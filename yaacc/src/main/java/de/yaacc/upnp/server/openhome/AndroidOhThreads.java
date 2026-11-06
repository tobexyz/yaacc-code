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
package de.yaacc.upnp.server.openhome;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import de.yaacc.util.YaaccLogger;

/**
 * The threads of the OpenHome renderer on Android: one worker thread that does the renderer's work one task after the other, and
 * the main thread for the player (media3 players insist on it). The worker waits for the main thread, never the other way round.
 */
public final class AndroidOhThreads implements OpenHomeRenderer.Threads {

    private static final long PLAYER_TIMEOUT_SECONDS = 5;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "openhome-renderer");
        thread.setDaemon(true);
        return thread;
    });

    private static Runnable guarded(Runnable work) {
        return () -> {
            try {
                work.run();
            } catch (RuntimeException e) {
                // a failing task must not end the worker or the poll
                YaaccLogger.w(AndroidOhThreads.class.getName(), "OpenHome renderer task failed", e);
            }
        };
    }

    @Override
    public void work(Runnable work) {
        try {
            worker.execute(guarded(work));
        } catch (RejectedExecutionException e) {
            YaaccLogger.d(getClass().getName(), "OpenHome renderer is shut down, work dropped");
        }
    }

    @Override
    public void runLater(Runnable runnable, long delayMillis) {
        try {
            worker.schedule(guarded(runnable), delayMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            YaaccLogger.d(getClass().getName(), "OpenHome renderer is shut down, timer dropped");
        }
    }

    @Override
    public Runnable every(Runnable work, long periodMillis) {
        ScheduledFuture<?> future = worker.scheduleWithFixedDelay(guarded(work), periodMillis, periodMillis, TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    @Override
    public <T> T onPlayer(Callable<T> call) {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                return call.call();
            }
            FutureTask<T> task = new FutureTask<>(call);
            main.post(task);
            return task.get(PLAYER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException("player call failed", e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("player call timed out", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("player call interrupted", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("player call failed", e);
        }
    }

    @Override
    public void close() {
        worker.shutdown();
    }
}
