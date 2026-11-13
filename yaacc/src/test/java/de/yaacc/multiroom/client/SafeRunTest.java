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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SafeRunTest {

    @Test
    public void aStepThatEndsNormallyDoesNotCallTheHandler() {
        int[] calls = {0, 0};
        assertTrue(SafeRun.run(() -> calls[0]++, () -> calls[1]++));
        assertEquals(1, calls[0]);
        assertEquals(0, calls[1]);
    }

    @Test
    public void anExceptionOfAnyKindEndsTheStepAndCallsTheHandlerOnceWithoutReachingTheCaller() {
        Runnable[] bodies = {
                () -> {
                    throw new SecurityException("a lock call");
                },
                () -> {
                    throw new IllegalArgumentException("bad");
                },
                () -> {
                    throw new OutOfMemoryError();
                },
                () -> {
                    throw new StackOverflowError();
                }};
        for (Runnable body : bodies) {
            int[] handled = {0};
            assertFalse(SafeRun.run(body, () -> handled[0]++));
            assertEquals(1, handled[0]);
        }
    }

    @Test
    public void aHandlerThatThrowsItselfIsSwallowedToo() {
        assertFalse(SafeRun.run(() -> {
            throw new IllegalStateException("first");
        }, () -> {
            throw new IllegalStateException("second");
        }));
    }
}
