/*
 *
 * Copyright (C) 2026 Tobias Schoene www.yaacc.de
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package de.yaacc.sendspin.control;

import de.yaacc.sendspin.hub.HubClock;
import de.yaacc.sendspin.session.ArtworkWire;
import de.yaacc.sendspin.session.ControllerMessages;
import de.yaacc.sendspin.session.SecureChannel;
import de.yaacc.util.YaaccLogger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The artwork role of one controller (issue #325, K15; spec {@code roles/artwork/v1.md}): the artwork stream and the images of the
 * cover of what plays, on the channels the client declared. One worker thread does every write of the role, so that there is at most
 * one image in flight, a newer request cancels the transfer in flight before the next one is announced, and a changed configuration
 * is preceded by the clear or the cancel the specification demands. A channel the app cannot serve (the source "artist", a cover that
 * is not there, a size beyond the cap) gets an empty image, which clears it and keeps the declared configuration as it is.
 */
final class ArtworkFeed {

    /** The most pixels of a channel the server renders (16 MB as a bitmap): a client that asks for more gets empty images. */
    static final int MAX_SIDE = 2048;
    static final int MAX_PIXELS = 4 * 1024 * 1024;
    private static final long PART_PAUSE_MILLIS = 5L;

    private final SecureChannel channel;
    private final ArtworkEncoder encoder;
    private final HubClock clock;
    private final Object lock = new Object();

    private List<ControllerMessages.ArtworkChannel> wanted = new ArrayList<>();
    private String wantedUrl;
    private boolean available;
    private long generation;
    private boolean closed;
    private Thread worker;

    // the worker's: what has been sent
    private List<ControllerMessages.ArtworkChannel> applied = new ArrayList<>();
    private String appliedUrl;
    private boolean streaming;

    ArtworkFeed(SecureChannel channel, ArtworkEncoder encoder, HubClock clock) {
        this.channel = channel;
        this.encoder = encoder;
        this.clock = clock;
    }

    /** A {@code client/state} of the client: the channels it wants now and whether it is available. */
    void configure(List<ControllerMessages.ArtworkChannel> channels, boolean isAvailable) {
        synchronized (lock) {
            wanted = new ArrayList<>(channels.subList(0, Math.min(ArtworkWire.MAX_CHANNELS, channels.size())));
            available = isAvailable;
            changed();
        }
    }

    /** The cover of what plays now (null: none). Does nothing if it is the one that was sent. */
    void imageUrl(String url) {
        synchronized (lock) {
            if (url == null ? wantedUrl == null : url.equals(wantedUrl)) {
                return;
            }
            wantedUrl = url;
            changed();
        }
    }

    void close() {
        synchronized (lock) {
            closed = true;
            lock.notifyAll();
        }
    }

    private void changed() {
        generation++;
        if (worker == null && !closed) {
            worker = new Thread(this::run, "sendspin-artwork");
            worker.setDaemon(true);
            worker.start();
        }
        lock.notifyAll();
    }

    private void run() {
        try {
            long done = -1;
            while (true) {
                List<ControllerMessages.ArtworkChannel> target;
                String url;
                boolean ready;
                long mine;
                synchronized (lock) {
                    while (!closed && generation == done) {
                        lock.wait();
                    }
                    if (closed) {
                        return;
                    }
                    mine = generation;
                    target = new ArrayList<>(wanted);
                    url = wantedUrl;
                    ready = available;
                }
                if (ready) {
                    apply(target, url, mine);
                }
                done = mine;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            YaaccLogger.d(getClass().getName(), "artwork: the connection failed");
        } catch (RuntimeException e) {
            YaaccLogger.d(getClass().getName(), "artwork failed: " + e.getClass().getSimpleName());
        }
    }

    private boolean superseded(long mine) {
        synchronized (lock) {
            return closed || generation != mine;
        }
    }

    private static boolean anyActive(List<ControllerMessages.ArtworkChannel> channels) {
        for (ControllerMessages.ArtworkChannel c : channels) {
            if (c.isActive()) {
                return true;
            }
        }
        return false;
    }

    private static boolean same(List<ControllerMessages.ArtworkChannel> a, List<ControllerMessages.ArtworkChannel> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).sameAs(b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private void apply(List<ControllerMessages.ArtworkChannel> target, String url, long mine) throws IOException, InterruptedException {
        boolean configChanged = !same(applied, target);
        if (configChanged) {
            // a channel that stops showing artwork is cleared first (the specification), before its source becomes none
            for (int i = 0; i < applied.size(); i++) {
                boolean stays = i < target.size() && target.get(i).isActive();
                if (applied.get(i).isActive() && !stays) {
                    sendImage(i, null);
                }
            }
            if (anyActive(target)) {
                channel.sendJson(ArtworkWire.streamStart(clock.nowMicros(), target));
                streaming = true;
            } else if (streaming) {
                channel.sendJson(ArtworkWire.streamEnd());
                streaming = false;
            }
            applied = target;
        }
        if (!streaming || (!configChanged && java.util.Objects.equals(url, appliedUrl))) {
            return;
        }
        for (int i = 0; i < target.size(); i++) {
            ControllerMessages.ArtworkChannel c = target.get(i);
            if (!c.isActive()) {
                continue;
            }
            byte[] image = null;
            if ("album".equals(c.source) && url != null && c.width <= MAX_SIDE && c.height <= MAX_SIDE && (long) c.width * c.height <= MAX_PIXELS) {
                try {
                    image = encoder.encode(url, c.format, c.width, c.height);
                } catch (IOException e) {
                    image = null; // no cover: the channel is cleared
                }
            }
            if (superseded(mine)) {
                return; // a newer request: it is dealt with next
            }
            if (!sendImage(i, image, mine)) {
                return;
            }
        }
        appliedUrl = url;
    }

    private void sendImage(int channelIndex, byte[] image) throws IOException, InterruptedException {
        sendImage(channelIndex, image, -1);
    }

    /** One image: the announce, then the parts paced. @return false if a newer request cancelled the transfer */
    private boolean sendImage(int channelIndex, byte[] image, long mine) throws IOException, InterruptedException {
        int type = ArtworkWire.typeOf(channelIndex);
        int total = image == null ? 0 : image.length;
        channel.sendMessage(type, ArtworkWire.announce(total, clock.nowMicros()));
        int offset = 0;
        while (offset < total) {
            if (mine >= 0 && superseded(mine)) {
                channel.sendMessage(type, ArtworkWire.cancel()); // the transfer in flight is cancelled before the next announce
                return false;
            }
            int length = Math.min(ArtworkWire.MAX_PART_DATA, total - offset);
            channel.sendMessage(type, ArtworkWire.part(image, offset, length));
            offset += length;
            if (offset < total) {
                Thread.sleep(PART_PAUSE_MILLIS);
            }
        }
        return true;
    }
}
