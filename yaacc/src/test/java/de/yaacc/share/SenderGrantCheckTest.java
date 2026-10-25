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
package de.yaacc.share;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** {@link SenderGrantCheck}: the API 35 gate in front of the launcher's forward. Plain JVM. */
public class SenderGrantCheckTest {

    private static final int GRANTED = 0;
    private static final int DENIED = -1;

    @Test
    public void belowAndroid15NothingIsAskedAndTheForwardIsAllowed() {
        boolean[] asked = new boolean[1];

        assertTrue(SenderGrantCheck.mayForward(34, () -> {
            asked[0] = true;
            return DENIED;
        }));
        assertTrue(SenderGrantCheck.mayForward(27, () -> DENIED));
        assertFalse(asked[0]);
    }

    @Test
    public void fromAndroid15OnAGrantedSenderMayForward() {
        assertTrue(SenderGrantCheck.mayForward(35, () -> GRANTED));
        assertTrue(SenderGrantCheck.mayForward(36, () -> GRANTED));
    }

    @Test
    public void fromAndroid15OnADeniedSenderMayNotForward() {
        assertFalse(SenderGrantCheck.mayForward(35, () -> DENIED));
    }

    @Test
    public void aThrowingCheckMeansDoNotForward() {
        assertFalse(SenderGrantCheck.mayForward(35, () -> {
            throw new IllegalArgumentException("not a content uri of the caller");
        }));
        assertFalse(SenderGrantCheck.mayForward(35, () -> {
            throw new SecurityException("no");
        }));
        assertFalse(SenderGrantCheck.mayForward(35, () -> {
            throw new IllegalStateException("anything");
        }));
    }

    @Test
    public void aMissingCheckFromAndroid15OnMeansDoNotForward() {
        assertFalse(SenderGrantCheck.mayForward(35, null));
    }
}
