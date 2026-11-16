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

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Task 12.6 / 12C: whether the phone's own Snapcast client is started again by the application (at the start of the process, which
 * a reboot with "autostart on boot" also is, and as one more try when a screen starts). Pure: the facts come in, the decision goes
 * out.
 *
 * <p>Restore only when ALL hold: the client is switched on, the option is on, a server is set, no session runs, the app is not in
 * the hold-back window after an exit, the last ending is not an unseen failure, and no attempt was made in this process yet. The
 * one exception to "no attempt yet": a start that the system REFUSED (Android 12 and newer limit foreground service starts from the
 * background) is tried once more when a screen starts, and never a third time.
 */
public final class SnapcastClientRestore {

    /** Where the restore is asked for. */
    public enum Point {
        /** The application object was created (the normal start, a boot, a restart by the system). */
        PROCESS_START,
        /** The user started the app (a screen was created fresh). */
        SCREEN_START
    }

    /** Why nothing is started; the order of the constants is the order in which the reasons are checked. */
    public enum Skip {
        NOT_SWITCHED_ON, OPTION_OFF, NO_SERVER, EXIT_HOLD, ALREADY_ACTIVE, UNSEEN_FAILURE, ALREADY_ATTEMPTED
    }

    /**
     * Endings that are a failure of the session: the restore waits until the user has seen them. The others are the user (clears the
     * state itself), the system or the app ending the service (the very reason for a restore), and a start that was not allowed (tried
     * again at the next screen).
     */
    private static final Set<String> FAILURES = new HashSet<>(Arrays.asList(
            SnapcastClientRuntime.Ending.BAD_ADDRESS.name(), SnapcastClientRuntime.Ending.UNRESOLVED.name(),
            SnapcastClientRuntime.Ending.CONNECTION_GAVE_UP.name(), SnapcastClientRuntime.Ending.SERVER_REFUSED.name(),
            SnapcastClientRuntime.Ending.ENGINE_FAILED.name(), SnapcastClientRuntime.Ending.SERVICE_ERROR.name(),
            SnapcastClientRuntime.Ending.NO_AUDIO.name()));

    /** What is known when the question is asked. Fluent, all off by default (and the last ending seen). */
    public static final class Facts {
        private final Point point;
        private boolean switchedOn;
        private boolean optionEnabled;
        private boolean serverSet;
        private boolean active;
        private String lastEnding;
        private boolean endingSeen = true;
        private int attempts;
        private boolean lastAttemptRefused;
        private boolean exitHold;

        public Facts(Point point) {
            this.point = point;
        }

        public Facts switchedOn(boolean value) {
            this.switchedOn = value;
            return this;
        }

        public Facts optionEnabled(boolean value) {
            this.optionEnabled = value;
            return this;
        }

        public Facts serverSet(boolean value) {
            this.serverSet = value;
            return this;
        }

        /** A session is starting or running. */
        public Facts active(boolean value) {
            this.active = value;
            return this;
        }

        /** The name of the last ending as stored; null or unknown for none. */
        public Facts lastEnding(String value) {
            this.lastEnding = value;
            return this;
        }

        public Facts endingSeen(boolean value) {
            this.endingSeen = value;
            return this;
        }

        /** How often the application tried to restore in this process. */
        public Facts attempts(int value) {
            this.attempts = value;
            return this;
        }

        /** The system refused the last attempt (the background start limit). */
        public Facts lastAttemptRefused(boolean value) {
            this.lastAttemptRefused = value;
            return this;
        }

        /** The process was created right after an explicit exit and waits for the user. */
        public Facts exitHold(boolean value) {
            this.exitHold = value;
            return this;
        }
    }

    /** The answer: restore, or skip with the reason. */
    public static final class Decision {
        private static final Decision RESTORE = new Decision(null);
        private final Skip skip;

        private Decision(Skip skip) {
            this.skip = skip;
        }

        public boolean restore() {
            return skip == null;
        }

        /** Why not; null when the answer is to restore. */
        public Skip skip() {
            return skip;
        }
    }

    private SnapcastClientRestore() {
    }

    public static Decision decide(Facts facts) {
        if (!facts.switchedOn) {
            return new Decision(Skip.NOT_SWITCHED_ON);
        }
        if (!facts.optionEnabled) {
            return new Decision(Skip.OPTION_OFF);
        }
        if (!facts.serverSet) {
            return new Decision(Skip.NO_SERVER);
        }
        if (facts.exitHold) {
            return new Decision(Skip.EXIT_HOLD);
        }
        if (facts.active) {
            return new Decision(Skip.ALREADY_ACTIVE);
        }
        if (isFailure(facts.lastEnding) && !facts.endingSeen) {
            return new Decision(Skip.UNSEEN_FAILURE);
        }
        boolean firstAttempt = facts.attempts == 0;
        boolean oneMoreAtAScreen = facts.attempts == 1 && facts.point == Point.SCREEN_START && facts.lastAttemptRefused;
        if (!firstAttempt && !oneMoreAtAScreen) {
            return new Decision(Skip.ALREADY_ATTEMPTED);
        }
        return Decision.RESTORE;
    }

    /** @return whether the stored ending name is a failure of a session; false for null or a name that is not known */
    public static boolean isFailure(String endingName) {
        return endingName != null && FAILURES.contains(endingName);
    }
}
