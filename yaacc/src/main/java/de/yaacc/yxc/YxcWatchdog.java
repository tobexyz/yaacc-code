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

import java.net.HttpURLConnection;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * An overall time limit for one HTTP exchange. The timeouts of {@code HttpURLConnection} apply to each single
 * read, so a peer that sends the status line and then a header line every few seconds, or a byte now and then,
 * would hold the calling thread for as long as it likes. The watchdog disconnects the connection when the
 * time is up, which makes the blocked call fail with an {@code IOException}.
 */
final class YxcWatchdog {
    /** Time allowed for connect, headers and body of one exchange together. */
    static final long DEFAULT_TOTAL_MILLIS = 15_000L;

    private static final ScheduledThreadPoolExecutor TIMER = createTimer();

    private YxcWatchdog() {
    }

    private static ScheduledThreadPoolExecutor createTimer() {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "yxc-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }

    /** Disconnects the connection after the time; the caller cancels the result in a {@code finally}. */
    static ScheduledFuture<?> arm(HttpURLConnection connection, long totalMillis) {
        return TIMER.schedule(connection::disconnect, totalMillis, TimeUnit.MILLISECONDS);
    }
}
