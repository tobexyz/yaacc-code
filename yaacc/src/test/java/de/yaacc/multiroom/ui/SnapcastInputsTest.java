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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** What the user types in the rename and the latency dialogs. Plain JVM. */
public class SnapcastInputsTest {

    @Test
    public void aLatencyInsideTheRangeIsAccepted() {
        assertEquals(0, SnapcastInputs.parseLatency("0").getValue());
        assertEquals(120, SnapcastInputs.parseLatency("120").getValue());
        assertEquals(10000, SnapcastInputs.parseLatency("10000").getValue());
        assertTrue(SnapcastInputs.parseLatency("0").isValid());
    }

    @Test
    public void whiteSpaceAroundTheNumberIsIgnored() {
        assertEquals(250, SnapcastInputs.parseLatency("  250 ").getValue());
    }

    @Test
    public void aLatencyOutsideTheRangeIsRefused() {
        assertEquals(SnapcastInputs.Problem.OUT_OF_RANGE, SnapcastInputs.parseLatency("10001").getProblem());
        assertEquals(SnapcastInputs.Problem.OUT_OF_RANGE, SnapcastInputs.parseLatency("99999999999999999999").getProblem());
        assertFalse(SnapcastInputs.parseLatency("10001").isValid());
    }

    @Test
    public void textThatIsNoWholeNumberIsRefused() {
        assertEquals(SnapcastInputs.Problem.NOT_A_NUMBER, SnapcastInputs.parseLatency("12ms").getProblem());
        assertEquals(SnapcastInputs.Problem.NOT_A_NUMBER, SnapcastInputs.parseLatency("1.5").getProblem());
        assertEquals(SnapcastInputs.Problem.NOT_A_NUMBER, SnapcastInputs.parseLatency("-5").getProblem());
        assertEquals(SnapcastInputs.Problem.NOT_A_NUMBER, SnapcastInputs.parseLatency("+5").getProblem());
        assertEquals(SnapcastInputs.Problem.NOT_A_NUMBER, SnapcastInputs.parseLatency("٣٤").getProblem());
    }

    @Test
    public void nothingTypedIsRefused() {
        assertEquals(SnapcastInputs.Problem.EMPTY, SnapcastInputs.parseLatency("").getProblem());
        assertEquals(SnapcastInputs.Problem.EMPTY, SnapcastInputs.parseLatency("   ").getProblem());
        assertEquals(SnapcastInputs.Problem.EMPTY, SnapcastInputs.parseLatency(null).getProblem());
    }

    @Test
    public void aNameIsTrimmedAndCleaned() {
        assertEquals("Kitchen", SnapcastInputs.normalizeName("  Kitchen  "));
        assertEquals("a?b", SnapcastInputs.normalizeName("a\nb"));
        assertEquals("Kitchen?", SnapcastInputs.normalizeName("Kitchen\u200B"));
    }

    @Test
    public void anEmptyNameIsAllowedBecauseItResetsTheName() {
        assertEquals("", SnapcastInputs.normalizeName("   "));
        assertEquals("", SnapcastInputs.normalizeName(null));
    }

    @Test
    public void aLongNameIsCut() {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            name.append('x');
        }

        assertEquals(SnapcastInputs.MAX_NAME, SnapcastInputs.normalizeName(name.toString()).length());
    }
}
