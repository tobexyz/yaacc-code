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
package de.yaacc.sendspin.session;

import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Where the commands of the user go out. A tap, a slider and a close are made on the main thread, which must not write to a socket
 * (Android throws {@code NetworkOnMainThreadException}). {@link #run} runs the task at once when the caller is not the main thread
 * and on one background thread, in the order of the calls, when it is. The threads of a connection are not affected.
 */
public final class CommandThread {

    private static final ExecutorService QUEUE = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "sendspin-commands");
        thread.setDaemon(true);
        return thread;
    });

    private CommandThread() {
    }

    /** True on the main thread of the app (never in a JVM test, where there is no looper). */
    public static boolean onMainThread() {
        Looper main = Looper.getMainLooper();
        return main != null && Looper.myLooper() == main;
    }

    /** Runs the task now, or, on the main thread, later on the command thread. */
    public static void run(Runnable task) {
        if (!onMainThread()) {
            task.run();
            return;
        }
        try {
            QUEUE.execute(task);
        } catch (RejectedExecutionException e) {
            // not expected: the queue is never shut down
        }
    }
}
