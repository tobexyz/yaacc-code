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
package de.yaacc.multiroom.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

/** A dead server must not fill the screen with toasts while a slider is dragged: at most one in a while. Plain JVM. */
public class SnapcastToastGateTest {

    private final AtomicLong now = new AtomicLong(10_000);
    private final SnapcastToastGate gate = new SnapcastToastGate(now::get, 3_000);

    @Test
    public void theFirstFailureIsShown() {
        assertTrue(gate.allow());
    }

    @Test
    public void furtherFailuresInsideTheGapAreHeldBack() {
        assertTrue(gate.allow());
        now.addAndGet(1_000);
        assertFalse(gate.allow());
        now.addAndGet(1_999);
        assertFalse(gate.allow());
    }

    @Test
    public void afterTheGapTheNextFailureIsShownAgain() {
        assertTrue(gate.allow());
        now.addAndGet(3_000);
        assertTrue(gate.allow());
        now.addAndGet(10);
        assertFalse(gate.allow());
    }

    @Test
    public void aHeldBackFailureDoesNotExtendTheGap() {
        assertTrue(gate.allow());
        now.addAndGet(2_000);
        assertFalse(gate.allow());
        now.addAndGet(1_000);
        assertTrue(gate.allow());
    }
}
