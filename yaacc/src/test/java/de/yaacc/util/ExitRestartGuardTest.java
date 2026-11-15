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

import static de.yaacc.util.ExitRestartGuard.Decision.START;
import static de.yaacc.util.ExitRestartGuard.Decision.STOP_SELF;
import static de.yaacc.util.ExitRestartGuard.StartReason.BOOT;
import static de.yaacc.util.ExitRestartGuard.StartReason.PROCESS_START;
import static de.yaacc.util.ExitRestartGuard.StartReason.SYSTEM_RESTART;
import static de.yaacc.util.ExitRestartGuard.StartReason.USER_LAUNCH;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import de.yaacc.util.ExitRestartGuard.StartReason;

/**
 * Task 12.2: after an explicit exit (exit button, sleep timer, idle timer) the system may bring a service back (sticky restart) or
 * the process may be created again for another reason; that must not look like the app starting by itself. The decision is
 * "exit requested at T, now, why does something start" and a start by the user is never blocked.
 */
public class ExitRestartGuardTest {

    private static final long T = 1_000_000_000L;

    // ---- the pure decision ----------------------------------------------------------------------------------------

    @Test
    public void noExitRequestedEverythingStarts() {
        for (StartReason reason : StartReason.values()) {
            assertEquals(reason.name(), START, ExitRestartGuard.decide(0L, T, reason));
        }
    }

    @Test
    public void aStickyRestartRightAfterAnExitStopsItself() {
        assertEquals(STOP_SELF, ExitRestartGuard.decide(T, T + 1_000L, SYSTEM_RESTART));
    }

    @Test
    public void aProcessThatIsCreatedRightAfterAnExitDoesNotStartTheServices() {
        assertEquals(STOP_SELF, ExitRestartGuard.decide(T, T + 3_000L, PROCESS_START));
    }

    @Test
    public void aScreenTheSystemRestoresRightAfterAnExitIsDropped() {
        assertEquals(STOP_SELF, ExitRestartGuard.decide(T, T + 2_000L, ExitRestartGuard.StartReason.RESTORED_SCREEN));
        assertEquals(START, ExitRestartGuard.decide(T, T + ExitRestartGuard.WINDOW_MILLIS, ExitRestartGuard.StartReason.RESTORED_SCREEN));
        assertEquals(START, ExitRestartGuard.decide(0L, T, ExitRestartGuard.StartReason.RESTORED_SCREEN));
    }

    @Test
    public void theUserStartingTheAppIsNeverBlockedAtAnyTime() {
        assertEquals(START, ExitRestartGuard.decide(T, T, USER_LAUNCH));
        assertEquals(START, ExitRestartGuard.decide(T, T + 1L, USER_LAUNCH));
        assertEquals(START, ExitRestartGuard.decide(T, T + ExitRestartGuard.WINDOW_MILLIS - 1, USER_LAUNCH));
    }

    @Test
    public void aBootIsANewSessionAndStartsTheAutostart() {
        assertEquals(START, ExitRestartGuard.decide(T, T + 5_000L, BOOT));
    }

    @Test
    public void theWindowEndsExactlyAtItsLength() {
        assertEquals(STOP_SELF, ExitRestartGuard.decide(T, T + ExitRestartGuard.WINDOW_MILLIS - 1, SYSTEM_RESTART));
        assertEquals(START, ExitRestartGuard.decide(T, T + ExitRestartGuard.WINDOW_MILLIS, SYSTEM_RESTART));
        assertEquals(START, ExitRestartGuard.decide(T, T + ExitRestartGuard.WINDOW_MILLIS + 60_000L, PROCESS_START));
    }

    @Test
    public void aMarkFromTheFarFutureOrAClockThatWentBackDoesNotBlockForever() {
        // the clock was set back by an hour: the mark is "in the future", it can not be judged, so nothing is blocked
        assertEquals(START, ExitRestartGuard.decide(T, T - 3_600_000L, SYSTEM_RESTART));
    }

    @Test
    public void aSmallClockStepBackwardsStillCountsAsRightAfter() {
        assertEquals(STOP_SELF, ExitRestartGuard.decide(T, T - 1_000L, SYSTEM_RESTART));
    }

    @Test
    public void garbageMarksAreNoMark() {
        assertEquals(START, ExitRestartGuard.decide(-1L, T, SYSTEM_RESTART));
        assertEquals(START, ExitRestartGuard.decide(Long.MIN_VALUE, T, PROCESS_START));
    }

    // ---- with a store -----------------------------------------------------------------------------------------------

    private static final class MemoryStore implements ExitRestartGuard.Store {
        long value;
        int writes;
        boolean failReads;
        boolean failWrites;

        @Override
        public long readExitRequestedAt() {
            if (failReads) {
                throw new IllegalStateException("unreadable");
            }
            return value;
        }

        @Override
        public void writeExitRequestedAt(long millis) {
            if (failWrites) {
                throw new IllegalStateException("not writable");
            }
            value = millis;
            writes++;
        }
    }

    @Test
    public void markThenARestartIsRefusedAndAUserStartClearsTheMark() {
        MemoryStore store = new MemoryStore();
        AtomicLong clock = new AtomicLong(T);
        ExitRestartGuard guard = new ExitRestartGuard(store, clock::get);

        assertEquals(START, guard.decide(SYSTEM_RESTART));
        guard.markExitRequested();
        assertEquals(T, store.value);
        clock.set(T + 2_000L);
        assertEquals(STOP_SELF, guard.decide(SYSTEM_RESTART));
        assertEquals(STOP_SELF, guard.decide(PROCESS_START));

        guard.clearOnUserStart();
        assertEquals(0L, store.value);
        clock.set(T + 3_000L);
        assertEquals("a later start by the system is a normal start again", START, guard.decide(SYSTEM_RESTART));
    }

    @Test
    public void theMarkSurvivesANewGuardOverTheSameStoreLikeANewProcess() {
        MemoryStore store = new MemoryStore();
        new ExitRestartGuard(store, () -> T).markExitRequested();
        ExitRestartGuard afterTheProcessEnded = new ExitRestartGuard(store, () -> T + 4_000L);
        assertEquals(STOP_SELF, afterTheProcessEnded.decide(SYSTEM_RESTART));
    }

    @Test
    public void aStoreThatCanNotBeReadNeverBlocksAStart() {
        MemoryStore store = new MemoryStore();
        store.value = T;
        store.failReads = true;
        assertEquals(START, new ExitRestartGuard(store, () -> T + 1L).decide(SYSTEM_RESTART));
    }

    @Test
    public void aStoreThatCanNotBeWrittenDoesNotMakeTheExitFail() {
        MemoryStore store = new MemoryStore();
        store.failWrites = true;
        ExitRestartGuard guard = new ExitRestartGuard(store, () -> T);
        guard.markExitRequested(); // no exception: the exit goes on
        guard.clearOnUserStart();  // the same for the clear
    }

    @Test
    public void clearingWhenThereIsNoMarkWritesNothing() {
        // every screen the user opens calls it: no disk write on the main thread when there is nothing to clear
        MemoryStore store = new MemoryStore();
        new ExitRestartGuard(store, () -> T).clearOnUserStart();
        assertEquals(0, store.writes);
        store.value = T;
        new ExitRestartGuard(store, () -> T + 1L).clearOnUserStart();
        assertEquals(1, store.writes);
        assertEquals(0L, store.value);
    }

    @Test
    public void markingTwiceKeepsTheLaterTime() {
        MemoryStore store = new MemoryStore();
        AtomicLong clock = new AtomicLong(T);
        ExitRestartGuard guard = new ExitRestartGuard(store, clock::get);
        guard.markExitRequested();
        clock.set(T + 10_000L);
        guard.markExitRequested();
        assertEquals(T + 10_000L, store.value);
    }
}
