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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link CopyBudget}: the copy fallback for a provider file that cannot seek stops
 * when it grows past the cap or when the volume would run too low on space. Plain JVM.
 */
public class CopyBudgetTest {

    private static final long MAX = SharedContentPolicy.MAX_COPY_BYTES;
    private static final long RESERVE = ShareLimits.COPY_FREE_SPACE_RESERVE_BYTES;
    private static final long GIB = 1024L * 1024L * 1024L;
    private static final long UNKNOWN = -1L;

    // ---- constants

    @Test
    public void theCapIs512MiBAndTheReserveIs64MiB() {
        assertEquals(512L * 1024L * 1024L, MAX);
        assertEquals(64L * 1024L * 1024L, RESERVE);
    }

    // ---- continue

    @Test
    public void aSmallCopyWithPlentyOfSpaceContinues() {
        assertEquals(CopyBudget.Decision.CONTINUE, CopyBudget.decide(0L, 1_000L, 10 * GIB));
        assertEquals(CopyBudget.Decision.CONTINUE, CopyBudget.decide(500L, 1_000L, 10 * GIB));
        assertEquals(CopyBudget.Decision.CONTINUE, CopyBudget.decide(1_000L, 1_000L, 10 * GIB));
    }

    @Test
    public void anUnknownSizeContinuesWhileUnderTheCapAndSpaceIsLeft() {
        assertEquals(CopyBudget.Decision.CONTINUE, CopyBudget.decide(0L, UNKNOWN, 10 * GIB));
        assertEquals(CopyBudget.Decision.CONTINUE, CopyBudget.decide(100L * 1024 * 1024, UNKNOWN, 10 * GIB));
    }

    @Test
    public void anyNegativeDeclaredSizeCountsAsUnknown() {
        assertEquals(CopyBudget.Decision.CONTINUE, CopyBudget.decide(10L, -2L, 10 * GIB));
        assertEquals(CopyBudget.Decision.CONTINUE, CopyBudget.decide(10L, Long.MIN_VALUE, 10 * GIB));
    }

    // ---- too big

    @Test
    public void exactlyTheCapIsStillAllowed() {
        assertEquals(CopyBudget.Decision.CONTINUE, CopyBudget.decide(MAX, UNKNOWN, 10 * GIB));
        assertEquals(CopyBudget.Decision.CONTINUE, CopyBudget.decide(MAX, MAX, 10 * GIB));
        assertEquals(CopyBudget.Decision.CONTINUE, CopyBudget.decide(0L, MAX, 10 * GIB));
    }

    @Test
    public void oneByteOverTheCapAborts() {
        assertEquals(CopyBudget.Decision.ABORT_TOO_BIG, CopyBudget.decide(MAX + 1, UNKNOWN, 10 * GIB));
        assertEquals(CopyBudget.Decision.ABORT_TOO_BIG, CopyBudget.decide(MAX + 1, MAX, 10 * GIB));
        assertEquals(CopyBudget.Decision.ABORT_TOO_BIG, CopyBudget.decide(Long.MAX_VALUE, UNKNOWN, 10 * GIB));
    }

    @Test
    public void aDeclaredSizeOverTheCapAbortsBeforeAnythingIsCopied() {
        assertEquals(CopyBudget.Decision.ABORT_TOO_BIG, CopyBudget.decide(0L, MAX + 1, 10 * GIB));
        assertEquals(CopyBudget.Decision.ABORT_TOO_BIG, CopyBudget.decide(0L, 5 * GIB, 100 * GIB));
        assertEquals(CopyBudget.Decision.ABORT_TOO_BIG, CopyBudget.decide(0L, Long.MAX_VALUE, Long.MAX_VALUE));
    }

    @Test
    public void tooBigWinsOverNoSpace() {
        assertEquals(CopyBudget.Decision.ABORT_TOO_BIG, CopyBudget.decide(MAX + 1, UNKNOWN, 0L));
        assertEquals(CopyBudget.Decision.ABORT_TOO_BIG, CopyBudget.decide(0L, MAX + 1, 0L));
    }

    @Test
    public void aNegativeByteCountIsABugAndAborts() {
        assertEquals(CopyBudget.Decision.ABORT_TOO_BIG, CopyBudget.decide(-1L, UNKNOWN, 10 * GIB));
        assertEquals(CopyBudget.Decision.ABORT_TOO_BIG, CopyBudget.decide(Long.MIN_VALUE, 10L, 10 * GIB));
    }

    // ---- no space

    @Test
    public void lessThanTheReserveFreeAborts() {
        assertEquals(CopyBudget.Decision.ABORT_NO_SPACE, CopyBudget.decide(0L, UNKNOWN, RESERVE - 1));
        assertEquals(CopyBudget.Decision.ABORT_NO_SPACE, CopyBudget.decide(0L, UNKNOWN, 0L));
        assertEquals(CopyBudget.Decision.ABORT_NO_SPACE, CopyBudget.decide(10L, 10L, 0L));
    }

    @Test
    public void exactlyTheReserveFreeIsEnoughWhenNothingMoreIsNeeded() {
        assertEquals(CopyBudget.Decision.CONTINUE, CopyBudget.decide(0L, UNKNOWN, RESERVE));
        assertEquals(CopyBudget.Decision.CONTINUE, CopyBudget.decide(1_000L, 1_000L, RESERVE));
    }

    @Test
    public void theRemainingBytesOfAKnownSizeMustFitAboveTheReserve() {
        long declared = 100L * 1024 * 1024;
        long done = 40L * 1024 * 1024;
        long remaining = declared - done;

        assertEquals(CopyBudget.Decision.CONTINUE, CopyBudget.decide(done, declared, RESERVE + remaining));
        assertEquals(CopyBudget.Decision.ABORT_NO_SPACE, CopyBudget.decide(done, declared, RESERVE + remaining - 1));
    }

    @Test
    public void aKnownSizeIsCheckedBeforeTheFirstByteIsCopied() {
        long declared = 300L * 1024 * 1024;

        assertEquals(CopyBudget.Decision.CONTINUE, CopyBudget.decide(0L, declared, declared + RESERVE));
        assertEquals(CopyBudget.Decision.ABORT_NO_SPACE, CopyBudget.decide(0L, declared, declared + RESERVE - 1));
    }

    @Test
    public void aVolumeThatReportsNegativeSpaceAborts() {
        assertEquals(CopyBudget.Decision.ABORT_NO_SPACE, CopyBudget.decide(0L, UNKNOWN, -1L));
        assertEquals(CopyBudget.Decision.ABORT_NO_SPACE, CopyBudget.decide(0L, UNKNOWN, Long.MIN_VALUE));
    }

    @Test
    public void bytesAlreadyCopiedBeyondTheDeclaredSizeNeedNoMoreSpace() {
        // the provider declared 10 bytes but sends 20: no negative "remaining"
        assertEquals(CopyBudget.Decision.CONTINUE, CopyBudget.decide(20L, 10L, RESERVE));
        assertEquals(CopyBudget.Decision.ABORT_NO_SPACE, CopyBudget.decide(20L, 10L, RESERVE - 1));
    }

    @Test
    public void everyDecisionIsOneOfThree() {
        assertEquals(3, CopyBudget.Decision.values().length);
        assertTrue(CopyBudget.Decision.valueOf("CONTINUE") != null);
        assertTrue(CopyBudget.Decision.valueOf("ABORT_TOO_BIG") != null);
        assertTrue(CopyBudget.Decision.valueOf("ABORT_NO_SPACE") != null);
    }
}
