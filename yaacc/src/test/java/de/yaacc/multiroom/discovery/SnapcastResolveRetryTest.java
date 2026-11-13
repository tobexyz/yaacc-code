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
package de.yaacc.multiroom.discovery;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * A resolution that fails for a moment (the platform is busy with another one, or answers with an internal error, which it does
 * on many phones) is tried again, a few times and not for ever (task 11.6). Before, the service was dropped until it was lost
 * and found again, so a server that announced itself once stayed unlisted for the whole visit of the page.
 */
public class SnapcastResolveRetryTest {

    private static final int INTERNAL_ERROR = 0;
    private static final int ALREADY_ACTIVE = 3;
    private static final int MAX_LIMIT = 4;
    private static final int BAD_PARAMETERS = 6;

    @Test
    public void aBusyOrInternalFailureIsRetriedTwice() {
        SnapcastResolveRetry retry = new SnapcastResolveRetry();
        assertTrue(retry.shouldRetry("a", ALREADY_ACTIVE));
        assertTrue(retry.shouldRetry("a", INTERNAL_ERROR));
        assertFalse("the third failure gives up", retry.shouldRetry("a", ALREADY_ACTIVE));
    }

    @Test
    public void otherFailuresAreNotRetried() {
        SnapcastResolveRetry retry = new SnapcastResolveRetry();
        assertFalse(retry.shouldRetry("a", MAX_LIMIT));
        assertFalse(retry.shouldRetry("a", BAD_PARAMETERS));
        assertFalse(retry.shouldRetry("a", 42));
    }

    @Test
    public void everyServiceHasItsOwnCount() {
        SnapcastResolveRetry retry = new SnapcastResolveRetry();
        retry.shouldRetry("a", INTERNAL_ERROR);
        retry.shouldRetry("a", INTERNAL_ERROR);

        assertTrue(retry.shouldRetry("b", INTERNAL_ERROR));
    }

    @Test
    public void aResolvedServiceStartsOverTheNextTime() {
        SnapcastResolveRetry retry = new SnapcastResolveRetry();
        retry.shouldRetry("a", INTERNAL_ERROR);
        retry.shouldRetry("a", INTERNAL_ERROR);
        retry.succeeded("a");

        assertTrue(retry.shouldRetry("a", INTERNAL_ERROR));
    }

    @Test
    public void clearForgetsEverything() {
        SnapcastResolveRetry retry = new SnapcastResolveRetry();
        retry.shouldRetry("a", INTERNAL_ERROR);
        retry.shouldRetry("a", INTERNAL_ERROR);
        retry.clear();

        assertTrue(retry.shouldRetry("a", INTERNAL_ERROR));
    }

    @Test
    public void aFloodOfNamesDoesNotGrowTheTable() {
        SnapcastResolveRetry retry = new SnapcastResolveRetry();
        for (int i = 0; i < 10_000; i++) {
            retry.shouldRetry("name" + i, INTERNAL_ERROR);
        }
        assertTrue(retry.size() <= SnapcastResolveRetry.MAX_TRACKED);
    }

    @Test
    public void aNullIdIsNotRetried() {
        assertFalse(new SnapcastResolveRetry().shouldRetry(null, INTERNAL_ERROR));
    }

    @Test
    public void theDelayIsShortAndFixed() {
        assertTrue(SnapcastResolveRetry.DELAY_MILLIS >= 1_000L && SnapcastResolveRetry.DELAY_MILLIS <= 5_000L);
    }
}
