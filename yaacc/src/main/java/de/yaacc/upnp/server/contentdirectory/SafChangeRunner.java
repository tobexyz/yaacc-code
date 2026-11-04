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
package de.yaacc.upnp.server.contentdirectory;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import androidx.preference.PreferenceManager;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import de.yaacc.R;
import de.yaacc.util.YaaccLogger;

/**
 * The thin Android part of the SAF change check (issue #299), owned by {@code YaaccUpnpServerService}: one daemon
 * thread that runs a {@link SafChangeMonitor} pass about every 30 seconds while the local server serves at least
 * one SAF folder, and nothing at all (no thread, no query) otherwise.
 * <p>
 * {@link #update()} starts or stops it according to the preferences and is cheap, so the service calls it whenever
 * a relevant preference or the server changed. The bump of the SystemUpdateID runs on the check's own thread, never
 * on the main thread or a Browse thread.
 *
 * @author Tobias Schoene (tobexyz)
 */
public final class SafChangeRunner {

    /** The first pass comes soon after the start, so the folders have a baseline before anything changes. */
    static final long FIRST_PASS_DELAY_MILLIS = 10_000L;

    // the monitor Browse threads report to; null while the check is not running
    private static volatile SafChangeMonitor shared;

    private final Context context;
    private final BooleanSupplier bump;
    private final Object lock = new Object();
    // all guarded by lock
    private ScheduledThreadPoolExecutor executor;
    private SafChangeMonitor monitor;

    /**
     * @param bump raises the SystemUpdateID and sends the event to the subscribers; false if there is no
     *             ContentDirectory to do that. Called on the check's thread.
     */
    public SafChangeRunner(Context context, BooleanSupplier bump) {
        this.context = context.getApplicationContext();
        this.bump = bump;
    }

    /** A Browse got the listing of a folder (see {@link SafChangeMonitor#onBrowse}); nothing happens if the check is not running. */
    static void onBrowse(String folder, SafDirectory listing, boolean fromCache) {
        SafChangeMonitor current = shared;
        if (current != null) {
            current.onBrowse(folder, listing, fromCache);
        }
    }

    /** Starts or stops the check as the preferences say: runs while the server serves a selected SAF folder. */
    public void update() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        boolean watch = SafChangeMonitor.shouldWatch(
                preferences.getBoolean(context.getString(R.string.settings_local_server_chkbx), false),
                preferences.getBoolean(context.getString(R.string.settings_local_server_provider_chkbx), false),
                preferences.getBoolean(context.getString(R.string.settings_local_server_serve_saf_chkbx), false),
                MediaPathFilter.getSelectedSafPathes(context).size());
        if (watch) {
            start();
        } else {
            stop();
        }
    }

    /** The shared SAF folders changed: what was seen so far is forgotten (the bump for that is done by the caller). */
    public void pathsChanged() {
        synchronized (lock) {
            if (monitor != null) {
                monitor.reset();
            }
        }
        update();
    }

    /** Stops the thread; a pass that is under way ends without a bump. */
    public void stop() {
        synchronized (lock) {
            if (executor == null) {
                return;
            }
            if (shared == monitor) {
                shared = null;
            }
            executor.shutdownNow();
            executor = null;
            monitor = null;
        }
        YaaccLogger.d(getClass().getName(), "SAF change check stopped");
    }

    private void start() {
        synchronized (lock) {
            if (executor != null) {
                return;
            }
            ScheduledThreadPoolExecutor owner = new ScheduledThreadPoolExecutor(1, runnable -> {
                Thread thread = new Thread(runnable, "saf-change-check");
                thread.setDaemon(true);
                return thread;
            });
            owner.setRemoveOnCancelPolicy(true);
            SafChangeMonitor created = new SafChangeMonitor(
                    folder -> SafDirectoryLister.refresh(context, folder).value,
                    () -> isCurrent(owner) && bump.getAsBoolean(),
                    delayMillis -> scheduleFlush(owner, delayMillis),
                    SystemClock::elapsedRealtime);
            executor = owner;
            monitor = created;
            shared = created;
            schedulePass(owner, created, FIRST_PASS_DELAY_MILLIS);
        }
        YaaccLogger.d(getClass().getName(), "SAF change check started");
    }

    private boolean isCurrent(ScheduledThreadPoolExecutor owner) {
        synchronized (lock) {
            return executor == owner;
        }
    }

    // caller holds the lock or runs on the owner's thread
    private void schedulePass(ScheduledThreadPoolExecutor owner, SafChangeMonitor owningMonitor, long delayMillis) {
        try {
            owner.schedule(() -> pass(owner, owningMonitor), delayMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // stopped meanwhile
        }
    }

    private void scheduleFlush(ScheduledThreadPoolExecutor owner, long delayMillis) {
        try {
            owner.schedule(() -> {
                try {
                    SafChangeMonitor current = currentMonitor(owner);
                    if (current != null) {
                        current.flush();
                    }
                } catch (RuntimeException e) {
                    YaaccLogger.w(getClass().getName(), "SAF change flush failed", e);
                }
            }, delayMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // stopped meanwhile
        }
    }

    // null once the owner was stopped
    private SafChangeMonitor currentMonitor(ScheduledThreadPoolExecutor owner) {
        synchronized (lock) {
            return executor == owner ? monitor : null;
        }
    }

    private void pass(ScheduledThreadPoolExecutor owner, SafChangeMonitor owningMonitor) {
        long start = SystemClock.elapsedRealtime();
        try {
            if (isCurrent(owner)) {
                owningMonitor.runPass(MediaPathFilter.getSelectedSafPathes(context));
            }
        } catch (RuntimeException e) {
            // the next pass tries again; an exception must not end the schedule
            YaaccLogger.w(getClass().getName(), "SAF change pass failed", e);
        } finally {
            if (isCurrent(owner)) {
                schedulePass(owner, owningMonitor, SafChangeMonitor.pauseAfterPass(SystemClock.elapsedRealtime() - start));
            }
        }
    }
}
