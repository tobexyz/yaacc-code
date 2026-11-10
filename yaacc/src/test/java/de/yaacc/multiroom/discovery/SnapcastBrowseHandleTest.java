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
package de.yaacc.multiroom.discovery;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * One call of {@code NsdManager.discoverServices} and what to do with a stop: a stop that comes before the platform
 * confirmed the start is held back until it did (some releases answer an early stop with "failed" and leave the
 * browse running).
 */
public class SnapcastBrowseHandleTest {

    @Test
    public void aStopAfterTheStartWentThroughStopsAtOnce() {
        SnapcastBrowseHandle handle = new SnapcastBrowseHandle();
        assertFalse(handle.onStarted());

        assertTrue(handle.requestStop());
    }

    @Test
    public void aStopRightAfterTheStartWaitsForTheConfirmation() {
        SnapcastBrowseHandle handle = new SnapcastBrowseHandle();

        assertFalse("not started yet: the platform would refuse", handle.requestStop());
        assertTrue("now it can be stopped", handle.onStarted());
    }

    @Test
    public void theHeldBackStopIsDoneOnce() {
        SnapcastBrowseHandle handle = new SnapcastBrowseHandle();
        handle.requestStop();

        assertTrue(handle.onStarted());
        assertFalse(handle.onStarted());
        assertFalse(handle.requestStop());
    }

    @Test
    public void aSecondStopDoesNothing() {
        SnapcastBrowseHandle handle = new SnapcastBrowseHandle();
        handle.onStarted();

        assertTrue(handle.requestStop());
        assertFalse(handle.requestStop());
    }

    @Test
    public void aStartThatFailedHasNothingToStop() {
        SnapcastBrowseHandle handle = new SnapcastBrowseHandle();
        handle.onStartFailed();

        assertFalse(handle.requestStop());
        assertFalse(handle.onStarted());
    }

    @Test
    public void aStopHeldBackIsDroppedWhenTheStartFails() {
        SnapcastBrowseHandle handle = new SnapcastBrowseHandle();
        handle.requestStop();

        handle.onStartFailed();

        assertFalse(handle.onStarted());
    }

    @Test
    public void everyCallHasItsOwnHandle() {
        SnapcastBrowseHandle old = new SnapcastBrowseHandle();
        old.requestStop(); // held back, the platform has not confirmed yet
        SnapcastBrowseHandle fresh = new SnapcastBrowseHandle();

        assertTrue("the new browse is not affected by the old stop", !fresh.onStarted());
        assertTrue("the old one is stopped when its own start is confirmed", old.onStarted());
    }
}
