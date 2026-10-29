/*
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
package de.yaacc.browser;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link RelaunchGuard}: at most one layout relaunch per window, so a stored
 * "new" that cannot be honored never makes the two browsers bounce forever. Plain JVM, fake clock.
 */
public class RelaunchGuardTest {

    private static final long WINDOW = 2000L;

    private long now;
    private RelaunchGuard guard;

    @Before
    public void setUp() {
        now = 10_000L;
        guard = new RelaunchGuard(() -> now, WINDOW);
    }

    @Test
    public void firstCallIsAllowed() {
        assertTrue(guard.tryRelaunch());
    }

    @Test
    public void firstCallIsAllowedEvenWhenClockStartsAtZero() {
        now = 0L;
        assertTrue(guard.tryRelaunch());
    }

    @Test
    public void secondCallWithinWindowIsDenied() {
        assertTrue(guard.tryRelaunch());
        now += 500L;
        assertFalse(guard.tryRelaunch());
    }

    @Test
    public void callWithoutTimePassingIsDenied() {
        assertTrue(guard.tryRelaunch());
        assertFalse(guard.tryRelaunch());
    }

    @Test
    public void callJustBeforeTheBoundaryIsDenied() {
        assertTrue(guard.tryRelaunch());
        now += WINDOW - 1;
        assertFalse(guard.tryRelaunch());
    }

    @Test
    public void callExactlyAtTheBoundaryIsAllowed() {
        assertTrue(guard.tryRelaunch());
        now += WINDOW;
        assertTrue(guard.tryRelaunch());
    }

    @Test
    public void callAfterTheWindowIsAllowed() {
        assertTrue(guard.tryRelaunch());
        now += WINDOW + 1;
        assertTrue(guard.tryRelaunch());
    }

    @Test
    public void deniedCallsDoNotExtendTheWindow() {
        assertTrue(guard.tryRelaunch());
        now += 1500L;
        assertFalse(guard.tryRelaunch());
        now += 500L; // 2000 ms after the allowed call
        assertTrue(guard.tryRelaunch());
    }

    @Test
    public void allowedCallStartsANewWindow() {
        assertTrue(guard.tryRelaunch());
        now += WINDOW;
        assertTrue(guard.tryRelaunch());
        now += WINDOW - 1;
        assertFalse(guard.tryRelaunch());
    }

    @Test
    public void clockGoingBackwardsDoesNotWedgeTheGuard() {
        assertTrue(guard.tryRelaunch());
        now -= 5_000L;
        assertTrue(guard.tryRelaunch());
    }

    @Test
    public void afterClockWentBackwardsTheNewWindowApplies() {
        assertTrue(guard.tryRelaunch());
        now -= 5_000L;
        assertTrue(guard.tryRelaunch());
        now += 100L;
        assertFalse(guard.tryRelaunch());
        now += WINDOW;
        assertTrue(guard.tryRelaunch());
    }

    @Test
    public void sharedInstanceIsProcessWide() {
        assertTrue(RelaunchGuard.shared() == RelaunchGuard.shared());
    }

    @Test
    public void defaultWindowIsAboutTwoSeconds() {
        assertTrue(RelaunchGuard.DEFAULT_WINDOW_MILLIS == 2000L);
    }
}
