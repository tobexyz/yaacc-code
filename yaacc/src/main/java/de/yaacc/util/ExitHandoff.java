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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ends things that may wait (a remote player sleeps and polls, up to 30 s, on the thread that ends it) without ever making the main
 * thread wait (review W2 of Group 12A). Every step runs on a thread of its own; the caller returns at once. The hand-over
 * ({@link Then}) runs <b>on the main thread, once</b>: when every step has ended (posted by the last one), or after
 * {@code boundMillis} (a delayed post made up front), whichever comes first. A step that is still running then is named in the
 * outcome and left to run: the process ends soon and the threads are daemons.
 * <p>
 * Plain Java: the main thread is the {@link MainThread} seam (a looper handler in the app, a queue in the tests).
 */
public final class ExitHandoff {

    /** The main thread: where the hand-over runs. */
    public interface MainThread {
        void post(Runnable task);

        void postDelayed(Runnable task, long delayMillis);
    }

    /** What the hand-over is told. */
    public static final class Outcome {
        private final List<String> failed;
        private final List<String> unfinished;

        Outcome(List<String> failed, List<String> unfinished) {
            this.failed = failed;
            this.unfinished = unfinished;
        }

        /** Steps that ended by throwing (or that the executor refused). */
        public List<String> failed() {
            return failed;
        }

        /** Steps that had not ended when the bound was up. */
        public List<String> unfinished() {
            return unfinished;
        }

        /** True if every step ended (failed ones count as ended). */
        public boolean allEnded() {
            return unfinished.isEmpty();
        }
    }

    /** The hand-over, on the main thread. */
    public interface Then {
        void run(Outcome outcome);
    }

    private ExitHandoff() {
    }

    /** Returns at once. {@code names} and {@code steps} are parallel lists. */
    public static void run(List<String> names, List<ExitSequence.Step> steps, Executor workers, MainThread main, long boundMillis,
                           Then then) {
        final int count = steps.size();
        final AtomicInteger remaining = new AtomicInteger(count);
        final AtomicBoolean handedOver = new AtomicBoolean();
        final List<String> failed = Collections.synchronizedList(new ArrayList<String>());
        final boolean[] ended = new boolean[count];
        final Runnable handOver = new Runnable() {
            @Override
            public void run() {
                if (!handedOver.compareAndSet(false, true)) {
                    return;
                }
                List<String> stillRunning = new ArrayList<>();
                synchronized (ended) {
                    for (int i = 0; i < count; i++) {
                        if (!ended[i]) {
                            stillRunning.add(names.get(i));
                        }
                    }
                }
                then.run(new Outcome(new ArrayList<>(failed), stillRunning));
            }
        };
        if (count == 0) {
            main.post(handOver);
            return;
        }
        main.postDelayed(handOver, boundMillis);
        for (int i = 0; i < count; i++) {
            final int index = i;
            final ExitSequence.Step step = steps.get(i);
            final Runnable finished = () -> {
                synchronized (ended) {
                    ended[index] = true;
                }
                if (remaining.decrementAndGet() == 0) {
                    main.post(handOver);
                }
            };
            try {
                workers.execute(() -> {
                    try {
                        step.run();
                    } catch (Throwable t) { // an Error of a library must not leave the exit waiting for the bound
                        failed.add(names.get(index));
                    } finally {
                        finished.run();
                    }
                });
            } catch (RejectedExecutionException refused) {
                failed.add(names.get(index));
                finished.run();
            }
        }
    }
}
