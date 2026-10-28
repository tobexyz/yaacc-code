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
package de.yaacc.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** When the log viewer may change its text. Plain JVM. */
public class LogRefreshGateTest {

    @Test
    public void neverTouchedMeansAtRest() {
        assertTrue(LogRefreshGate.mayChangeText(10_000L, -1L));
    }

    @Test
    public void justTouchedOrScrolledMeansNotAtRest() {
        assertFalse(LogRefreshGate.mayChangeText(10_000L, 10_000L));
        assertFalse(LogRefreshGate.mayChangeText(10_000L, 10_000L - (LogRefreshGate.IDLE_MILLIS - 1)));
    }

    @Test
    public void afterTheIdleTimeTheTextMayChangeAgain() {
        assertTrue(LogRefreshGate.mayChangeText(10_000L, 10_000L - LogRefreshGate.IDLE_MILLIS));
        assertTrue(LogRefreshGate.mayChangeText(10_000L, 0L));
    }
}
