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

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs a call on another thread and waits for it. A call that did not run in time is cancelled, so it does not run later when
 * the caller has already given up. Plain JVM.
 */
final class OhCall {

    private OhCall() {
    }

    /** @throws IllegalStateException when the call failed, timed out or the wait was interrupted */
    static <T> T runAndWait(Executor executor, Callable<T> call, long timeoutSeconds) {
        FutureTask<T> task = new FutureTask<>(call);
        try {
            executor.execute(task);
            return task.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException("player call failed", e.getCause());
        } catch (TimeoutException e) {
            task.cancel(false);
            throw new IllegalStateException("player call timed out", e);
        } catch (InterruptedException e) {
            task.cancel(false);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("player call interrupted", e);
        } catch (RuntimeException e) {
            task.cancel(false);
            throw e;
        }
    }
}
