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
    static final List<int[]> FORMATS = java.util.Collections.unmodifiableList(Arrays.asList(new int[]{48000, 2, 16}, new int[]{44100, 2, 16}));
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
    }

    public interface Listener {
        /** The state changed (a server came or went, a stream started or ended); never with a lock held. */
        void changed();
    }

    private final class Entry {
        PlayerSession session;
        boolean closed;
    }

    private final Identity identity;
    private final SendspinClientPairings credentials;
    private final BooleanSupplier unpairedAllowed;
    private final String name;
    private final EngineFactory engines;
    private final MicroClock micro;
    private final HubClock clock;
    private final SecureRandom random;
    private final Store store;
    private final Listener listener;
    private final Object lock = new Object();
    private final List<Entry> entries = new ArrayList<>();

    private Entry owner;
    private Engine engine;
    private SendspinAudioEngine audio;
    private volatile int volume;
    private volatile boolean muted;
    private volatile int outputDelayMs;
    private volatile boolean closed;

    public SendspinPlayerHost(Identity identity, SendspinClientPairings credentials, BooleanSupplier unpairedAllowed, String name,
                              EngineFactory engines, MicroClock micro, SecureRandom random, Store store, Listener listener) {
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
        final Entry entry = new Entry();
        synchronized (lock) {
            if (closed || entries.size() >= MAX_SESSIONS) {
                closeQuietly(link);
                return;
            }
            entries.add(entry);
        }
        entry.session = new PlayerSession(link, identity, unpairedAllowed.getAsBoolean(), credentials, clock, random, name, FORMATS,
                BUFFER_CAPACITY, REQUIRED_LEAD_MS, MIN_BUFFER_MS, new Port(entry), controls, session -> onSessionChanged(entry));
        entry.session.start();
        fire();
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
        public void start(int sampleRate, int channels, int bitDepth) {
            Entry displaced = null;
            SendspinAudioEngine mine;
            synchronized (lock) {
                if (closed) {
                    return;
                }
                if (owner != null && owner != entry) {
                    displaced = owner; // the server whose stream started last plays
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
            if (displaced != null && displaced.session != null) {
                displaced.session.displace();
            }
            mine.start(sampleRate, channels, bitDepth);
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
