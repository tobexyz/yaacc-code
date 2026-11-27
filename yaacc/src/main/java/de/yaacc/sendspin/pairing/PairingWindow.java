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
package de.yaacc.sendspin.pairing;

import java.util.function.LongSupplier;

/**
 * The pairing window of a client (spec, "Pairing Window"): a state in which the client has decided to accept code-based pairing
 * attempts. An operator gesture on the client opens it (the app's button in the sheet of the phone's own card: an action no server can
 * drive); it lasts five minutes without pausing during attempts; it closes on a completed pairing, on a closing gesture or when it
 * expires; it is exhausted after its fifth failed static attempt or at the round limit of a dynamic attempt: it then stays open but
 * admits no further attempt until a new opening gesture. Thread safe.
 */
public final class PairingWindow {

    /** Recommended by the specification. */
    public static final long LIFETIME_MILLIS = 5L * 60L * 1000L;
    /** Failed static attempts that exhaust the window. */
    public static final int STATIC_FAILURES = 5;

    private final LongSupplier nowMillis;
    private boolean open;
    private long openedAt;
    private int staticFailures;
    private boolean exhausted;

    public PairingWindow(LongSupplier nowMillis) {
        this.nowMillis = nowMillis;
    }

    /** The opening gesture (also a new one while it is open: the lifetime starts again and the failures are forgotten). */
    public synchronized void open() {
        open = true;
        openedAt = nowMillis.getAsLong();
        staticFailures = 0;
        exhausted = false;
    }

    /** The closing gesture. */
    public synchronized void close() {
        open = false;
    }

    public synchronized boolean isOpen() {
        if (open && nowMillis.getAsLong() - openedAt >= LIFETIME_MILLIS) {
            open = false; // closes silently
        }
        return open;
    }

    public synchronized boolean isExhausted() {
        return isOpen() && exhausted;
    }

    /** Whether a new attempt may start now: the window is open and not exhausted. */
    public synchronized boolean admitsAttempt() {
        return isOpen() && !exhausted;
    }

    /** The client's verification of {@code server_kc} failed in a static attempt. */
    public synchronized void staticAttemptFailed() {
        staticFailures++;
        if (staticFailures >= STATIC_FAILURES) {
            exhausted = true;
        }
    }

    /** A dynamic attempt reached the round limit. */
    public synchronized void roundLimitReached() {
        exhausted = true;
    }

    /** A pairing completed: the window closes. */
    public synchronized void completed() {
        open = false;
    }

    /** How long the window stays open, in milliseconds; 0 if it is closed. */
    public synchronized long remainingMillis() {
        return isOpen() ? LIFETIME_MILLIS - (nowMillis.getAsLong() - openedAt) : 0L;
    }
}
