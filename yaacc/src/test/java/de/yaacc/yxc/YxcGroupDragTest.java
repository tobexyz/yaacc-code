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
package de.yaacc.yxc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/** The drag on the group volume slider and the parallel read of the start levels. */
public class YxcGroupDragTest {

    private static Map<String, Integer> levels(Object... pairs) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        return result;
    }

    @Test
    public void movementBeforeTheLevelsAreThereIsRememberedAndAppliedOnce() {
        YxcGroupDrag drag = new YxcGroupDrag();
        int id = drag.begin(50);

        assertNull(drag.moved(55));
        assertNull(drag.moved(60));
        Map<String, Integer> first = drag.levelsArrived(id, levels("a", 40, "b", 70));

        assertEquals("the latest position, applied to the start levels", levels("a", 50, "b", 80), first);
    }

    @Test
    public void afterTheLevelsAreThereEveryMoveGivesTheTargets() {
        YxcGroupDrag drag = new YxcGroupDrag();
        int id = drag.begin(50);
        drag.levelsArrived(id, levels("a", 40, "b", 95));

        assertEquals(levels("a", 50, "b", 100), drag.moved(60));
        assertEquals("back to the start: every member at its own level", levels("a", 40, "b", 95), drag.moved(50));
    }

    @Test
    public void aRebindDuringTheDragDoesNotLoseIt() {
        YxcGroupDrag drag = new YxcGroupDrag();
        int id = drag.begin(30);
        drag.levelsArrived(id, levels("a", 20));

        // the row is rebound here: a new listener only calls moved() on the same drag object
        assertTrue(drag.isActive());
        assertEquals(levels("a", 25), drag.moved(35));
    }

    @Test
    public void aLateReadOfAnEarlierDragIsDropped() {
        YxcGroupDrag drag = new YxcGroupDrag();
        int first = drag.begin(10);
        drag.end();
        int second = drag.begin(70);

        assertNull("the read of the first drag arrives late", drag.levelsArrived(first, levels("a", 5)));
        assertNull(drag.moved(75));
        assertEquals(levels("a", 15), drag.levelsArrived(second, levels("a", 10)));
    }

    @Test
    public void afterTheEndNothingIsSentAndNoLateReadIsApplied() {
        YxcGroupDrag drag = new YxcGroupDrag();
        int id = drag.begin(10);
        drag.end();

        assertFalse(drag.isActive());
        assertNull(drag.moved(20));
        assertNull(drag.levelsArrived(id, levels("a", 5)));
    }

    @Test
    public void aMemberThatDoesNotAnswerIsLeftOutAndDoesNotHoldTheOthersUp() {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            long start = System.nanoTime();
            Map<String, Integer> read = YxcGroupVolume.readLevels(java.util.Arrays.asList("fast", "slow", "broken", "unreadable"),
                    udn -> {
                        switch (udn) {
                            case "fast":
                                return 42;
                            case "slow":
                                try {
                                    Thread.sleep(10_000);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                                return 99;
                            case "broken":
                                throw new IllegalStateException("simulated");
                            default:
                                return -1;
                        }
                    }, pool, 500);

            assertEquals(levels("fast", 42), read);
            assertTrue("did not wait for the slow one", (System.nanoTime() - start) / 1_000_000L < 5000);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void aClosedPoolGivesNoLevelsAndNoException() {
        ExecutorService pool = Executors.newFixedThreadPool(1);
        pool.shutdownNow();

        assertTrue(YxcGroupVolume.readLevels(java.util.Arrays.asList("a"), udn -> 5, pool, 200).isEmpty());
    }
}
