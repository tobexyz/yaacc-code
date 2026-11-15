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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Review W2 of Group 12A: ending a remote player waits (it sleeps, up to 30 s, on the calling thread), so the exit must never end a
 * player on the main thread. {@link ExitHandoff} runs every player's end on a thread of its own and hands over to the main thread
 * when all ended or after a bound, whichever is first; the caller returns at once. The main thread is a fake queue here.
 */
public class ExitHandoffTest {

    private static final class FakeMain implements ExitHandoff.MainThread {
        final List<Runnable> posted = Collections.synchronizedList(new ArrayList<Runnable>());
        final List<Runnable> delayed = Collections.synchronizedList(new ArrayList<Runnable>());
        final List<Long> delays = Collections.synchronizedList(new ArrayList<Long>());

        @Override
        public void post(Runnable task) {
            posted.add(task);
        }

        @Override
        public void postDelayed(Runnable task, long delayMillis) {
            delayed.add(task);
            delays.add(delayMillis);
        }

        /** Runs what was posted so far, like the main looper; the delayed ones only when asked. */
        void runPosted() {
            List<Runnable> now;
            synchronized (posted) {
                now = new ArrayList<>(posted);
                posted.clear();
            }
            for (Runnable task : now) {
                task.run();
            }
        }

        void runDelayed() {
            List<Runnable> now;
            synchronized (delayed) {
                now = new ArrayList<>(delayed);
                delayed.clear();
            }
            for (Runnable task : now) {
                task.run();
            }
        }
    }

    @Rule
    public final Timeout timeout = Timeout.seconds(30);

    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "test-exit-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final CountDownLatch neverReleased = new CountDownLatch(1);

    @After
    public void tearDown() {
        neverReleased.countDown();
        workers.shutdownNow();
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < end) {
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean());
    }

    @Test
    public void theCallerGetsControlBackAtOnceEvenWithAPlayerThatNeverReturns() {
        FakeMain main = new FakeMain();
        AtomicInteger thens = new AtomicInteger();
        long start = System.nanoTime();
        ExitHandoff.run(Arrays.asList("slow player"), Arrays.<ExitSequence.Step>asList(() -> neverReleased.await()),
                workers, main, 4_000L, outcome -> thens.incrementAndGet());
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue("run() must not wait for the player, took " + tookMillis + " ms", tookMillis < 500);
        assertEquals("nothing continues before the bound or the end of the players", 0, thens.get());
    }

    @Test
    public void whenThePlayerNeverReturnsTheBoundHandsOverOnceAndNamesTheStuckPlayer() throws Exception {
        FakeMain main = new FakeMain();
        List<ExitHandoff.Outcome> outcomes = Collections.synchronizedList(new ArrayList<ExitHandoff.Outcome>());
        AtomicInteger fastRan = new AtomicInteger();
        ExitHandoff.run(Arrays.asList("fast", "slow"),
                Arrays.<ExitSequence.Step>asList(fastRan::incrementAndGet, () -> neverReleased.await()),
                workers, main, 4_000L, outcomes::add);
        assertEquals("the bound is a delayed post on the main thread", Collections.singletonList(4_000L), main.delays);
        awaitTrue(() -> fastRan.get() == 1);
        Thread.sleep(100); // the fast step is marked as ended right after it returns
        main.runPosted();  // nothing was posted: the hand-over waits for the slow one
        assertEquals(0, outcomes.size());

        main.runDelayed(); // the bound is up
        assertEquals(1, outcomes.size());
        assertFalse(outcomes.get(0).allEnded());
        assertEquals(Collections.singletonList("slow"), outcomes.get(0).unfinished());
        assertTrue(outcomes.get(0).failed().isEmpty());
    }

    @Test
    public void whenAllPlayersEndTheHandOverIsPostedOnceAndTheBoundDoesNothingLater() throws Exception {
        FakeMain main = new FakeMain();
        List<ExitHandoff.Outcome> outcomes = Collections.synchronizedList(new ArrayList<ExitHandoff.Outcome>());
        ExitHandoff.run(Arrays.asList("a", "b"), Arrays.<ExitSequence.Step>asList(() -> { }, () -> { }),
                workers, main, 4_000L, outcomes::add);
        awaitTrue(() -> !main.posted.isEmpty());
        main.runPosted();
        assertEquals(1, outcomes.size());
        assertTrue(outcomes.get(0).allEnded());
        assertTrue(outcomes.get(0).unfinished().isEmpty());

        main.runDelayed();
        main.runPosted();
        assertEquals("the hand-over runs once", 1, outcomes.size());
    }

    @Test
    public void aPlayerThatThrowsIsNamedAndDoesNotKeepTheOthersFromEnding() throws Exception {
        FakeMain main = new FakeMain();
        List<ExitHandoff.Outcome> outcomes = Collections.synchronizedList(new ArrayList<ExitHandoff.Outcome>());
        AtomicInteger ended = new AtomicInteger();
        ExitHandoff.run(Arrays.asList("bad", "good"), Arrays.<ExitSequence.Step>asList(
                () -> { throw new IllegalStateException("does not release"); },
                () -> ended.incrementAndGet()), workers, main, 4_000L, outcomes::add);
        awaitTrue(() -> ended.get() == 1);
        awaitTrue(() -> !main.posted.isEmpty());
        main.runPosted();
        assertEquals(Collections.singletonList("bad"), outcomes.get(0).failed());
        assertTrue("a failed player has ended all the same", outcomes.get(0).allEnded());
    }

    @Test
    public void aStepThatRunsInThePlayersThreadNeverRunsOnTheCallingThread() throws Exception {
        FakeMain main = new FakeMain();
        Thread caller = Thread.currentThread();
        List<Thread> ranOn = Collections.synchronizedList(new ArrayList<Thread>());
        ExitHandoff.run(Arrays.asList("p"), Arrays.<ExitSequence.Step>asList(() -> ranOn.add(Thread.currentThread())),
                workers, main, 4_000L, outcome -> { });
        awaitTrue(() -> !ranOn.isEmpty());
        assertTrue(ranOn.get(0) != caller);
    }

    @Test
    public void noPlayersHandsOverAtOnce() {
        FakeMain main = new FakeMain();
        List<ExitHandoff.Outcome> outcomes = new ArrayList<>();
        ExitHandoff.run(Collections.<String>emptyList(), Collections.<ExitSequence.Step>emptyList(), workers, main, 4_000L, outcomes::add);
        main.runPosted();
        assertEquals(1, outcomes.size());
        assertTrue(outcomes.get(0).allEnded());
    }

    @Test
    public void anExecutorThatRefusesCountsTheStepAsFailedNotAsHanging() {
        FakeMain main = new FakeMain();
        List<ExitHandoff.Outcome> outcomes = new ArrayList<>();
        ExitHandoff.run(Arrays.asList("p"), Arrays.<ExitSequence.Step>asList(() -> { }),
                task -> { throw new java.util.concurrent.RejectedExecutionException("shut down"); }, main, 4_000L, outcomes::add);
        main.runPosted();
        assertEquals(1, outcomes.size());
        assertEquals(Collections.singletonList("p"), outcomes.get(0).failed());
    }
}
