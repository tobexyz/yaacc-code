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
package de.yaacc.browser;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** One power change per device at a time, and not a second one right after the first (task 12.4). Pure. */
public class RendererPowerGateTest {

    @Test
    public void aSecondTapWhileTheFirstRunsIsDropped() {
        RendererPowerGate gate = new RendererPowerGate(1_000);

        assertTrue(gate.tryStart("a", 0));
        assertFalse(gate.tryStart("a", 10));
        assertFalse(gate.tryStart("a", 50_000));
    }

    @Test
    public void anotherDeviceIsNotHeldUp() {
        RendererPowerGate gate = new RendererPowerGate(1_000);

        assertTrue(gate.tryStart("a", 0));
        assertTrue(gate.tryStart("b", 1));
    }

    @Test
    public void aTapRightAfterTheEndIsDroppedAndALaterOneIsAccepted() {
        RendererPowerGate gate = new RendererPowerGate(1_000);
        assertTrue(gate.tryStart("a", 0));
        gate.finished("a", 2_000);

        assertFalse(gate.tryStart("a", 2_500));
        assertTrue(gate.tryStart("a", 3_000));
    }

    @Test
    public void aChangeThatNeverReportsItsEndDoesNotBlockForEver() {
        RendererPowerGate gate = new RendererPowerGate(1_000);
        assertTrue(gate.tryStart("a", 0));

        assertTrue("a change is over after the longest it may take", gate.tryStart("a", RendererPowerGate.MAX_RUN_MILLIS + 1));
    }

    @Test
    public void theMemoryIsBounded() {
        RendererPowerGate gate = new RendererPowerGate(1_000);
        for (int i = 0; i < 1_000; i++) {
            assertTrue(gate.tryStart("device" + i, i));
            gate.finished("device" + i, i);
        }

        assertTrue(gate.size() <= RendererPowerGate.MAX_KEYS);
    }
}
