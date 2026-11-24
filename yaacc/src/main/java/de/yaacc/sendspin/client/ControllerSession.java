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

import de.yaacc.sendspin.hub.Connector;
import de.yaacc.sendspin.hub.Endpoint;
import de.yaacc.sendspin.hub.HubClock;
import de.yaacc.sendspin.secure.Identity;
import de.yaacc.sendspin.secure.NoiseException;
import de.yaacc.sendspin.secure.NoiseSuite;
import de.yaacc.sendspin.secure.SendspinPsk;
import de.yaacc.sendspin.session.ClientHandshake;
import de.yaacc.sendspin.session.ControllerMessages;
import de.yaacc.sendspin.session.Envelope;
import de.yaacc.sendspin.session.ProtocolException;
import de.yaacc.sendspin.session.CommandThread;
import de.yaacc.sendspin.session.SecureChannel;
import de.yaacc.sendspin.session.SendspinMessages;
import de.yaacc.sendspin.session.WsConnection;
import de.yaacc.util.YaaccLogger;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The phone as a Sendspin client with the roles {@code controller@v1} and {@code metadata@v1} (issue #325, use cases C1 to C3): one
 * connection to one server (the phone opens it, client-initiated mode) that shows what the server plays in the group this client is
 * in and sends commands for it. It plays no audio. One thread runs the connection ({@link #start()}); the app reads the state and
 * sends commands from its own threads. The session does not reconnect: it ends and says why ({@link #endReason()}).
 * <p>
 * This first slice has no pairing as a client: it uses the Sentinel PSK (an unpaired session, which the server's operator has to
 * allow, and which the protocol warns is not authenticated), answers a pairing activation with {@code method_not_supported} and
 * ends on a re-handshake request.
 */
public final class ControllerSession {

    public enum Phase {
        CONNECTING, HANDSHAKING, WAITING_FOR_HELLO, WAITING_FOR_ACTIVATION,
        /** Activated with no role: the server holds this client but has not (yet) let it control or display. */
        WAITING_FOR_SERVER,
        /** The controller role is active: state and commands work. */
        ACTIVE,
        ENDED
    }

    public enum EndReason {
        NONE, CLOSED_BY_APP, FAILED, PROTOCOL_ERROR, PAIRING_REQUIRED, UNAUTHORIZED, UNPAIRED, SERVER_CLOSED
    }

    public interface Listener {
        /** Something changed; called on the session's thread or the caller's, never with a lock held. */
        void onChanged(ControllerSession session);
    }

    private static final long IDLE_LIMIT_MILLIS = 60_000L;
    private static final int BURST_SIZE = 8;
    private static final long BURST_TIMEOUT_MICROS = 2_000_000L;
    private static final String PRODUCT_NAME = "YAACC";
    /** The one artwork channel this client asks for: the album image, JPEG, a square the server scales to fit and pads with black. */
    static final String ARTWORK_FORMAT = "jpeg";
    static final int ARTWORK_SIZE = 512;

    private final Endpoint endpoint;
    private final Identity identity;
    private final boolean unpairedAccess;
    private final Connector connector;
    private final HubClock clock;
    private final SecureRandom random;
    private final String clientName;
    private final Listener listener;
    private final Object lock = new Object();

    private volatile Phase phase = Phase.CONNECTING;
    private volatile EndReason endReason = EndReason.NONE;
    private volatile String serverName;
    private volatile String serverId;
    private volatile ControllerMessages.Group group;
    private volatile ControllerMessages.ControllerState controller;
    private final TrackPosition.Tracker tracker = new TrackPosition.Tracker();
    private final ServerClock serverClock = new ServerClock();
    private final ArtworkStream artwork = new ArtworkStream();
    private volatile SecureChannel channel;
    private volatile Connector.Link link;
    private volatile boolean closing;
    private volatile long burstIntervalMillis = 3000L;
    private Thread thread;

    // the loop thread's
    private SendspinPsk.Category matched = SendspinPsk.Category.SENTINEL;
    private List<String> roles = Collections.emptyList();
    private boolean stateSent;
    private boolean artworkStateSent;
    private int burstSent;
    private long burstT1;
    private long burstSentAt;
    private long nextBurstAt;

    public ControllerSession(Endpoint endpoint, Identity identity, boolean unpairedAccess, Connector connector, HubClock clock,
                             SecureRandom random, String clientName, Listener listener) {
        this.endpoint = endpoint;
        this.identity = identity;
        this.unpairedAccess = unpairedAccess;
        this.connector = connector;
        this.clock = clock;
        this.random = random;
        this.clientName = clientName;
        this.listener = listener;
    }

    // ------------------------------------------------------------------ what the app can read

    public Endpoint endpoint() {
        return endpoint;
    }

    public Phase phase() {
        return phase;
    }

    public EndReason endReason() {
        return endReason;
    }

    public String serverName() {
        return serverName;
    }

    public String serverId() {
        return serverId;
    }

    /** The group this client is in, or null before the first {@code group/update}. */
    public ControllerMessages.Group group() {
        return group;
    }

    /** What the group can do and its volume, or null before the first state of the controller role. */
    public ControllerMessages.ControllerState controller() {
        return controller;
    }

    /** The metadata to show now (a scheduled update whose time has come is applied), or null. */
    public ControllerMessages.Metadata metadata() {
        synchronized (lock) {
            return tracker.current(serverNowMicros());
        }
    }

    /** The scheduled update that follows (a coming-up display), or null. */
    public ControllerMessages.Metadata pendingMetadata() {
        synchronized (lock) {
            tracker.current(serverNowMicros());
            return tracker.pending();
        }
    }

    /** The position in the current track in milliseconds, or -1 if the server reports none. */
    public long positionMs() {
        return TrackPosition.positionMs(metadata(), serverNowMicros());
    }

    /** The album image the server shows this client now (JPEG or PNG bytes), or null if there is none. */
    public byte[] artwork() {
        synchronized (lock) {
            return artwork.current(0, serverNowMicros());
        }
    }

    /** Increases whenever the image changes, so that a display decodes it again only then. */
    public long artworkVersion() {
        synchronized (lock) {
            artwork.current(0, serverNowMicros()); // a pending image whose time has come is applied first
            return artwork.version();
        }
    }

    public boolean hasClockEstimate() {
        synchronized (lock) {
            return serverClock.hasEstimate();
        }
    }

    private long serverNowMicros() {
        synchronized (lock) {
            return serverClock.serverTimeAt(clock.nowMicros());
        }
    }

    /** The interval between two bursts of {@code client/time}; the specification allows at most 10 seconds. Tests make it short. */
    void setBurstIntervalMillis(long millis) {
        burstIntervalMillis = millis;
    }

    // ------------------------------------------------------------------ lifecycle

    public void start() {
        synchronized (lock) {
            if (thread != null) {
                throw new IllegalStateException("started");
            }
            thread = new Thread(this::run, "sendspin-controller");
            thread.setDaemon(true);
            thread.start();
        }
    }

    /** Ends the session: tells the server the user asked for it and closes. Safe to call more than once. */
    public void close() {
        if (closing) {
            return;
        }
        closing = true;
        final SecureChannel c = channel;
        CommandThread.run(() -> {
            if (c != null) {
                try {
                    c.sendJson(ControllerMessages.clientGoodbye("user_request"));
                } catch (IOException ignored) {
                    // the connection is gone
                }
                c.close(WsConnection.CLOSE_NORMAL);
            }
            closeLink();
        });
    }

    private void closeLink() {
        Connector.Link l = link;
        if (l != null) {
            try {
                l.close();
            } catch (IOException ignored) {
                // closed
            }
        }
    }

    // ------------------------------------------------------------------ commands

    private boolean send(String command, String json) {
        SecureChannel c = channel;
        ControllerMessages.ControllerState s = controller;
        if (phase != Phase.ACTIVE || c == null || s == null || !s.supports(command) || !roles.contains("controller@v1")) {
            return false;
        }
        // accepted: the write itself never runs on the main thread (a tap is made there)
        CommandThread.run(() -> {
            try {
                c.sendJson(json);
            } catch (IOException e) {
                // the connection is gone; its thread ends the session
            }
        });
        return true;
    }

    public boolean play() {
        return send("play", ControllerMessages.commandPlain("play"));
    }

    public boolean pause() {
        return send("pause", ControllerMessages.commandPlain("pause"));
    }

    public boolean stop() {
        return send("stop", ControllerMessages.commandPlain("stop"));
    }

    public boolean next() {
        return send("next", ControllerMessages.commandPlain("next"));
    }

    public boolean previous() {
        return send("previous", ControllerMessages.commandPlain("previous"));
    }

    public boolean setVolume(int volume) {
        return send("volume", ControllerMessages.commandVolume(Math.max(0, Math.min(100, volume))));
    }

    public boolean setMuted(boolean muted) {
        return send("mute", ControllerMessages.commandMute(muted));
    }

    /** @param repeat {@code "off"}, {@code "one"} or {@code "all"} */
    public boolean setRepeat(String repeat) {
        String command = "one".equals(repeat) ? "repeat_one" : "all".equals(repeat) ? "repeat_all" : "repeat_off";
        return send(command, ControllerMessages.commandPlain(command));
    }

    public boolean setShuffle(boolean shuffle) {
        String command = shuffle ? "shuffle" : "unshuffle";
        return send(command, ControllerMessages.commandPlain(command));
    }

    /** Moves this client to the next group of the server's cycle. */
    public boolean switchGroup() {
        return send("switch", ControllerMessages.commandPlain("switch"));
    }

    /** @param positionMs clamped to the seek range the server reports */
    public boolean seek(long positionMs) {
        ControllerMessages.ControllerState s = controller;
        long max = s == null || s.seekMaxMs == null ? Long.MAX_VALUE : s.seekMaxMs;
        return send("seek", ControllerMessages.commandSeek(Math.max(0, Math.min(max, positionMs))));
    }

    public boolean seekRelative(long offsetMs) {
        return send("seek_relative", ControllerMessages.commandSeekRelative(offsetMs));
    }

    // ------------------------------------------------------------------ the connection thread

    private void run() {
        try {
            setPhase(Phase.CONNECTING);
            link = connector.open(endpoint, random);
            if (closing) {
                return;
            }
            setPhase(Phase.HANDSHAKING);
            ClientHandshake.Result result = ClientHandshake.perform(link.ws(), identity, NoiseSuite.CHACHA_POLY,
                    (id, category) -> null, random);
            channel = result.channel;
            serverId = result.serverId;
            matched = result.matched;
            setPhase(Phase.WAITING_FOR_HELLO);
            loop(result.channel);
        } catch (SocketTimeoutException e) {
            fail(EndReason.FAILED);
        } catch (WsConnection.ClosedException e) {
            if (endReason == EndReason.NONE) {
                end(closing ? EndReason.CLOSED_BY_APP : EndReason.SERVER_CLOSED);
            }
        } catch (ProtocolException | WsConnection.ViolationException e) {
            YaaccLogger.d(getClass().getName(), "protocol error: " + e.getMessage());
            fail(EndReason.PROTOCOL_ERROR);
        } catch (NoiseException e) {
            fail(EndReason.PROTOCOL_ERROR);
        } catch (IOException e) {
            if (closing) {
                end(EndReason.CLOSED_BY_APP);
            } else {
                YaaccLogger.d(getClass().getName(), "connection failed: " + e.getClass().getSimpleName());
                fail(EndReason.FAILED);
            }
        } catch (RuntimeException e) {
            YaaccLogger.d(getClass().getName(), "unexpected failure: " + e.getClass().getSimpleName());
            fail(EndReason.FAILED);
        } finally {
            closeLink();
            if (phase != Phase.ENDED) {
                end(closing ? EndReason.CLOSED_BY_APP : endReason == EndReason.NONE ? EndReason.FAILED : endReason);
            }
        }
    }

    private void loop(SecureChannel c) throws IOException, ProtocolException, NoiseException {
        long lastRx = System.nanoTime();
        boolean helloSeen = false;
        while (!closing && phase != Phase.ENDED) {
            SecureChannel.Inbound inbound;
            try {
                inbound = c.receive();
                lastRx = System.nanoTime();
            } catch (SocketTimeoutException idle) {
                serveBursts(c);
                if ((System.nanoTime() - lastRx) / 1_000_000L > IDLE_LIMIT_MILLIS) {
                    throw new IOException("no traffic");
                }
                continue;
            }
            serveBursts(c);
            if (!inbound.isJson()) {
                int id = inbound.id();
                if (id >= ArtworkStream.FIRST_TYPE && id < ArtworkStream.FIRST_TYPE + ArtworkStream.MAX_CHANNELS) {
                    long before;
                    long after;
                    synchronized (lock) {
                        before = artwork.version();
                        artwork.onMessage(id, inbound.body(), serverNowMicros()); // a malformed message ends the connection (spec)
                        after = artwork.version();
                    }
                    if (after != before) {
                        notifyChanged();
                    }
                }
                continue; // other binary data belongs to roles this client does not have; ignored as the specification asks
            }
            Envelope envelope = inbound.envelope();
            if (!helloSeen) {
                if (!"server/hello".equals(envelope.type())) {
                    throw new ProtocolException("server/hello expected");
                }
                serverName = ControllerMessages.parseServerHello(envelope.payload());
                helloSeen = true;
                c.sendJson(ControllerMessages.clientHello(clientName, PRODUCT_NAME, null, unpairedAccess));
                setPhase(Phase.WAITING_FOR_ACTIVATION);
                continue;
            }
            switch (envelope.type()) {
                case "server/activate":
                    handleActivate(c, ControllerMessages.parseActivate(envelope.payload()));
                    break;
                case "group/update":
                    group = ControllerMessages.parseGroupUpdate(envelope.payload());
                    notifyChanged();
                    break;
                case "server/state":
                    handleServerState(ControllerMessages.parseServerState(envelope.payload()));
                    break;
                case "stream/start": {
                    java.util.List<ControllerMessages.ArtworkChannel> channels = ControllerMessages.parseStreamStartArtwork(envelope.payload());
                    if (channels != null && roles.contains("artwork@v1")) {
                        synchronized (lock) {
                            artwork.start(channels);
                        }
                        notifyChanged();
                    }
                    break; // the player and visualizer objects are for roles this client does not have
                }
                case "stream/end": {
                    java.util.List<String> ended = ControllerMessages.parseStreamEndRoles(envelope.payload());
                    if (ended == null || ended.contains("artwork")) {
                        synchronized (lock) {
                            artwork.end();
                        }
                        notifyChanged();
                    }
                    break;
                }
                case "server/time":
                    handleServerTime(c, ControllerMessages.parseServerTime(envelope.payload()));
                    break;
                case "server/unpair":
                    if (matched == SendspinPsk.Category.LONG_TERM) { // an unpaired session ignores it (spec)
                        c.sendJson(ControllerMessages.clientGoodbye("unpaired"));
                        c.close(WsConnection.CLOSE_NORMAL);
                        end(EndReason.UNPAIRED);
                        return;
                    }
                    break;
                case "noise/handshake":
                    // a re-handshake needs the responder side with the pairing records of this client: not part of this slice
                    throw new IOException("re-handshake is not supported");
                default:
                    break; // unknown types are ignored
            }
        }
    }

    private void handleActivate(SecureChannel c, ControllerMessages.Activate a) throws IOException {
        List<String> effective = a.activeRoles != null ? a.activeRoles : roles;
        // this client offers the Pairing PSK in its hello but has no way to complete a pairing yet: any method is one it can not do
        ControllerMessages.Admission admission = ControllerMessages.admit(matched, unpairedAccess, a.activities, effective, false);
        switch (admission) {
            case PAIRING_REQUIRED:
                c.sendJson(ControllerMessages.clientGoodbye("pairing_required"));
                c.close(WsConnection.CLOSE_NORMAL);
                end(EndReason.PAIRING_REQUIRED);
                return;
            case UNAUTHORIZED:
                c.sendJson(ControllerMessages.clientGoodbye("unauthorized"));
                c.close(WsConnection.CLOSE_NORMAL);
                end(EndReason.UNAUTHORIZED);
                return;
            case METHOD_NOT_SUPPORTED:
                c.sendJson(ControllerMessages.pairAbort("method_not_supported"));
                return;
            case OK:
            default:
                break;
        }
        roles = new ArrayList<>(effective);
        boolean wantsArtwork = roles.contains("artwork@v1");
        boolean active = roles.contains("controller@v1") || roles.contains("metadata@v1") || wantsArtwork;
        if (!wantsArtwork) {
            synchronized (lock) {
                artwork.end();
            }
        }
        if (!active) {
            stateSent = false;
            artworkStateSent = false;
            controller = null;
            synchronized (lock) {
                tracker.clear();
            }
            setPhase(Phase.WAITING_FOR_SERVER);
            return;
        }
        if (!stateSent || wantsArtwork != artworkStateSent) {
            // a role with a state object (artwork) that becomes active needs its object in a client/state before the server streams
            c.sendJson(wantsArtwork ? ControllerMessages.clientStateWithArtwork(true, ARTWORK_FORMAT, ARTWORK_SIZE, ARTWORK_SIZE)
                    : ControllerMessages.clientState(true));
            if (!stateSent) {
                nextBurstAt = 0;
            }
            stateSent = true;
            artworkStateSent = wantsArtwork;
        }
        setPhase(roles.contains("controller@v1") ? Phase.ACTIVE : Phase.WAITING_FOR_SERVER);
    }

    private void handleServerState(ControllerMessages.ServerState state) {
        if (state.controller != null) {
            controller = state.controller;
        }
        if (state.metadata != null) {
            synchronized (lock) {
                tracker.accept(state.metadata, serverNowMicros());
            }
        }
        notifyChanged();
    }

    // ------------------------------------------------------------------ the clock bursts

    /** Starts a burst when it is time and the roles are active; ends one that got no answer. */
    private void serveBursts(SecureChannel c) throws IOException {
        if (!stateSent) {
            return;
        }
        long now = clock.nowMicros();
        if (burstSent > 0 && now - burstSentAt > BURST_TIMEOUT_MICROS) {
            finishBurst(now); // no answer in time: the burst ends with what it has
            return;
        }
        if (burstSent == 0 && now >= nextBurstAt) {
            sendTime(c, now);
        }
    }

    private void sendTime(SecureChannel c, long now) throws IOException {
        burstT1 = now;
        burstSentAt = now;
        burstSent++;
        c.sendJson(ControllerMessages.clientTime(now));
    }

    private void handleServerTime(SecureChannel c, long[] t) throws IOException {
        long t4 = clock.nowMicros();
        if (burstSent == 0 || t[0] != burstT1) {
            return; // not the answer to the message in flight
        }
        synchronized (lock) {
            serverClock.add(t[0], t[1], t[2], t4);
        }
        if (burstSent >= BURST_SIZE) {
            finishBurst(t4);
        } else {
            sendTime(c, t4);
        }
    }

    private void finishBurst(long now) {
        synchronized (lock) {
            serverClock.endBurst();
        }
        burstSent = 0;
        nextBurstAt = now + burstIntervalMillis * 1000L;
        notifyChanged();
    }

    // ------------------------------------------------------------------ state

    private void fail(EndReason reason) {
        end(reason);
    }

    private void end(EndReason reason) {
        if (endReason == EndReason.NONE) {
            endReason = reason;
        }
        setPhase(Phase.ENDED);
    }

    private void setPhase(Phase next) {
        phase = next;
        notifyChanged();
    }

    private void notifyChanged() {
        if (listener != null) {
            try {
                listener.onChanged(this);
            } catch (RuntimeException e) {
                YaaccLogger.d(getClass().getName(), "listener failed: " + e.getClass().getSimpleName());
            }
        }
    }
}
