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
package de.yaacc.sendspin.hub;

import de.yaacc.sendspin.secure.Identity;
import de.yaacc.sendspin.secure.NoiseException;
import de.yaacc.sendspin.secure.SendspinPsk;
import de.yaacc.sendspin.session.Envelope;
import de.yaacc.sendspin.session.ProtocolException;
import de.yaacc.sendspin.session.SecureChannel;
import de.yaacc.sendspin.session.SendspinMessages;
import de.yaacc.sendspin.session.ServerHandshake;
import de.yaacc.sendspin.session.WsConnection;
import de.yaacc.util.YaaccLogger;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.Collections;

/**
 * The server's side of one connection to one Sendspin player, from the connect to the end: handshake, {@code server/hello}, the
 * player's {@code client/hello}, the decision whether the player may be given the {@code 'playback'} activity, the
 * {@code server/activate}, the answers to {@code client/time}, the player's state, and the commands and the audio that the app sends.
 * <p>
 * The rules it keeps (spec, "Unpaired Access", "Pairing", {@code server/activate}): a player that is neither paired nor approved by
 * the operator is activated with no activities and no roles and waits for {@link #approve()}; a player that does not admit unpaired
 * access, or that could not use the PSK that was referenced, is shown as such and not activated; the server declares
 * {@code 'playback'} only for a player it can stream to (it needs PCM, 16 bit, stereo in the player's list).
 * <p>
 * One thread runs the connection ({@link #start()}); the app calls the other methods from its own threads. The connection does not
 * reconnect: it ends, and says why ({@link #endReason()}); the directory decides what to do (the guidance of {@code client/goodbye}).
 */
public final class HubConnection implements PcmStreamer.Output, PcmStreamer.Timing {

    /** Where the connection is; the rows of the app are made from it. */
    public enum Phase {
        CONNECTING, HANDSHAKING, WAITING_FOR_HELLO,
        /** Admitted unpaired access is on, but the operator has not approved this player: nothing is played to it. */
        NEEDS_APPROVAL,
        /** The player does not admit unpaired access and no pairing is known. */
        PAIRING_REQUIRED,
        /** The player lost the record of this server (it answered with the Sentinel): pairing has to be done again. */
        RE_PAIR_NEEDED,
        /** The player has no {@code player@v1} role, or lists no PCM 16 bit stereo format (FLAC and Opus need an encoder). */
        UNUSABLE,
        /** A pairing with the Pairing PSK is running (a few messages; then the connection goes on with the new long-term PSK). */
        PAIRING,
        /** A pairing did not complete; {@link #pairingFailure()} says why. The connection is idle. */
        PAIRING_FAILED,
        /** The player role is active and the player is available, but nothing plays: no {@code 'playback'} activity is declared. */
        AVAILABLE,
        ACTIVATING, READY, ENDED
    }

    /** Why a connection ended, as far as the server knows. */
    public enum EndReason {
        NONE, CLOSED_BY_APP, FAILED, PROTOCOL_ERROR,
        ANOTHER_SERVER, SHUTDOWN, RESTART, USER_REQUEST, UNAUTHORIZED, PAIRING_REQUIRED, CONCURRENT_ATTEMPT, UNPAIRED, OTHER
    }

    /** Why a pairing did not complete. */
    public enum PairingFailure {
        NONE,
        /** The player does not know the Pairing PSK of the pasted token (a wrong token, or the player's was replaced). */
        TOKEN_REJECTED,
        /** The player does not offer the Pairing PSK method (every compliant player does). */
        NOT_OFFERED,
        PLAYER_ABORTED,
        /** The PSK the player delivered cannot be kept (the Sentinel, the pairing PSK itself) or there is no room. */
        CANNOT_STORE,
        TIMEOUT
    }

    private enum PairingStep { IDLE, ACTIVATED, INITIATED }

    public interface Listener {
        /** Something changed (phase, player state, name); called on the connection's thread or the caller's, never with a lock held. */
        void onChanged(HubConnection connection);
    }

    /** The format of the stream to this player. */
    public static final class StreamFormat {
        public final int sampleRate;

        StreamFormat(int sampleRate) {
            this.sampleRate = sampleRate;
        }
    }

    private static final long IDLE_LIMIT_MILLIS = 45_000L;
    /** Added to what the player asked for: network and scheduling jitter on the way. */
    private static final long LEAD_MARGIN_MICROS = 250_000L;
    private static final String GROUP_ID = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // a group of one; ids of real groups come with linking

    private final Endpoint endpoint;
    private final Identity identity;
    private final ServerPolicy policy;
    private final Connector connector;
    private final HubClock clock;
    private final SecureRandom random;
    private final String serverName;
    private final Listener listener;
    private final java.util.function.BooleanSupplier legacyAllowed;
    private final Object lock = new Object();

    private volatile Phase phase = Phase.CONNECTING;
    private volatile EndReason endReason = EndReason.NONE;
    private volatile String clientId;
    private volatile SendspinMessages.ClientHello hello;
    private volatile SendspinMessages.ClientState state;
    private volatile ServerHandshake.Result handshake;
    /** The category the connection's keys are under now: from the handshake, and after a re-handshake the new one. */
    private volatile SendspinPsk.Category matched;
    private volatile PairingFailure pairingFailure = PairingFailure.NONE;
    /** The loop thread's: where the pairing is, how many pairing activations came since the last handshake, when it must be done. */
    private PairingStep pairingStep = PairingStep.IDLE;
    private int pairingActivations;
    private boolean discardPairing;
    private long pairingDeadlineNanos;
    private volatile long pairingTimeoutMillis = 30_000L;
    private volatile SecureChannel channel;
    private volatile Connector.Link link;
    private volatile boolean closing;
    /** True for a client of the older, unencrypted wire: no activation messages, the roles are in the {@code server/hello}. */
    private volatile boolean legacy;
    private volatile StreamFormat format;
    private PcmStreamer streamer;
    private Thread thread;
    /** The loop's and the callers': the player role is activated (first activation sent). */
    private boolean rolesActive;
    /** A YAACC player plays to this player: the {@code 'playback'} activity is declared (spec: only while it plays). */
    private boolean playbackActive;
    /** A YAACC player wants to play before the first activation: that activation then declares {@code 'playback'} at once. */
    private boolean playbackWanted;

    public HubConnection(Endpoint endpoint, Identity identity, ServerPolicy policy, Connector connector, HubClock clock,
                         SecureRandom random, String serverName, Listener listener) {
        this(endpoint, identity, policy, connector, clock, random, serverName, listener, () -> false);
    }

    /**
     * @param legacyAllowed whether a client of the older, unencrypted wire is accepted ({@code client/hello} as the first message);
     *                      read at each connection: the user's switch, off by default
     */
    public HubConnection(Endpoint endpoint, Identity identity, ServerPolicy policy, Connector connector, HubClock clock,
                         SecureRandom random, String serverName, Listener listener, java.util.function.BooleanSupplier legacyAllowed) {
        this.legacyAllowed = legacyAllowed;
        this.endpoint = endpoint;
        this.identity = identity;
        this.policy = policy;
        this.connector = connector;
        this.clock = clock;
        this.random = random;
        this.serverName = serverName;
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

    /** The player's id once the handshake is done, else null. */
    public String clientId() {
        return clientId;
    }

    public SendspinMessages.ClientHello hello() {
        return hello;
    }

    /** The last {@code client/state}, or null before the first one. */
    public SendspinMessages.ClientState state() {
        return state;
    }

    public boolean isReady() {
        return phase == Phase.READY;
    }

    /** True if this player speaks the older, unencrypted wire (allowed by the user's switch). */
    public boolean isLegacy() {
        return legacy;
    }

    /** True if the server could not use a PSK the player had referenced earlier (spec: credential mismatch). */
    public boolean credentialMismatch() {
        ServerHandshake.Result result = handshake;
        return result != null && result.credentialMismatch;
    }

    public boolean isPaired() {
        return matched == SendspinPsk.Category.LONG_TERM;
    }

    /** Why the last pairing did not complete, else {@link PairingFailure#NONE}. */
    public PairingFailure pairingFailure() {
        return pairingFailure;
    }

    /** The time the server waits for the player to answer a pairing activation (30 s); tests make it short. */
    void setPairingTimeoutMillis(long millis) {
        pairingTimeoutMillis = millis;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Starts the connection on its own thread. */
    public void start() {
        synchronized (lock) {
            if (thread != null) {
                throw new IllegalStateException("started");
            }
            thread = new Thread(this::run, "sendspin-hub");
            thread.setDaemon(true);
            thread.start();
        }
    }

    /** Ends the connection: stops a running stream, tells the player and closes. Safe to call more than once. */
    public void close() {
        closing = true;
        PcmStreamer current;
        synchronized (lock) {
            current = streamer;
        }
        if (current != null) {
            current.end();
        }
        SecureChannel c = channel;
        if (c != null) {
            c.close(WsConnection.CLOSE_NORMAL);
        }
        closeLink();
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

    // ------------------------------------------------------------------ the operator

    /**
     * The operator approves this player (choosing it in "Play on" is the approval the specification allows). Remembered by the
     * policy; if the player waits, it is activated now.
     */
    public void approve() {
        String id = clientId;
        if (id == null) {
            return;
        }
        policy.approve(id);
        boolean activate;
        synchronized (lock) {
            activate = phase == Phase.NEEDS_APPROVAL;
        }
        if (activate) {
            try {
                activateIdle();
            } catch (IOException e) {
                fail(EndReason.FAILED);
            }
        }
    }

    /**
     * A YAACC player starts to play to this player: declares the {@code 'playback'} activity (and keeps the role). The specification
     * asks a server to declare it only while it plays, so that other servers can take the player at other times; the connection
     * idles with empty activities until this is called. Another server that plays to the player can displace this connection
     * (then the connection ends with {@code ANOTHER_SERVER}).
     *
     * @return false if the connection is not ready for it (not approved or paired, no role, ended)
     */
    public boolean startPlayback() {
        SecureChannel c = channel;
        synchronized (lock) {
            if (phase == Phase.ENDED) {
                return false;
            }
            if (!rolesActive) {
                // not activated yet: the first activation declares 'playback' itself. An idle first activation would tie with another
                // server's idle connection and lose (spec, "Multiple servers"); one that declares 'playback' wins against it.
                if (phase == Phase.CONNECTING || phase == Phase.HANDSHAKING || phase == Phase.WAITING_FOR_HELLO
                        || phase == Phase.NEEDS_APPROVAL) {
                    playbackWanted = true;
                    return true;
                }
                return false;
            }
            if (c == null) {
                return false;
            }
            if (playbackActive) {
                return true;
            }
            playbackActive = true;
        }
        try {
            if (!legacy) {
                c.sendJson(SendspinMessages.serverActivate(Collections.singletonList("playback"), Collections.singletonList("player@v1")));
            }
            c.sendJson(SendspinMessages.groupUpdate(false, GROUP_ID, serverName));
        } catch (IOException e) {
            fail(EndReason.FAILED);
            return false;
        }
        SendspinMessages.ClientState s = state;
        if (s != null && s.available && s.player != null) {
            setPhase(Phase.READY);
        } else {
            setPhase(Phase.ACTIVATING);
        }
        return true;
    }

    /** The YAACC player stopped: ends the stream and drops the {@code 'playback'} activity; the connection stays as available. */
    public void stopPlayback() {
        PcmStreamer current;
        SecureChannel c = channel;
        synchronized (lock) {
            if (!playbackActive) {
                return;
            }
            playbackActive = false;
            playbackWanted = false;
            current = streamer;
        }
        if (current != null) {
            current.end();
        }
        if (c == null || phase == Phase.ENDED) {
            return;
        }
        try {
            if (!legacy) {
                c.sendJson(SendspinMessages.serverActivate(Collections.<String>emptyList(), Collections.singletonList("player@v1")));
            }
        } catch (IOException e) {
            return; // the connection is gone; the loop ends it
        }
        SendspinMessages.ClientState s = state;
        if (s != null && s.available && s.player != null) {
            setPhase(Phase.AVAILABLE);
        }
    }

    // ------------------------------------------------------------------ the stream and the commands

    /** The first PCM entry with 16 bit and two channels in the player's own order, or null. */
    private static SendspinMessages.Format choosePcm(SendspinMessages.ClientHello hello) {
        for (SendspinMessages.Format f : hello.playerFormats) {
            if ("pcm".equals(f.codec) && f.bitDepth == 16 && f.channels == 2) {
                return f;
            }
        }
        return null;
    }

    /**
     * The format of the stream to this player (PCM, 16 bit, stereo, the first such entry of the player's list) from the moment its
     * hello is read, else null. The audio tap of the app needs the rate before the player is ready.
     */
    public StreamFormat streamFormat() {
        return format;
    }

    /**
     * The streamer that sends audio to this player, or null if the player is not ready. One per connection: the same object every
     * time.
     */
    public PcmStreamer streamer() {
        synchronized (lock) {
            if (phase != Phase.READY || format == null) {
                return null;
            }
            if (streamer == null) {
                streamer = new PcmStreamer(this, clock, this, format.sampleRate);
            }
            return streamer;
        }
    }

    /** @return false if the player does not offer the command or the connection is not ready */
    public boolean setVolume(int volume) {
        return command("volume", SendspinMessages.commandVolume(Math.max(0, Math.min(100, volume))));
    }

    public boolean setMuted(boolean muted) {
        return command("mute", SendspinMessages.commandMute(muted));
    }

    private boolean command(String name, String json) {
        SendspinMessages.ClientState s = state;
        SecureChannel c = channel;
        if ((phase != Phase.READY && phase != Phase.AVAILABLE) || s == null || s.player == null || !s.player.supportedCommands.contains(name)
                || c == null) {
            return false;
        }
        try {
            c.sendJson(json);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // PcmStreamer.Output ----------------------------------------------------

    @Override
    public void streamStart(long serverTransmittedMicros, int sampleRate) throws IOException {
        channel.sendJson(SendspinMessages.streamStartPcm(serverTransmittedMicros, sampleRate, 2, 16));
    }

    @Override
    public void streamClear(long serverTransmittedMicros) throws IOException {
        channel.sendJson(SendspinMessages.streamClear(serverTransmittedMicros, null));
    }

    @Override
    public void streamEnd() throws IOException {
        channel.sendJson(SendspinMessages.streamEnd());
    }

    @Override
    public void audio(long timestampMicros, long sendAheadMicros, byte[] pcm, int offset, int length) throws IOException {
        channel.sendMessage(SendspinMessages.AUDIO_CHUNK_ID, legacy
                ? SendspinMessages.audioChunkLegacy(timestampMicros, pcm, offset, length)
                : SendspinMessages.audioChunk(timestampMicros, sendAheadMicros, pcm, offset, length));
    }

    // PcmStreamer.Timing ----------------------------------------------------

    @Override
    public long leadMicros() {
        SendspinMessages.ClientState s = state;
        long asked = 0;
        long delay = 0;
        if (s != null && s.player != null) {
            asked = Math.max(s.player.minBufferMs, s.player.requiredLeadTimeMs) * 1000L;
            delay = s.player.outputDelayMs * 1000L;
        }
        return asked + delay + LEAD_MARGIN_MICROS;
    }

    @Override
    public long outputDelayMicros() {
        SendspinMessages.ClientState s = state;
        return s == null || s.player == null ? 0 : s.player.outputDelayMs * 1000L;
    }

    @Override
    public long capacityBytes() {
        SendspinMessages.ClientHello h = hello;
        return h == null ? 0 : h.playerBufferCapacity;
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
            ServerHandshake.Result result = ServerHandshake.perform(link.ws(), identity, policy::pskFor, random, legacyAllowed.getAsBoolean());
            handshake = result;
            matched = result.verifiedAs;
            channel = result.channel;
            clientId = result.clientId;
            if (result.legacy) {
                legacy = true;
                setPhase(Phase.WAITING_FOR_HELLO);
                handleHello(SendspinMessages.parseLegacyClientHello(result.legacyHello)); // the hello that opened the session
            } else {
                channel.sendJson(SendspinMessages.serverHello(serverName));
                setPhase(Phase.WAITING_FOR_HELLO);
            }
            loop(result.channel);
        } catch (SocketTimeoutException e) {
            fail(EndReason.FAILED);
        } catch (WsConnection.ClosedException e) {
            if (endReason == EndReason.NONE) {
                end(closing ? EndReason.CLOSED_BY_APP : EndReason.OTHER);
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
            PcmStreamer current;
            synchronized (lock) {
                current = streamer;
            }
            if (current != null) {
                current.end();
            }
            closeLink();
            if (phase != Phase.ENDED) {
                end(endReason == EndReason.NONE ? EndReason.FAILED : endReason);
            }
        }
    }

    private void loop(SecureChannel c) throws IOException, ProtocolException, NoiseException {
        long lastRx = System.nanoTime();
        while (!closing) {
            SecureChannel.Inbound inbound;
            try {
                inbound = c.receive();
                lastRx = System.nanoTime();
            } catch (SocketTimeoutException idle) {
                if (pairingStep != PairingStep.IDLE && System.nanoTime() - pairingDeadlineNanos > 0) {
                    cancelPairing(PairingFailure.TIMEOUT);
                }
                if ((System.nanoTime() - lastRx) / 1_000_000L > IDLE_LIMIT_MILLIS) {
                    throw new IOException("no traffic");
                }
                continue;
            }
            if (!inbound.isJson()) {
                continue; // the player sends no binary data in the role it has; ignored as the specification asks
            }
            Envelope envelope = inbound.envelope();
            switch (envelope.type()) {
                case "client/hello":
                    if (hello != null) {
                        throw new ProtocolException("second client/hello");
                    }
                    handleHello(SendspinMessages.parseClientHello(envelope.payload()));
                    break;
                case "client/time": {
                    long received = clock.nowMicros();
                    long clientTime = SendspinMessages.parseClientTime(envelope.payload());
                    c.sendJson(SendspinMessages.serverTime(clientTime, received, clock.nowMicros()));
                    break;
                }
                case "client/state":
                    handleState(legacy ? SendspinMessages.parseLegacyClientState(envelope.payload(), hello.playerCommands)
                            : SendspinMessages.parseClientState(envelope.payload()));
                    break;
                case "client/pair-init":
                    handlePairInit(SendspinMessages.parsePairingIndex(envelope.payload()));
                    break;
                case "client/pair-finalize":
                    handlePairFinalize(SendspinMessages.parseLongTermPsk(envelope.payload()));
                    break;
                case "pair/abort":
                    SendspinMessages.parsePairAbortReason(envelope.payload());
                    if (pairingStep != PairingStep.IDLE) {
                        cancelPairing(PairingFailure.PLAYER_ABORTED);
                    }
                    break;
                case "client/goodbye":
                    end(reasonOf(SendspinMessages.parseGoodbyeReason(envelope.payload())));
                    return;
                default:
                    break; // unknown types are ignored
            }
        }
    }

    private void handleHello(SendspinMessages.ClientHello h) throws IOException {
        hello = h;
        SendspinMessages.Format usable = choosePcm(h);
        if (usable != null && h.supportsPlayer()) {
            format = new StreamFormat(usable.sampleRate);
        }
        decide();
    }

    /** What the player may be given, from what the handshake showed and the hello said; runs again after a pairing. */
    private void decide() throws IOException {
        if (legacy) {
            decideLegacy();
            return;
        }
        SendspinMessages.ClientHello h = hello;
        ServerHandshake.Result result = handshake;
        if (!h.supportsPlayer() || choosePcm(h) == null) {
            activateNothing();
            setPhase(Phase.UNUSABLE);
            return;
        }
        if (result.credentialMismatch) {
            activateNothing();
            if (result.referenced == SendspinPsk.Category.PAIRING) {
                pairingFailure = PairingFailure.TOKEN_REJECTED;
                setPhase(Phase.PAIRING_FAILED);
            } else {
                setPhase(Phase.RE_PAIR_NEEDED);
            }
            return;
        }
        if (matched == SendspinPsk.Category.PAIRING) {
            startPairing();
            return;
        }
        boolean paired = matched == SendspinPsk.Category.LONG_TERM;
        if (!paired && !h.unpairedAccess) {
            activateNothing();
            setPhase(Phase.PAIRING_REQUIRED);
            return;
        }
        if (!paired && !policy.isApproved(result.clientId)) {
            activateNothing();
            setPhase(Phase.NEEDS_APPROVAL);
            return;
        }
        activateIdle();
    }

    /**
     * The older wire has no activation: the roles are named once, in the {@code server/hello}, so a player that the operator has not
     * approved gets nothing at all (not even a hello) until it is approved. It is never paired.
     */
    private void decideLegacy() throws IOException {
        SendspinMessages.ClientHello h = hello;
        if (!h.supportsPlayer() || choosePcm(h) == null) {
            sendLegacyHello(Collections.<String>emptyList());
            setPhase(Phase.UNUSABLE);
            return;
        }
        if (!policy.isApproved(clientId)) {
            setPhase(Phase.NEEDS_APPROVAL);
            return;
        }
        activateIdle();
    }

    private void sendLegacyHello(java.util.List<String> roles) throws IOException {
        String reason;
        synchronized (lock) {
            reason = playbackWanted ? "playback" : "discovery";
        }
        channel.sendJson(SendspinMessages.legacyServerHello(identity.id(), serverName, roles, reason));
    }

    // ------------------------------------------------------------------ pairing with the Pairing PSK

    private void startPairing() throws IOException {
        if (!hello.pairMethods.contains("pairing_psk")) {
            activateNothing();
            pairingFailure = PairingFailure.NOT_OFFERED;
            setPhase(Phase.PAIRING_FAILED);
            return;
        }
        pairingFailure = PairingFailure.NONE;
        pairingActivations++;
        pairingStep = PairingStep.ACTIVATED;
        discardPairing = false;
        pairingDeadlineNanos = System.nanoTime() + pairingTimeoutMillis * 1_000_000L;
        channel.sendJson(SendspinMessages.serverActivatePairing());
        setPhase(Phase.PAIRING);
    }

    private void handlePairInit(int index) throws ProtocolException {
        if (pairingStep == PairingStep.IDLE) {
            if (discardPairing) {
                return; // in flight from before the player saw that the attempt ended
            }
            throw new ProtocolException("client/pair-init without a pairing activation");
        }
        if (index < pairingActivations) {
            return; // a leftover of a superseded pairing
        }
        if (index > pairingActivations || pairingStep != PairingStep.ACTIVATED) {
            throw new ProtocolException("pairing_index");
        }
        pairingStep = PairingStep.INITIATED;
    }

    private void handlePairFinalize(String encodedPsk) throws IOException, ProtocolException {
        if (pairingStep == PairingStep.IDLE && discardPairing) {
            return;
        }
        if (pairingStep != PairingStep.INITIATED) {
            throw new ProtocolException("client/pair-finalize out of sequence");
        }
        byte[] psk = de.yaacc.sendspin.secure.Base64Url.decode32(encodedPsk);
        if (psk == null) {
            throw new ProtocolException("long_term_psk");
        }
        try {
            String id = clientId;
            if (!policy.completePairing(id, psk)) {
                cancelPairing(PairingFailure.CANNOT_STORE);
                return;
            }
            pairingStep = PairingStep.IDLE;
            pairingActivations = 0;
            channel.sendJson(SendspinMessages.serverPairFinalize());
            ServerHandshake.PskChoice choice = policy.pskFor(id);
            if (choice.category() != SendspinPsk.Category.LONG_TERM) {
                throw new IOException("no long-term PSK after the pairing"); // a token pasted meanwhile must not start a second pairing here
            }
            ServerHandshake.rehandshake(channel, identity, id, handshake.suite, choice, random);
            matched = choice.category();
            decide();
        } finally {
            java.util.Arrays.fill(psk, (byte) 0);
        }
    }

    /** Ends the attempt without a pairing: a {@code server/activate} with nothing, as the specification has it. */
    private void cancelPairing(PairingFailure why) throws IOException {
        pairingStep = PairingStep.IDLE;
        discardPairing = true;
        pairingFailure = why;
        activateNothing();
        setPhase(Phase.PAIRING_FAILED);
    }

    private void activateNothing() throws IOException {
        channel.sendJson(SendspinMessages.serverActivate(Collections.<String>emptyList(), Collections.<String>emptyList()));
    }

    /**
     * The first activation of a usable player that may be used (paired or approved): the player role without any activity, so that
     * its state and volume are known and another server is not displaced (spec, "Servers SHOULD declare the minimal set of
     * activities"). The {@code 'playback'} activity follows in {@link #startPlayback()}.
     */
    private void activateIdle() throws IOException {
        boolean withPlayback;
        synchronized (lock) {
            if (rolesActive || hello == null || choosePcm(hello) == null) {
                return;
            }
            rolesActive = true;
            withPlayback = playbackWanted;
            playbackActive = withPlayback;
        }
        setPhase(Phase.ACTIVATING);
        if (legacy) {
            sendLegacyHello(Collections.singletonList("player@v1")); // there is no server/activate on this wire
        } else {
            channel.sendJson(SendspinMessages.serverActivate(
                    withPlayback ? Collections.singletonList("playback") : Collections.<String>emptyList(),
                    Collections.singletonList("player@v1")));
        }
        if (withPlayback) {
            channel.sendJson(SendspinMessages.groupUpdate(false, GROUP_ID, serverName));
        }
        // the player's own state (the specification forbids role data before it) moves the phase on
        SendspinMessages.ClientState s = state;
        if (s != null && s.available && s.player != null) {
            setPhase(withPlayback ? Phase.READY : Phase.AVAILABLE);
        }
    }

    private void handleState(SendspinMessages.ClientState s) {
        state = s;
        boolean becameReady;
        boolean playing;
        synchronized (lock) {
            becameReady = rolesActive && phase == Phase.ACTIVATING && s.available && s.player != null;
            playing = playbackActive;
        }
        if (becameReady) {
            setPhase(playing ? Phase.READY : Phase.AVAILABLE);
        } else {
            notifyChanged();
        }
    }

    private static EndReason reasonOf(String reason) {
        switch (reason) {
            case "another_server":
                return EndReason.ANOTHER_SERVER;
            case "shutdown":
                return EndReason.SHUTDOWN;
            case "restart":
                return EndReason.RESTART;
            case "user_request":
                return EndReason.USER_REQUEST;
            case "unauthorized":
                return EndReason.UNAUTHORIZED;
            case "pairing_required":
                return EndReason.PAIRING_REQUIRED;
            case "concurrent_attempt":
                return EndReason.CONCURRENT_ATTEMPT;
            case "unpaired":
                return EndReason.UNPAIRED;
            default:
                return EndReason.OTHER;
        }
    }

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
