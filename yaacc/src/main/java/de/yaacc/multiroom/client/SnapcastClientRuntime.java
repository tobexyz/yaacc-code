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
package de.yaacc.multiroom.client;

import java.util.concurrent.atomic.AtomicReference;

/**
 * What the status screen of the spike reads: the latest status of the running session (or of the last one) and a
 * phase text of the service. A process-wide holder, because the screen and the service live apart. Thread safe.
 */
public final class SnapcastClientRuntime {

    /** The phase of the service around the engine. */
    public enum Phase {
        /** No session. */
        IDLE,
        /** Resolving the address, connecting. */
        CONNECTING,
        /** The engine runs (it has its own state). */
        RUNNING,
        /** The connection was lost and is retried. */
        RECONNECTING,
        /** Ended by the user. */
        STOPPED,
        /** Ended by a failure of the connection or of the engine. */
        FAILED
    }

    /** Why a session ended on the connection side (typed, no server text). */
    public enum Ending {
        NONE, USER, BAD_ADDRESS, UNRESOLVED, CONNECTION_GAVE_UP, SERVER_REFUSED, ENGINE_FAILED, SERVICE_ERROR, NO_AUDIO, DISABLED,
        /** The service was destroyed without a stop from the user (the system, or the app ending its services). */
        SYSTEM,
        /** The whole app ended: the exit action or the shutdown (sleep) timer of YAACC. */
        APP_EXIT,
        /**
         * The system refused to start the foreground service from the background (Android 12 and newer). No failure of a session: the
         * start is tried once more when a screen of the app starts. Last in the list: the trace stores the ordinal.
         */
        START_NOT_ALLOWED
    }

    /** An immutable view for the screen. */
    public static final class View {
        public final Phase phase;
        public final Ending ending;
        public final EngineStatus engine;
        public final long startedAtMillis;
        /** What the server says about this client (null before the first answer). */
        public final ServerSays server;

        View(Phase phase, Ending ending, EngineStatus engine, long startedAtMillis) {
            this(phase, ending, engine, startedAtMillis, null);
        }

        View(Phase phase, Ending ending, EngineStatus engine, long startedAtMillis, ServerSays server) {
            this.server = server;
            this.phase = phase;
            this.ending = ending;
            this.engine = engine;
            this.startedAtMillis = startedAtMillis;
        }
    }

    private static final AtomicReference<View> VIEW = new AtomicReference<>(new View(Phase.IDLE, Ending.NONE, null, 0));

    // The client id of the Hello of the session that runs (task 12.8: that client is never removed from a server); empty
    // before the Hello is built and again at the start of the next session. Never logged.
    private static final AtomicReference<String> CLIENT_ID = new AtomicReference<>();

    private static final java.util.concurrent.atomic.AtomicBoolean SERVICE_ALIVE = new java.util.concurrent.atomic.AtomicBoolean();

    private SnapcastClientRuntime() {
    }

    /** The service built the Hello of the session that starts: its client id. */
    public static void clientId(String id) {
        CLIENT_ID.set(id);
    }

    /** The client id of the session that runs or starts; null when it is not known yet, and after the session ended. */
    public static String clientId() {
        return isActive() ? CLIENT_ID.get() : null;
    }

    public static View get() {
        return VIEW.get();
    }

    public static void phase(Phase phase, Ending ending, long startedAtMillis) {
        if (phase == Phase.CONNECTING) {
            CLIENT_ID.set(null); // the id of an earlier session is not this one's
        }
        // one atomic step: the engine thread publishes at the same time and must not bring an old phase back
        VIEW.updateAndGet(old -> new View(phase, ending, phase == Phase.CONNECTING ? null : old.engine, startedAtMillis,
                phase == Phase.CONNECTING ? null : old.server));
    }

    /**
     * As {@link #phase} but only while a session is still on, decided in the same atomic step: a late "connected" or
     * "lost" from another thread can not bring a finished session back to life (check-then-act would).
     */
    public static void phaseWhileActive(Phase phase, Ending ending, long startedAtMillis) {
        VIEW.updateAndGet(old -> isActive(old.phase)
                ? new View(phase, ending, phase == Phase.CONNECTING ? null : old.engine, startedAtMillis,
                        phase == Phase.CONNECTING ? null : old.server) : old);
    }

    private static boolean isActive(Phase p) {
        return p == Phase.CONNECTING || p == Phase.RUNNING || p == Phase.RECONNECTING;
    }

    public static void engine(EngineStatus status) {
        VIEW.updateAndGet(old -> new View(old.phase, old.ending, status, old.startedAtMillis, old.server));
    }

    /** The newest answer of the server about this client. */
    public static void server(ServerSays says) {
        VIEW.updateAndGet(old -> new View(old.phase, old.ending, old.engine, old.startedAtMillis, says));
    }

    /** The service instance was created (its {@code onCreate}). */
    public static void serviceCreated() {
        SERVICE_ALIVE.set(true);
    }

    /** The service instance was destroyed: it has torn its session down (its {@code onDestroy}). */
    public static void serviceDestroyed() {
        SERVICE_ALIVE.set(false);
    }

    /**
     * A service instance exists. It outlives the phase of its session by the teardown (a few seconds at most), and it answers a start
     * with "busy" until it is gone: a restart on another server waits for this too (task 12.10).
     */
    public static boolean isServiceAlive() {
        return SERVICE_ALIVE.get();
    }

    /** True while a session is starting or running. */
    public static boolean isActive() {
        return isActive(VIEW.get().phase);
    }
}
