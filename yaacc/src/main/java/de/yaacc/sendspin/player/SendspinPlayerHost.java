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
package de.yaacc.sendspin.player;

import de.yaacc.multiroom.protocol.MicroClock;
import de.yaacc.multiroom.protocol.OffsetSource;
import de.yaacc.sendspin.SendspinClientPairings;
import de.yaacc.sendspin.hub.Connector;
import de.yaacc.sendspin.hub.HubClock;
import de.yaacc.sendspin.session.PlayerMessages;
import de.yaacc.sendspin.client.PlayerSession;
import de.yaacc.sendspin.secure.Identity;
import de.yaacc.util.YaaccLogger;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * This phone as a Sendspin player: it takes the connections of servers that dial the phone, one {@link PlayerSession} each, and lets
 * <b>one</b> server at a time play (the one whose stream started last; the server that is displaced gets {@code client/goodbye}
 * {@code another_server}, as the multi-server rules ask). The sound goes through one {@link SendspinAudioEngine} that exists while a
 * stream plays. At most {@link #MAX_SESSIONS} sessions at once. The Android parts (the output, the decoders, the threads, the
 * service, the lock) are passed in. Thread safe: the sessions call on their own threads.
 */
public final class SendspinPlayerHost {

    public static final int MAX_SESSIONS = 3;
    /** Offers 48000 and 44100 Hz first (the formats the group and the Kodi add-on use); PCM 16 bit stereo only. */
    static final List<PlayerMessages.Offer> PCM = java.util.Collections.unmodifiableList(Arrays.asList(
            new PlayerMessages.Offer("pcm", 48000, 2, 16), new PlayerMessages.Offer("pcm", 44100, 2, 16)));
    static final List<PlayerMessages.Offer> FLAC = java.util.Collections.unmodifiableList(Arrays.asList(
            new PlayerMessages.Offer("flac", 48000, 2, 16), new PlayerMessages.Offer("flac", 44100, 2, 16)));

    /** PCM first, FLAC after it; with FLAC preferred, FLAC first (the server takes the first format it can send). */
    static List<PlayerMessages.Offer> offers(boolean preferFlac) {
        List<PlayerMessages.Offer> all = new ArrayList<>();
        all.addAll(preferFlac ? FLAC : PCM);
        all.addAll(preferFlac ? PCM : FLAC);
        return all;
    }
    static final long BUFFER_CAPACITY = 2_000_000L;
    static final int REQUIRED_LEAD_MS = 500;
    static final int MIN_BUFFER_MS = 1000;

    /** An engine that plays one stream; closed when the stream ends. */
    public interface Engine {
        SendspinAudioEngine.Target target();

        void close();
    }

    /** Makes the engine for a stream; the offsets are those of the session that plays. */
    public interface EngineFactory {
        Engine open(OffsetSource offsets, MicroClock clock);
    }

    /** What the player keeps between runs and what the user sees. */
    public interface Store {
        int volume();

        boolean muted();

        int outputDelayMs();

        void save(int volume, boolean muted, int outputDelayMs);

        /** The {@code server_id} of the server that last held an admitted connection with the playback activity, or null. */
        String lastPlaybackServer();

        void setLastPlaybackServer(String serverId);
    }

    public interface Listener {
        /** The state changed (a server came or went, a stream started or ended); never with a lock held. */
        void changed();
    }

    private final class Entry {
        PlayerSession session;
        boolean closed;
        /** Admitted: the first activation was arbitrated and won. */
        boolean admitted;
        final java.util.Set<String> activities = new java.util.HashSet<>();
    }

    private final Identity identity;
    private final SendspinClientPairings credentials;
    private final BooleanSupplier unpairedAllowed;
    private final BooleanSupplier preferFlac;
    private final String name;
    private final EngineFactory engines;
    private final MicroClock micro;
    private final HubClock clock;
    private final SecureRandom random;
    private final Store store;
    private final Listener listener;
    private final PlayerArbiter arbiter = new PlayerArbiter(MAX_SESSIONS);
    private final Object lock = new Object();
    private final List<Entry> entries = new ArrayList<>();

    private volatile ClientCodePairing codePairing;
    private Entry owner;
    private Engine engine;
    private SendspinAudioEngine audio;
    private volatile int volume;
    private volatile boolean muted;
    private volatile int outputDelayMs;
    private volatile boolean closed;

    public SendspinPlayerHost(Identity identity, SendspinClientPairings credentials, BooleanSupplier unpairedAllowed, String name,
                              EngineFactory engines, MicroClock micro, SecureRandom random, Store store, Listener listener) {
        this(identity, credentials, unpairedAllowed, () -> false, name, engines, micro, random, store, listener);
    }

    /** @param preferFlac read for every new connection: true offers FLAC before PCM */
    public SendspinPlayerHost(Identity identity, SendspinClientPairings credentials, BooleanSupplier unpairedAllowed, BooleanSupplier preferFlac,
                              String name, EngineFactory engines, MicroClock micro, SecureRandom random, Store store, Listener listener) {
        this.preferFlac = preferFlac;
        this.identity = identity;
        this.credentials = credentials;
        this.unpairedAllowed = unpairedAllowed;
        this.name = name;
        this.engines = engines;
        this.micro = micro;
        this.random = random;
        this.store = store;
        this.listener = listener;
        this.volume = store.volume();
        this.muted = store.muted();
        this.outputDelayMs = store.outputDelayMs();
        this.clock = new HubClock() {
            @Override
            public long nowMicros() {
                return micro.nowMicros();
            }

            @Override
            public void sleepMicros(long micros) throws InterruptedException {
                HubClock.SYSTEM.sleepMicros(micros);
            }
        };
    }

    private final PlayerSession.Controls controls = new PlayerSession.Controls() {
        @Override
        public int volume() {
            return volume;
        }

        @Override
        public boolean muted() {
            return muted;
        }

        @Override
        public int outputDelayMs() {
            return outputDelayMs;
        }

        @Override
        public void setVolume(int v) {
            volume = Math.max(0, Math.min(100, v));
            applyControls();
        }

        @Override
        public void setMuted(boolean m) {
            muted = m;
            applyControls();
        }

        @Override
        public void setOutputDelayMs(int ms) {
            outputDelayMs = Math.max(0, Math.min(5000, ms));
            applyControls();
        }
    };

    private void applyControls() {
        SendspinAudioEngine current;
        synchronized (lock) {
            current = audio;
        }
        if (current != null) {
            current.setVolume(volume);
            current.setMuted(muted);
            current.setOutputDelayMs(outputDelayMs);
        }
        store.save(volume, muted, outputDelayMs);
        fire();
    }

    private void fire() {
        if (listener != null) {
            listener.changed();
        }
    }

    // ------------------------------------------------------------------ the connections

    /** A server dialled the phone: its connection becomes a session. A connection beyond the limit is closed at once. */
    public void accept(Connector.Link link) {
        accept(link, false);
    }

    /**
     * As {@link #accept(Connector.Link)}; {@code local} is true for a connection over the loopback address, which is this app's own hub
     * playing on its own phone: it needs no pairing, whatever the setting for unpaired servers says (it is not a device on the network).
     */
    public void accept(Connector.Link link, boolean local) {
        final Entry entry = new Entry();
        // the session is there before the entry is listed, so that the positions of sessionInfos() and disconnect() are the same
        entry.session = new PlayerSession(link, identity, local || unpairedAllowed.getAsBoolean(), credentials, clock, random, name, offers(preferFlac.getAsBoolean()),
                BUFFER_CAPACITY, REQUIRED_LEAD_MS, MIN_BUFFER_MS, new Port(entry), controls, session -> onSessionChanged(entry),
                (session, activities) -> arbitrate(entry, activities));
        ClientCodePairing pairing = codePairing;
        if (pairing != null) {
            entry.session.setCodePairing(pairing.offer(), pairing.window(), pairing);
        }
        synchronized (lock) {
            if (closed || entries.size() >= MAX_SESSIONS) {
                closeQuietly(link);
                return;
            }
            entries.add(entry);
        }
        entry.session.start();
        fire();
    }

    /** Pairing with a code (task 11.1): the connections that come after this call offer the methods of {@code pairing}. */
    public void setCodePairing(ClientCodePairing pairing) {
        this.codePairing = pairing;
    }

    /** @return the code pairing of this player, or null if it offers none */
    public ClientCodePairing codePairing() {
        return codePairing;
    }

    /**
     * The operator's gesture on this phone: opens the pairing window (5 minutes) and lets the connections that wait for it start their
     * attempt. A server that asks to pair with a code gets the window only through this call.
     */
    public void openPairingWindow() {
        ClientCodePairing pairing = codePairing;
        if (pairing == null) {
            return;
        }
        pairing.window().open();
        List<PlayerSession> sessions = new ArrayList<>();
        synchronized (lock) {
            for (Entry e : entries) {
                if (e.session != null) {
                    sessions.add(e.session);
                }
            }
        }
        for (PlayerSession s : sessions) {
            s.onWindowOpened();
        }
        fire();
    }

    /** The rules of the specification for several servers (see {@link PlayerArbiter}); called by a session at its every activation. */
    private boolean arbitrate(Entry entry, java.util.Set<String> activities) {
        List<PlayerSession> displaced = new ArrayList<>();
        boolean admit;
        synchronized (lock) {
            java.util.Map<PlayerArbiter.Conn, Entry> byConn = new java.util.IdentityHashMap<>();
            List<PlayerArbiter.Conn> admitted = new ArrayList<>();
            for (Entry e : entries) {
                if (e != entry && e.admitted && e.session != null) {
                    PlayerArbiter.Conn c = connOf(e);
                    c.activities.addAll(e.activities);
                    admitted.add(c);
                    byConn.put(c, e);
                }
            }
            PlayerArbiter.Conn mine = connOf(entry);
            PlayerArbiter.Verdict verdict = arbiter.decide(mine, activities, admitted, !entry.admitted, store.lastPlaybackServer());
            admit = verdict.admit;
            if (admit) {
                for (PlayerArbiter.Conn c : verdict.displace) {
                    Entry gone = byConn.get(c);
                    if (gone != null) {
                        gone.admitted = false;
                        gone.activities.clear();
                        displaced.add(gone.session);
                        if (owner == gone) {
                            releaseEngineLocked();
                        }
                    }
                }
                entry.admitted = true;
                entry.activities.clear();
                entry.activities.addAll(activities);
                if (activities.contains(PlayerArbiter.PLAYBACK)) {
                    store.setLastPlaybackServer(entry.session.serverId());
                } else if (owner == entry) {
                    releaseEngineLocked(); // the server dropped the playback activity
                }
            }
        }
        for (PlayerSession s : displaced) {
            s.displace();
        }
        fire();
        return admit;
    }

    private PlayerArbiter.Conn connOf(Entry e) {
        PlayerArbiter.Conn c = new PlayerArbiter.Conn(e.session.serverId(), e.session.isPaired());
        c.pairingInProgress = e.session.isPairing();
        return c;
    }

    private static void closeQuietly(Connector.Link link) {
        try {
            link.close();
        } catch (java.io.IOException ignored) {
            // closed
        }
    }

    private void onSessionChanged(Entry entry) {
        if (entry.session.phase() == PlayerSession.Phase.ENDED) {
            synchronized (lock) {
                entries.remove(entry);
                if (owner == entry) {
                    releaseEngineLocked();
                }
            }
        }
        fire();
    }

    /** The server that plays now, or null. */
    public String playingServerName() {
        synchronized (lock) {
            return owner == null || owner.session == null ? null : owner.session.serverName();
        }
    }

    public int sessionCount() {
        synchronized (lock) {
            return entries.size();
        }
    }

    /** One connected server, for the card of this phone. */
    public static final class SessionInfo {
        public final String serverName;
        public final PlayerSession.Phase phase;
        public final boolean playing;

        SessionInfo(String serverName, PlayerSession.Phase phase, boolean playing) {
            this.serverName = serverName;
            this.phase = phase;
            this.playing = playing;
        }
    }

    public List<SessionInfo> sessionInfos() {
        List<SessionInfo> infos = new ArrayList<>();
        synchronized (lock) {
            for (Entry e : entries) {
                if (e.session != null) {
                    infos.add(new SessionInfo(e.session.serverName(), e.session.phase(), e == owner));
                }
            }
        }
        return infos;
    }

    /** Ends the connection of the server at this position of {@link #sessionInfos()}. */
    public void disconnect(int index) {
        PlayerSession session = null;
        synchronized (lock) {
            if (index >= 0 && index < entries.size()) {
                session = entries.get(index).session;
            }
        }
        if (session != null) {
            session.close();
        }
    }

    /** The user set the volume on the phone: the servers are told. */
    public void setVolumeOnPhone(int percent) {
        controls.setVolume(percent);
        announceToServers();
    }

    public void setMutedOnPhone(boolean mute) {
        controls.setMuted(mute);
        announceToServers();
    }

    private void announceToServers() {
        List<PlayerSession> sessions = new ArrayList<>();
        synchronized (lock) {
            for (Entry e : entries) {
                if (e.session != null) {
                    sessions.add(e.session);
                }
            }
        }
        for (PlayerSession s : sessions) {
            s.announceState();
        }
    }

    /** The phase of every session, for a log line. */
    public List<String> sessionPhases() {
        List<String> phases = new ArrayList<>();
        synchronized (lock) {
            for (Entry e : entries) {
                phases.add(e.session == null ? "?" : e.session.phase() + "/" + e.session.endReason());
            }
        }
        return phases;
    }

    public boolean isPlaying() {
        synchronized (lock) {
            return owner != null;
        }
    }

    public int volume() {
        return volume;
    }

    public boolean muted() {
        return muted;
    }

    /** Ends every session and the stream; the host takes no more connections. */
    public void close() {
        List<Entry> all;
        synchronized (lock) {
            closed = true;
            all = new ArrayList<>(entries);
        }
        for (Entry e : all) {
            if (e.session != null) {
                e.session.close();
            }
        }
        synchronized (lock) {
            releaseEngineLocked();
        }
    }

    // ------------------------------------------------------------------ the stream: one server at a time

    private void releaseEngineLocked() {
        owner = null;
        if (engine != null) {
            Engine old = engine;
            engine = null;
            audio = null;
            try {
                old.close();
            } catch (RuntimeException e) {
                YaaccLogger.d(getClass().getName(), "engine close: " + e.getClass().getSimpleName());
            }
        }
    }

    /** What one session may do with the audio: only the session that plays now. */
    private final class Port implements PlayerSession.Audio {
        private final Entry entry;

        Port(Entry entry) {
            this.entry = entry;
        }

        @Override
        public void start(String codec, int sampleRate, int channels, int bitDepth, byte[] codecHeader) {
            SendspinAudioEngine mine;
            synchronized (lock) {
                if (closed || !entry.admitted || !entry.activities.contains(PlayerArbiter.PLAYBACK)) {
                    return; // a stream without the playback activity is not played (the spec: the activity is what is admitted)
                }
                releaseEngineLocked();
                owner = entry;
                final PlayerSession session = entry.session;
                engine = engines.open(new OffsetSource() {
                    @Override
                    public boolean isSynchronized() {
                        return session.hasClockEstimate();
                    }

                    @Override
                    public long offsetMicros() {
                        return session.serverOffsetMicros();
                    }

                    @Override
                    public int epoch() {
                        return 0;
                    }
                }, micro);
                audio = new SendspinAudioEngine(engine.target());
                audio.setVolume(volume);
                audio.setMuted(muted);
                audio.setOutputDelayMs(outputDelayMs);
                mine = audio;
            }
            try {
                mine.start(codec, sampleRate, channels, bitDepth, codecHeader);
            } catch (IllegalArgumentException badHeader) {
                synchronized (lock) {
                    if (owner == entry) {
                        releaseEngineLocked();
                    }
                }
                throw badHeader; // the session ends with a protocol error
            }
            fire();
        }

        @Override
        public void chunk(long serverTimestampMicros, byte[] pcm) {
            SendspinAudioEngine mine = mineOrNull();
            if (mine != null) {
                mine.chunk(serverTimestampMicros, pcm);
            }
        }

        @Override
        public void clear(Long fromTimestamp) {
            SendspinAudioEngine mine = mineOrNull();
            if (mine != null) {
                mine.clear(fromTimestamp);
            }
        }

        @Override
        public void end() {
            SendspinAudioEngine mine;
            synchronized (lock) {
                if (owner != entry) {
                    return;
                }
                mine = audio;
            }
            if (mine != null) {
                mine.end();
            }
            synchronized (lock) {
                if (owner == entry) {
                    releaseEngineLocked();
                }
            }
            fire();
        }

        private SendspinAudioEngine mineOrNull() {
            synchronized (lock) {
                return owner == entry ? audio : null;
            }
        }
    }
}
