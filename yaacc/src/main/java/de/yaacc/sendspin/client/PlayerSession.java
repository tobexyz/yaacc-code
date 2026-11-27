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
import de.yaacc.sendspin.hub.HubClock;
import de.yaacc.sendspin.secure.Base64Url;
import de.yaacc.sendspin.secure.Identity;
import de.yaacc.sendspin.secure.NoiseException;
import de.yaacc.sendspin.secure.NoiseSuite;
import de.yaacc.sendspin.secure.SendspinPsk;
import de.yaacc.sendspin.session.ClientHandshake;
import de.yaacc.sendspin.session.CommandThread;
import de.yaacc.sendspin.session.ControllerMessages;
import de.yaacc.sendspin.session.Envelope;
import de.yaacc.sendspin.session.PlayerMessages;
import de.yaacc.sendspin.session.ProtocolException;
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
 * This app as a Sendspin <b>player</b> for one connection of a server (use case P1, {@code player.md}): the connection has been opened by
 * the server (the phone listens) or by the app; the handshake is the client's either way. The session reads the hello and the activation
 * of the server, says what the player is ({@code client/hello}, {@code client/state}), keeps the clock estimate ({@code client/time}),
 * takes the commands for volume, mute and output delay and hands the stream to an {@link Audio} (the engine that plays it in sync).
 * The pairing as a client is the one of the controller: a Pairing PSK activation is answered when the app holds a token.
 * One thread; the callbacks run on it and must not block.
 */
public final class PlayerSession {

    public enum Phase {
        HANDSHAKING, WAITING_FOR_HELLO, WAITING_FOR_ACTIVATION, WAITING_FOR_SERVER,
        /** The player role is active: the server may stream. */
        ACTIVE,
        ENDED
    }

    public enum EndReason {
        NONE, CLOSED_BY_APP, FAILED, PROTOCOL_ERROR, PAIRING_REQUIRED, UNAUTHORIZED, UNPAIRED, SERVER_CLOSED,
        /** The phone took another server's activation instead (a rejected connection). */
        REJECTED
    }

    /** What the app does with the stream; the calls come from the session's thread. */
    public interface Audio {
        /**
         * A stream starts in this format (the session checked that it is one the player offered; {@code codecHeader} is null for PCM).
         *
         * @throws IllegalArgumentException if the codec header is not one the player can play (the session ends)
         */
        void start(String codec, int sampleRate, int channels, int bitDepth, byte[] codecHeader);

        /** One chunk: the audio and the time it has to be heard, on the server's clock. */
        void chunk(long serverTimestampMicros, byte[] pcm);

        /** Drops what is buffered ({@code fromTimestamp}: only what is stamped later; null: everything). */
        void clear(Long fromTimestamp);

        /** The stream ends (a pause or a stop): what is buffered is dropped. */
        void end();
    }

    /** The controls of the player (the volume and mute of this player, the delay of its output). */
    public interface Controls {
        int volume();

        boolean muted();

        int outputDelayMs();

        void setVolume(int volume);

        void setMuted(boolean muted);

        void setOutputDelayMs(int milliseconds);
    }

    public interface Listener {
        void onChanged(PlayerSession session);
    }

    /** Decides at every {@code server/activate} whether this connection may hold the activities it declares (several servers, one player). */
    public interface Arbiter {
        /**
         * @return true to go on; false to reject the connection ({@code concurrent_attempt}); a connection that is displaced for it gets
         * {@code another_server} from the arbiter
         */
        boolean admit(PlayerSession session, java.util.Set<String> activities);
    }

    static final List<String> COMMANDS = Collections.unmodifiableList(new ArrayList<>(java.util.Arrays.asList("volume", "mute", "set_output_delay")));
    private static final String ROLE = "player@v1";
    private static final long IDLE_LIMIT_MILLIS = 60_000L;
    private static final String PRODUCT_NAME = "YAACC";

    private final Connector.Link link;
    private final Identity identity;
    private final boolean unpairedAccess;
    private final ClientCredentials credentials;
    private final HubClock clock;
    private final SecureRandom random;
    private final String name;
    private final List<PlayerMessages.Offer> formats;
    private final long bufferCapacity;
    private final int requiredLeadMs;
    private final int minBufferMs;
    private final Audio audio;
    private final Controls controls;
    private final Listener listener;
    private final Arbiter arbiter;
    private final ServerClock serverClock = new ServerClock();
    private final ClientPairing pairing;
    private final ClockBursts bursts;
    private final Object lock = new Object();

    private volatile Phase phase = Phase.HANDSHAKING;
    private volatile EndReason endReason = EndReason.NONE;
    private volatile String serverName;
    private volatile String serverId;
    private volatile boolean streaming;
    private volatile boolean closing;
    private volatile boolean activated;
    private volatile long provisionalLimitMillis = 30_000L;
    private final long startedNanos = System.nanoTime();
    private volatile SecureChannel channel;
    private Thread thread;

    private SendspinPsk.Category matched = SendspinPsk.Category.SENTINEL;
    private boolean playerActive;
    private boolean stateSent;

    /**
     * @param link           the connection (its WebSocket handshake is done)
     * @param formats        the formats offered, in the order of preference
     * @param bufferCapacity the bytes the player can hold
     * @param requiredLeadMs how far ahead of the play time a chunk has to arrive at least
     * @param minBufferMs    the audio the player wants to hold at least
     */
    public PlayerSession(Connector.Link link, Identity identity, boolean unpairedAccess, ClientCredentials credentials, HubClock clock,
                         SecureRandom random, String name, List<PlayerMessages.Offer> formats, long bufferCapacity, int requiredLeadMs, int minBufferMs,
                         Audio audio, Controls controls, Listener listener) {
        this(link, identity, unpairedAccess, credentials, clock, random, name, formats, bufferCapacity, requiredLeadMs, minBufferMs, audio, controls,
                listener, null);
    }

    /** @param arbiter decides between servers at every activation; null: every activation is taken (a single server) */
    public PlayerSession(Connector.Link link, Identity identity, boolean unpairedAccess, ClientCredentials credentials, HubClock clock,
                         SecureRandom random, String name, List<PlayerMessages.Offer> formats, long bufferCapacity, int requiredLeadMs, int minBufferMs,
                         Audio audio, Controls controls, Listener listener, Arbiter arbiter) {
        this.arbiter = arbiter;
        this.link = link;
        this.identity = identity;
        this.unpairedAccess = unpairedAccess;
        this.credentials = credentials;
        this.pairing = new ClientPairing(credentials, random);
        this.bursts = new ClockBursts(clock, new ClockBursts.Sink() {
            @Override
            public void add(long t1, long t2, long t3, long t4) {
                synchronized (lock) {
                    serverClock.add(t1, t2, t3, t4);
                }
            }

            @Override
            public void endBurst() {
                synchronized (lock) {
                    serverClock.endBurst();
                }
            }

            @Override
            public void changed() {
                notifyChanged();
            }
        });
        this.clock = clock;
        this.random = random;
        this.name = name;
        this.formats = formats;
        this.bufferCapacity = bufferCapacity;
        this.requiredLeadMs = requiredLeadMs;
        this.minBufferMs = minBufferMs;
        this.audio = audio;
        this.controls = controls;
        this.listener = listener;
    }

    // ------------------------------------------------------------------ what the app can read

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

    /** True while a stream of the server is running. */
    public boolean isStreaming() {
        return streaming;
    }

    /** True if the PSK of the connection is a long-term one: the server is paired and proved who it is. */
    public boolean isPaired() {
        return matched == SendspinPsk.Category.LONG_TERM;
    }

    /** The server's clock as measured: the engine maps the chunk times with it. Safe to call from another thread. */
    public long serverToClientMicros(long serverMicros) {
        synchronized (lock) {
            return serverMicros - serverClock.offsetMicros();
        }
    }

    /** The offset of the server's clock from the client's (server minus client), in microseconds. */
    public long serverOffsetMicros() {
        synchronized (lock) {
            return serverClock.offsetMicros();
        }
    }

    public boolean hasClockEstimate() {
        synchronized (lock) {
            return serverClock.hasEstimate();
        }
    }

    void setBurstIntervalMillis(long millis) {
        bursts.setIntervalMillis(millis);
    }

    /** For tests: how long a connection may wait for its first activation (the specification says 30 seconds). */
    void setProvisionalLimitMillis(long millis) {
        provisionalLimitMillis = millis;
    }

    /** True while a pairing of a server with this app is under way on this connection (it is not displaced then). */
    public boolean isPairing() {
        return pairing.isPending();
    }

    // ------------------------------------------------------------------ lifecycle

    public void start() {
        synchronized (lock) {
            if (thread != null) {
                throw new IllegalStateException("started");
            }
            thread = new Thread(this::run, "sendspin-player");
            thread.setDaemon(true);
            thread.start();
        }
    }

    /** Ends the session: tells the server and closes. Safe to call more than once and from the main thread. */
    public void close() {
        close("user_request");
    }

    /** Ends the session because another server plays to this player now ({@code client/goodbye} {@code another_server}). */
    public void displace() {
        close("another_server");
    }

    private void close(final String reason) {
        if (closing) {
            return;
        }
        closing = true;
        final SecureChannel c = channel;
        CommandThread.run(() -> {
            if (c != null) {
                try {
                    c.sendJson(ControllerMessages.clientGoodbye(reason));
                } catch (IOException ignored) {
                    // the connection is gone
                }
                c.close(WsConnection.CLOSE_NORMAL);
            }
            closeLink();
        });
    }

    private void closeLink() {
        try {
            link.close();
        } catch (IOException ignored) {
            // closed
        }
    }

    // ------------------------------------------------------------------ the connection thread

    private void run() {
        try {
            setPhase(Phase.HANDSHAKING);
            ClientHandshake.Result result = ClientHandshake.perform(link.ws(), identity, NoiseSuite.CHACHA_POLY,
                    (id, category) -> credentials == null ? null : credentials.find(id, category), random);
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
        } catch (ProtocolException | WsConnection.ViolationException | NoiseException e) {
            YaaccLogger.d(getClass().getName(), "protocol error: " + e.getMessage());
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
            endStream();
            closeLink();
            if (phase != Phase.ENDED) {
                end(closing ? EndReason.CLOSED_BY_APP : endReason == EndReason.NONE ? EndReason.FAILED : endReason);
            }
        }
    }

    /** A connection that has not activated within the limit is dropped (spec: a provisional connection, 30 seconds). */
    private void checkProvisional() throws IOException {
        if (!activated && (System.nanoTime() - startedNanos) / 1_000_000L > provisionalLimitMillis) {
            throw new IOException("no activation");
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
                bursts.serve(c, stateSent);
                checkProvisional();
                if ((System.nanoTime() - lastRx) / 1_000_000L > IDLE_LIMIT_MILLIS) {
                    throw new IOException("no traffic");
                }
                continue;
            }
            checkProvisional();
            bursts.serve(c, stateSent); // not only when the line is idle: the audio of a stream keeps it busy
            if (!inbound.isJson()) {
                if (inbound.id() == 4 && streaming && playerActive) {
                    PlayerMessages.AudioChunk chunk = PlayerMessages.parseAudioChunk(inbound.body());
                    if (chunk != null) {
                        audio.chunk(chunk.timestampMicros, chunk.audio);
                    }
                }
                continue; // other binary data belongs to roles this client does not have
            }
            Envelope envelope = inbound.envelope();
            if (!helloSeen) {
                if (!"server/hello".equals(envelope.type())) {
                    throw new ProtocolException("server/hello expected");
                }
                serverName = ControllerMessages.parseServerHello(envelope.payload());
                helloSeen = true;
                c.sendJson(PlayerMessages.clientHello(name, PRODUCT_NAME, unpairedAccess, formats, bufferCapacity,
                        Collections.singletonList(ROLE)));
                setPhase(Phase.WAITING_FOR_ACTIVATION);
                continue;
            }
            switch (envelope.type()) {
                case "server/activate":
                    handleActivate(c, ControllerMessages.parseActivate(envelope.payload()));
                    break;
                case "stream/start":
                    handleStreamStart(envelope.payload());
                    break;
                case "stream/clear":
                    if (playerActive && streaming && PlayerMessages.clearsPlayer(envelope.payload())) {
                        audio.clear(PlayerMessages.clearFrom(envelope.payload()));
                    }
                    break;
                case "stream/end":
                    if (PlayerMessages.clearsPlayer(envelope.payload())) {
                        endStream();
                    }
                    break;
                case "server/command":
                    handleCommand(c, PlayerMessages.parseCommand(envelope.payload()));
                    break;
                case "server/time": {
                    long[] t = ControllerMessages.parseServerTime(envelope.payload());
                    bursts.handleServerTime(c, t);
                    break;
                }
                case "server/pair-finalize":
                    pairing.finish(c, serverId);
                    break;
                case "pair/abort":
                    pairing.clear();
                    break;
                case "server/unpair":
                    if (matched == SendspinPsk.Category.LONG_TERM) { // an unpaired session ignores it (spec)
                        if (credentials != null && serverId != null) {
                            credentials.unpair(serverId);
                        }
                        c.sendJson(ControllerMessages.clientGoodbye("unpaired"));
                        c.close(WsConnection.CLOSE_NORMAL);
                        end(EndReason.UNPAIRED);
                        return;
                    }
                    break;
                case "noise/handshake": {
                    if (credentials == null || serverId == null || c.isPlain()) {
                        throw new IOException("re-handshake is not supported");
                    }
                    byte[] message1 = Base64Url.decode(SendspinMessages.parseNoiseData(envelope.payload()));
                    if (message1 == null) {
                        throw new ProtocolException("bad noise data");
                    }
                    matched = ClientHandshake.rehandshake(c, identity, serverId, NoiseSuite.CHACHA_POLY, credentials, message1, random);
                    pairing.resetCount();
                    break;
                }
                default:
                    break; // unknown types are ignored
            }
        }
    }

    // ------------------------------------------------------------------ activation, stream, commands

    private void handleActivate(SecureChannel c, ControllerMessages.Activate a) throws IOException {
        // an activation without active_roles leaves the roles as they are: all the roles this client offered (the reference server does this)
        List<String> effective = a.activeRoles != null ? a.activeRoles : Collections.singletonList(ROLE);
        boolean canPair = credentials != null && matched == SendspinPsk.Category.PAIRING && credentials.holdsPairingPsk()
                && "pairing_psk".equals(a.pairingMethod);
        ControllerMessages.Admission admission = ControllerMessages.admit(matched, unpairedAccess, a.activities, effective, canPair);
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
        if (arbiter != null && !arbiter.admit(this, a.activities)) {
            // another server holds what this one asks for and ranks higher (or equal and paired): this connection is turned away
            c.sendJson(a.activities.contains("pairing") ? ControllerMessages.pairAbort("concurrent_attempt")
                    : ControllerMessages.clientGoodbye("concurrent_attempt"));
            c.close(WsConnection.CLOSE_NORMAL);
            end(EndReason.REJECTED);
            return;
        }
        activated = true;
        if (a.activities.contains("pairing")) {
            pairing.start(c);
            return;
        }
        boolean active = effective.contains(ROLE);
        if (playerActive && !active) {
            endStream(); // the server took the role away
        }
        playerActive = active;
        if (active) {
            if (!stateSent) {
                stateSent = true;
                bursts.reset();
            }
            sendState(c, true);
            setPhase(Phase.ACTIVE);
        } else {
            setPhase(Phase.WAITING_FOR_SERVER);
        }
    }

    /** Tells the server the volume, the mute and the delay as they are now (the user changed them on the phone). Any thread. */
    public void announceState() {
        final SecureChannel c = channel;
        if (c == null || !playerActive || closing) {
            return;
        }
        CommandThread.run(() -> {
            try {
                sendState(c, true);
            } catch (IOException e) {
                // the connection is gone; its thread ends the session
            }
        });
    }

    private void sendState(SecureChannel c, boolean available) throws IOException {
        c.sendJson(PlayerMessages.clientState(available, controls.volume(), controls.muted(), controls.outputDelayMs(), requiredLeadMs,
                minBufferMs, COMMANDS));
    }

    private void handleStreamStart(org.json.JSONObject payload) throws ProtocolException {
        PlayerMessages.StreamStart start = PlayerMessages.parseStreamStart(payload);
        if (start == null || !playerActive) {
            return;
        }
        boolean offered = false;
        for (PlayerMessages.Offer f : formats) {
            offered |= f.codec.equals(start.codec) && f.sampleRate == start.sampleRate && f.channels == start.channels
                    && f.bitDepth == start.bitDepth;
        }
        if (!offered) {
            throw new ProtocolException("a format that was not offered");
        }
        if ("flac".equals(start.codec) && start.codecHeader == null) {
            throw new ProtocolException("a flac stream without its header");
        }
        try {
            audio.start(start.codec, start.sampleRate, start.channels, start.bitDepth, start.codecHeader);
        } catch (IllegalArgumentException badHeader) {
            throw new ProtocolException("codec_header");
        }
        streaming = true;
        notifyChanged();
    }

    private void endStream() {
        if (streaming) {
            streaming = false;
            audio.end();
            notifyChanged();
        }
    }

    private void handleCommand(SecureChannel c, PlayerMessages.Command command) throws IOException {
        if (command == null || !playerActive) {
            return;
        }
        boolean changed = false;
        if ("volume".equals(command.command) && command.volume != null) {
            controls.setVolume(command.volume);
            changed = true;
        } else if ("mute".equals(command.command) && command.mute != null) {
            controls.setMuted(command.mute);
            changed = true;
        } else if ("set_output_delay".equals(command.command) && command.outputDelayMs != null) {
            controls.setOutputDelayMs(command.outputDelayMs);
            changed = true;
        }
        if (changed) {
            sendState(c, true); // the server learns what the player took
        }
    }

    // ------------------------------------------------------------------ the pairing as a client (as of the controller)

    // ------------------------------------------------------------------ the clock bursts

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
            listener.onChanged(this);
        }
    }
}
