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

/**
 * Counts the started browser activities of the process and arms an idle timer only when the
 * count drops to zero.
 * <p>
 * Why a counter and not "arm in onStop, cancel in onResume": when the layout changes, the old
 * browser's {@code onStop} runs after the new browser's {@code onResume}, so a timer armed there
 * would never be cancelled. Rotation has the same shape. Plain Java (no Android imports) so it
 * can be unit tested; the Android side supplies the {@link Scheduler}. Call from the main thread
 * only; all methods are synchronized anyway so a stray caller cannot corrupt the count.
 */
public final class StartedBrowserCounter {

    /** Delayed execution on the main thread. */
    public interface Scheduler {
        void schedule(Runnable task, long delayMillis);

        void cancel(Runnable task);
    }

    private final Scheduler scheduler;
    private final long timeoutMillis;
    private final Runnable onIdle;
    private final Runnable expiry = this::onExpiry;
    private int started = 0;

    /**
     * @param scheduler     schedules and cancels the expiry task
     * @param timeoutMillis time without a started browser before {@code onIdle} runs
     * @param onIdle        runs on expiry; must re-read the current state itself
     */
    public StartedBrowserCounter(Scheduler scheduler, long timeoutMillis, Runnable onIdle) {
        this.scheduler = scheduler;
        this.timeoutMillis = timeoutMillis;
        this.onIdle = onIdle;
    }

    /** A browser activity reached {@code onStart}. */
    public synchronized void onBrowserStarted() {
        started++;
        scheduler.cancel(expiry);
    }

    /** A browser activity reached {@code onStop}. A stop without a start is ignored. */
    public synchronized void onBrowserStopped() {
        if (started == 0) {
            return;
        }
        started--;
        if (started == 0) {
            scheduler.cancel(expiry);
            scheduler.schedule(expiry, timeoutMillis);
        }
    }

    public synchronized int startedCount() {
        return started;
    }

    private void onExpiry() {
        synchronized (this) {
            if (started != 0) {
                return;
            }
        }
        onIdle.run();
    }
}
