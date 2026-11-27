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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

/** The pairing window of the client (spec, "Pairing Window"): opened by a gesture, five minutes, exhausted after the failures. */
public class PairingWindowTest {

    private final AtomicLong now = new AtomicLong(0);
    private final PairingWindow window = new PairingWindow(now::get);

    @Test
    public void itIsClosedUntilTheGestureAndExpiresAfterFiveMinutes() {
        assertFalse(window.isOpen());
        window.open();
        assertTrue(window.isOpen());
        now.set(PairingWindow.LIFETIME_MILLIS - 1);
        assertTrue(window.isOpen());
        now.set(PairingWindow.LIFETIME_MILLIS);
        assertFalse("silently closed", window.isOpen());
    }

    @Test
    public void aNewGestureStartsALifetimeAgainAndAClosingGestureEndsIt() {
        window.open();
        now.set(PairingWindow.LIFETIME_MILLIS - 10);
        window.open();
        now.set(PairingWindow.LIFETIME_MILLIS + 10);
        assertTrue("the second opening restarted the lifetime", window.isOpen());
        window.close();
        assertFalse(window.isOpen());
    }

    @Test
    public void theFifthFailedStaticAttemptExhaustsItAndItStaysOpenButAdmitsNothing() {
        window.open();
        for (int i = 0; i < 4; i++) {
            window.staticAttemptFailed();
            assertFalse(window.isExhausted());
        }
        window.staticAttemptFailed();
        assertTrue(window.isExhausted());
        assertTrue("it stays open", window.isOpen());
        assertFalse("but admits no further attempt", window.admitsAttempt());
        window.open(); // a new opening gesture
        assertTrue(window.admitsAttempt());
    }

    @Test
    public void aCompletedPairingClosesIt() {
        window.open();
        window.completed();
        assertFalse(window.isOpen());
    }
}
