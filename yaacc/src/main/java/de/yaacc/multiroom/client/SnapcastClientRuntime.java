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
        NONE, USER, BAD_ADDRESS, UNRESOLVED, CONNECTION_GAVE_UP, SERVER_REFUSED, ENGINE_FAILED, SERVICE_ERROR
    }

    /** An immutable view for the screen. */
    public static final class View {
        public final Phase phase;
        public final Ending ending;
        public final EngineStatus engine;
        public final long startedAtMillis;

        View(Phase phase, Ending ending, EngineStatus engine, long startedAtMillis) {
            this.phase = phase;
            this.ending = ending;
            this.engine = engine;
            this.startedAtMillis = startedAtMillis;
        }
    }

    private static final AtomicReference<View> VIEW = new AtomicReference<>(new View(Phase.IDLE, Ending.NONE, null, 0));

    private SnapcastClientRuntime() {
    }

    public static View get() {
        return VIEW.get();
    }

    public static void phase(Phase phase, Ending ending, long startedAtMillis) {
        // one atomic step: the engine thread publishes at the same time and must not bring an old phase back
        VIEW.updateAndGet(old -> new View(phase, ending, phase == Phase.CONNECTING ? null : old.engine, startedAtMillis));
    }

    /**
     * As {@link #phase} but only while a session is still on, decided in the same atomic step: a late "connected" or
     * "lost" from another thread can not bring a finished session back to life (check-then-act would).
     */
    public static void phaseWhileActive(Phase phase, Ending ending, long startedAtMillis) {
        VIEW.updateAndGet(old -> isActive(old.phase)
                ? new View(phase, ending, phase == Phase.CONNECTING ? null : old.engine, startedAtMillis) : old);
    }

    private static boolean isActive(Phase p) {
        return p == Phase.CONNECTING || p == Phase.RUNNING || p == Phase.RECONNECTING;
    }

    public static void engine(EngineStatus status) {
        VIEW.updateAndGet(old -> new View(old.phase, old.ending, status, old.startedAtMillis));
    }

    /** True while a session is starting or running. */
    public static boolean isActive() {
        return isActive(VIEW.get().phase);
    }
}
