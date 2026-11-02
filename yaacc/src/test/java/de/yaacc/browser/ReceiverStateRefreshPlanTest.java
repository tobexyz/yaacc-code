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
package de.yaacc.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Unit tests for {@link ReceiverStateRefreshPlan}: which receiver is read in which poll cycle, the pause
 * after failures, and when a value that was read is dropped because the user acts on the row. Plain JVM,
 * the clock is a number.
 */
public class ReceiverStateRefreshPlanTest {
    private static final long INTERVAL = ReceiverStateRefreshPlan.REFRESH_INTERVAL_MS;

    private final ReceiverStateRefreshPlan plan = new ReceiverStateRefreshPlan();

    private List<String> due(long now, String... ids) {
        return plan.dueForRefresh(Arrays.asList(ids), now);
    }

    // --- refresh schedule

    @Test
    public void aReceiverNotSeenBeforeIsDueAtOnce() {
        assertEquals(Arrays.asList("tv", "kitchen"), due(0, "tv", "kitchen"));
    }

    @Test
    public void aReceiverWhoseRequestIsStillRunningIsSkipped() {
        due(0, "tv");

        assertEquals(Collections.emptyList(), due(2000, "tv"));
        assertEquals(Collections.emptyList(), due(10 * INTERVAL, "tv")); // however long it takes
    }

    @Test
    public void aSlowReceiverDoesNotHoldBackTheOthers() {
        due(0, "tv", "kitchen");
        plan.refreshSucceeded("kitchen", 100);

        assertEquals(Collections.singletonList("kitchen"), due(100 + INTERVAL, "tv", "kitchen"));
    }

    @Test
    public void aReceiverIsReadEveryThirdTickOfTheTwoSecondPoll() {
        assertEquals(Collections.singletonList("tv"), due(0, "tv"));
        plan.refreshSucceeded("tv", 0);

        assertEquals(Collections.emptyList(), due(2000, "tv"));
        assertEquals(Collections.emptyList(), due(4000, "tv"));
        assertEquals(Collections.singletonList("tv"), due(6000, "tv"));
    }

    @Test
    public void theIntervalIsCountedFromTheAnswerNotFromTheStart() {
        due(0, "tv");
        plan.refreshSucceeded("tv", 3000); // a slow answer

        assertEquals(Collections.emptyList(), due(6000, "tv"));
        assertEquals(Collections.singletonList("tv"), due(9000, "tv"));
    }

    @Test
    public void onlyTheReceiversOnScreenAreAsked() {
        due(0, "tv", "kitchen");
        plan.refreshSucceeded("tv", 0);
        plan.refreshSucceeded("kitchen", 0);

        assertEquals(Collections.singletonList("kitchen"), due(INTERVAL, "kitchen"));
    }

    @Test
    public void failuresDoubleThePauseUpToTheMaximum() {
        long now = 0;
        long[] expectedPauses = {12000, 24000, 48000, 60000, 60000};
        for (long pause : expectedPauses) {
            assertEquals(Collections.singletonList("tv"), due(now, "tv"));
            plan.refreshFailed("tv", now);
            assertEquals(Collections.emptyList(), due(now + pause - 1, "tv"));
            now += pause;
        }
        assertEquals(Collections.singletonList("tv"), due(now, "tv"));
    }

    @Test
    public void aSuccessEndsTheBackoff() {
        due(0, "tv");
        plan.refreshFailed("tv", 0);
        due(12000, "tv");
        plan.refreshFailed("tv", 12000);
        due(36000, "tv");
        plan.refreshSucceeded("tv", 36000);

        assertEquals(Collections.singletonList("tv"), due(36000 + INTERVAL, "tv"));
    }

    @Test
    public void aFailingReceiverDoesNotChangeTheScheduleOfAnother() {
        due(0, "tv", "kitchen");
        plan.refreshFailed("tv", 0);
        plan.refreshSucceeded("kitchen", 0);

        assertEquals(Collections.singletonList("kitchen"), due(INTERVAL, "tv", "kitchen"));
    }

    @Test
    public void backoffIsNeverShorterThanTheIntervalOrLongerThanTheMaximum() {
        for (int failures = 0; failures < 200; failures++) {
            long pause = ReceiverStateRefreshPlan.backoffMs(failures);
            assertTrue(pause >= INTERVAL);
            assertTrue(pause <= ReceiverStateRefreshPlan.MAX_BACKOFF_MS);
        }
    }

    @Test
    public void anAnswerForAReceiverNeverAskedIsIgnored() {
        plan.refreshSucceeded("ghost", 0);
        plan.refreshFailed("ghost", 0);

        assertEquals(Collections.singletonList("ghost"), due(0, "ghost"));
    }

    @Test
    public void resetForgetsRequestsInFlightAndPauses() {
        due(0, "tv");
        plan.refreshFailed("tv", 0);

        plan.reset();

        assertEquals(Collections.singletonList("tv"), due(1, "tv"));
    }

    // --- user rule

    @Test
    public void aValueReadWhileNobodyTouchesTheRowIsShown() {
        assertFalse(plan.shouldIgnoreRead("tv", 100, 200));
    }

    @Test
    public void aValueIsDroppedWhileTheUserHoldsTheSlider() {
        plan.userTouchStarted("tv", 1000);

        assertTrue(plan.shouldIgnoreRead("tv", 5000, 20000));
    }

    @Test
    public void aValueIsDroppedForAShortTimeAfterTheUserLetGo() {
        plan.userTouchStarted("tv", 1000);
        plan.userTouchEnded("tv", 2000);

        assertTrue(plan.shouldIgnoreRead("tv", 2001, 2002));
        assertTrue(plan.shouldIgnoreRead("tv", 4000, 2000 + ReceiverStateRefreshPlan.USER_GRACE_MS - 1));
        assertFalse(plan.shouldIgnoreRead("tv", 4000, 2000 + ReceiverStateRefreshPlan.USER_GRACE_MS));
    }

    @Test
    public void aValueReadBeforeTheUsersActionIsDroppedHoweverLateItArrives() {
        plan.userChanged("tv", 5000);

        // read started at 4000 (before), answered at 20000
        assertTrue(plan.shouldIgnoreRead("tv", 4000, 20000));
        // read started after the action
        assertFalse(plan.shouldIgnoreRead("tv", 6000, 20000));
    }

    @Test
    public void tappingMuteCountsLikeTheSliderAndOnlyForThatReceiver() {
        plan.userChanged("tv", 1000);

        assertTrue(plan.shouldIgnoreRead("tv", 1500, 1600));
        assertFalse(plan.shouldIgnoreRead("kitchen", 1500, 1600));
    }

    @Test
    public void aTouchWhoseEndWasNeverReportedStopsCounting() {
        plan.userTouchStarted("tv", 0);

        assertTrue(plan.shouldIgnoreRead("tv", 5000, ReceiverStateRefreshPlan.TOUCH_TIMEOUT_MS - 1));
        assertFalse(plan.shouldIgnoreRead("tv", ReceiverStateRefreshPlan.TOUCH_TIMEOUT_MS,
                ReceiverStateRefreshPlan.TOUCH_TIMEOUT_MS + ReceiverStateRefreshPlan.USER_GRACE_MS));
    }

    @Test
    public void resetForgetsTheUsersActions() {
        plan.userTouchStarted("tv", 1000);

        plan.reset();

        assertFalse(plan.shouldIgnoreRead("tv", 1500, 1600));
    }

    @Test
    public void percentIsCutToTheSlidersRange() {
        assertEquals(0, ReceiverStateRefreshPlan.clampPercent(-5));
        assertEquals(37, ReceiverStateRefreshPlan.clampPercent(37));
        assertEquals(100, ReceiverStateRefreshPlan.clampPercent(250));
    }
}
