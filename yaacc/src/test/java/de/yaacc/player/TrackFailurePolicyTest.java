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
package de.yaacc.player;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import de.yaacc.player.TrackFailurePolicy.Decision;

/** 11.12: skip a track that can not be read, stop after three in a row or when the last item fails. */
public class TrackFailurePolicyTest {

    @Test
    public void aFailureWithAnItemBehindItIsSkipped() {
        assertEquals(Decision.SKIP, new TrackFailurePolicy().onFailure(true));
    }

    @Test
    public void aFailureOfTheLastItemStops() {
        assertEquals(Decision.STOP, new TrackFailurePolicy().onFailure(false));
    }

    @Test
    public void theThirdFailureInARowStopsTheFirstTwoSkip() {
        TrackFailurePolicy policy = new TrackFailurePolicy();

        assertEquals(Decision.SKIP, policy.onFailure(true));
        assertEquals(Decision.SKIP, policy.onFailure(true));
        assertEquals(Decision.STOP, policy.onFailure(true));
    }

    @Test
    public void theLimitIsThree() {
        assertEquals(3, TrackFailurePolicy.MAX_IN_A_ROW);
    }

    @Test
    public void aTrackThatStartsResetsTheCounter() {
        TrackFailurePolicy policy = new TrackFailurePolicy();
        policy.onFailure(true);
        policy.onFailure(true);

        policy.onTrackStarted();

        assertEquals(Decision.SKIP, policy.onFailure(true));
        assertEquals(Decision.SKIP, policy.onFailure(true));
        assertEquals(Decision.STOP, policy.onFailure(true));
    }

    @Test
    public void everyOtherTrackFailingNeverStops() {
        TrackFailurePolicy policy = new TrackFailurePolicy();

        for (int round = 0; round < 50; round++) {
            assertEquals(Decision.SKIP, policy.onFailure(true));
            policy.onTrackStarted();
        }
    }

    @Test
    public void aFailureOfTheLastItemStopsEvenAfterASuccess() {
        TrackFailurePolicy policy = new TrackFailurePolicy();
        policy.onTrackStarted();

        assertEquals(Decision.STOP, policy.onFailure(false));
    }

    @Test
    public void theLastItemAfterTwoSkipsStopsToo() {
        TrackFailurePolicy policy = new TrackFailurePolicy();
        policy.onFailure(true);

        assertEquals(Decision.STOP, policy.onFailure(false));
    }

    @Test
    public void afterAStopTheNextFailureStopsAgainUntilTheCounterIsReset() {
        TrackFailurePolicy policy = new TrackFailurePolicy();
        policy.onFailure(true);
        policy.onFailure(true);
        assertEquals(Decision.STOP, policy.onFailure(true));

        assertEquals("still over the limit", Decision.STOP, policy.onFailure(true));

        policy.reset();

        assertEquals(Decision.SKIP, policy.onFailure(true));
    }

    @Test
    public void startingWithoutAnyFailureChangesNothing() {
        TrackFailurePolicy policy = new TrackFailurePolicy();
        policy.onTrackStarted();
        policy.onTrackStarted();

        assertEquals(Decision.SKIP, policy.onFailure(true));
    }
}
