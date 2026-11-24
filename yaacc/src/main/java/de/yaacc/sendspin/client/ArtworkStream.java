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
package de.yaacc.sendspin.client;

import de.yaacc.sendspin.session.ControllerMessages.ArtworkChannel;
import de.yaacc.sendspin.session.ProtocolException;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The artwork role of a client (spec, "Artwork messages"): the images that arrive as an announce and parts in binary messages of
 * the types 8 to 11 (channels 0 to 3), one transfer in flight at a time for all channels. Per channel the client keeps the current
 * image and at most one pending image, which becomes current once it is complete and its timestamp (server clock) is reached; an
 * announce discards the pending image of its channel, a cancel too. An image above {@link #MAX_IMAGE_BYTES} is discarded but its
 * parts are still counted. Malformed messages and sequences are {@link ProtocolException}s (the client closes). The server is
 * untrusted: the memory of an image is bounded by the limit. Pure; used from one thread or locked by the caller.
 */
public final class ArtworkStream {

    /** The largest encoded image the client keeps: a 600 by 600 JPEG is far below it. */
    public static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;
    /** The largest artwork message including its type byte (spec): it fits one transport message. */
    public static final int MAX_MESSAGE_BYTES = 65519;
    /** The channels of the role (binary message types 8 to 11). */
    public static final int MAX_CHANNELS = 4;
    /** The first binary message type of the role. */
    public static final int FIRST_TYPE = 8;

    /** An image that has been announced: complete or not yet, to be shown at its timestamp; no data means "clear the channel". */
    private static final class Image {
        final long timestamp;
        final byte[] data;

        Image(long timestamp, byte[] data) {
            this.timestamp = timestamp;
            this.data = data;
        }
    }

    private List<ArtworkChannel> channels = Collections.emptyList();
    private boolean active;
    private final byte[][] current = new byte[MAX_CHANNELS][];
    private final Image[] pending = new Image[MAX_CHANNELS];
    private long version;

    // the transfer in flight
    private boolean inFlight;
    private int flightChannel;
    private long flightTotal;
    private long flightReceived;
    private long flightTimestamp;
    private ByteArrayOutputStream flightData; // null while the image is discarded

    /** A {@code stream/start} with an {@code artwork} object: the stream is active with this configuration. */
    public void start(List<ArtworkChannel> configuration) {
        List<ArtworkChannel> old = channels;
        channels = Collections.unmodifiableList(new ArrayList<>(configuration.subList(0, Math.min(MAX_CHANNELS, configuration.size()))));
        active = true;
        inFlight = false; // the server cancels a transfer before it changes its configuration
        for (int i = 0; i < MAX_CHANNELS; i++) {
            ArtworkChannel before = i < old.size() ? old.get(i) : null;
            ArtworkChannel now = i < channels.size() ? channels.get(i) : null;
            if ((before == null || !before.sameAs(now)) && current[i] != null) {
                current[i] = null;
                version++;
            }
            if (before == null || !before.sameAs(now)) {
                pending[i] = null;
            }
        }
    }

    /** A {@code stream/end} for the artwork role: the channels show no artwork and nothing is pending. */
    public void end() {
        active = false;
        inFlight = false;
        flightData = null;
        for (int i = 0; i < MAX_CHANNELS; i++) {
            if (current[i] != null) {
                version++;
            }
            current[i] = null;
            pending[i] = null;
        }
    }

    public boolean isActive() {
        return active;
    }

    /** Increases whenever the image of a channel changes, so that a display redraws only then. */
    public long version() {
        return version;
    }

    private boolean configured(int channel) {
        return channel < channels.size() && channels.get(channel).isActive();
    }

    /**
     * One binary artwork message.
     *
     * @param type          the message type, 8 to 11
     * @param body          the bytes after the type byte: the flags and what follows
     * @param serverNowMicros the server clock now
     */
    public void onMessage(int type, byte[] body, long serverNowMicros) throws ProtocolException {
        if (type < FIRST_TYPE || type >= FIRST_TYPE + MAX_CHANNELS) {
            throw new ProtocolException("not an artwork message");
        }
        if (body.length < 1 || body.length + 1 > MAX_MESSAGE_BYTES) {
            throw new ProtocolException("artwork message size");
        }
        int flags = body[0] & 0xFF;
        if ((flags & 0xFC) != 0) {
            throw new ProtocolException("reserved artwork flags");
        }
        boolean cancel = (flags & 0x01) != 0;
        boolean announce = (flags & 0x02) != 0;
        if (cancel && announce) {
            throw new ProtocolException("artwork cancel and announce");
        }
        if (cancel && body.length != 1) {
            throw new ProtocolException("artwork cancel length");
        }
        if (announce && body.length != 13) {
            throw new ProtocolException("artwork announce length");
        }
        if (!active) {
            return; // outside an active stream: the server must not send it; nothing is kept
        }
        int channel = type - FIRST_TYPE;
        if (cancel) {
            pending[channel] = null;
            if (inFlight && flightChannel == channel) {
                inFlight = false;
                flightData = null;
            }
        } else if (announce) {
            if (inFlight) {
                throw new ProtocolException("artwork announce while a transfer is in flight");
            }
            ByteBuffer b = ByteBuffer.wrap(body, 1, 12);
            long timestamp = b.getLong();
            long total = b.getInt() & 0xFFFFFFFFL;
            pending[channel] = null; // an announce discards the pending image of its channel
            if (total == 0) {
                pending[channel] = new Image(timestamp, null);
                applyDue(channel, serverNowMicros);
                return;
            }
            inFlight = true;
            flightChannel = channel;
            flightTotal = total;
            flightReceived = 0;
            flightTimestamp = timestamp;
            flightData = configured(channel) && total <= MAX_IMAGE_BYTES ? new ByteArrayOutputStream((int) Math.min(total, 65536)) : null;
        } else {
            if (!inFlight || flightChannel != channel) {
                throw new ProtocolException("artwork part without its transfer");
            }
            int length = body.length - 1;
            if (flightReceived + length > flightTotal) {
                throw new ProtocolException("artwork part past the total size");
            }
            if (flightData != null) {
                flightData.write(body, 1, length);
            }
            flightReceived += length;
            if (flightReceived == flightTotal) {
                if (flightData != null) {
                    pending[channel] = new Image(flightTimestamp, flightData.toByteArray());
                    applyDue(channel, serverNowMicros);
                }
                inFlight = false;
                flightData = null;
            }
        }
    }

    private void applyDue(int channel, long serverNowMicros) {
        Image image = pending[channel];
        if (image != null && image.timestamp <= serverNowMicros) {
            current[channel] = image.data;
            pending[channel] = null;
            version++;
        }
    }

    /** The image the channel shows at this time (a pending one whose time has come is applied first), or null for none. */
    public byte[] current(int channel, long serverNowMicros) {
        if (channel < 0 || channel >= MAX_CHANNELS) {
            return null;
        }
        applyDue(channel, serverNowMicros);
        return current[channel];
    }

    /** The complete image that waits for its time (a coming-up display), or null. */
    public byte[] pending(int channel) {
        if (channel < 0 || channel >= MAX_CHANNELS) {
            return null;
        }
        Image image = pending[channel];
        return image == null ? null : image.data;
    }
}
