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
package de.yaacc.browser;

import android.os.Handler;
import android.os.Looper;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.yaacc.yxc.YxcGroupOperations;
import de.yaacc.yxc.YxcGroupResult;
import de.yaacc.yxc.YxcMonitor;
import de.yaacc.yxc.YxcMonitorEnvironment;

/**
 * Runs the group operations of the sheet off the main thread, one at a time, and tells the screens which devices are
 * busy so their rows can show it. The results come back on the main thread.
 */
final class YxcGroupController {

    /** Told on the main thread when devices become busy or free again. */
    interface BusyListener {
        void onBusyChanged(Set<String> changedUdns);
    }

    /** Told on the main thread when an operation ended. */
    interface Callback {
        void onResult(YxcGroupResult result);
    }

    private static YxcGroupController shared;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "yxc-group");
        thread.setDaemon(true);
        return thread;
    });
    private final CopyOnWriteArrayList<BusyListener> listeners = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();
    private Set<String> busy = new HashSet<>(); // guarded by lock
    private final YxcGroupOperations operations;

    static synchronized YxcGroupController getShared() {
        if (shared == null) {
            shared = new YxcGroupController();
        }
        return shared;
    }

    private YxcGroupController() {
        operations = new YxcGroupOperations(new YxcMonitorEnvironment(YxcMonitor.getShared(), this::setBusy),
                millis -> Thread.sleep(millis));
    }

    boolean isBusy(String udn) {
        synchronized (lock) {
            return busy.contains(udn);
        }
    }

    void addBusyListener(BusyListener listener) {
        listeners.addIfAbsent(listener);
    }

    void removeBusyListener(BusyListener listener) {
        listeners.remove(listener);
    }

    /** Asks the screens to rebind the rows of these devices (called on any thread; delivered on the main thread). */
    void requestRebind(Set<String> udns, long delayMillis) {
        Set<String> copy = new HashSet<>(udns);
        main.postDelayed(() -> {
            for (BusyListener listener : listeners) {
                listener.onBusyChanged(copy);
            }
        }, delayMillis);
    }

    void applySelection(String serverUdn, Collection<String> selectedClientUdns, Callback callback) {
        Set<String> selection = new HashSet<>(selectedClientUdns);
        run(() -> operations.applySelection(serverUdn, selection), callback);
    }

    private interface Job {
        YxcGroupResult run();
    }

    private void run(Job job, Callback callback) {
        executor.execute(() -> {
            YxcGroupResult result;
            try {
                result = job.run();
            } catch (RuntimeException e) {
                result = YxcGroupResult.failed(YxcGroupResult.Step.VALIDATE, null, "unexpected failure");
            }
            YxcGroupResult finalResult = result;
            main.post(() -> callback.onResult(finalResult));
        });
    }

    private void setBusy(Set<String> now) {
        Set<String> changed = new HashSet<>();
        synchronized (lock) {
            changed.addAll(busy);
            changed.addAll(now);
            busy = new HashSet<>(now);
        }
        main.post(() -> {
            List<BusyListener> targets = listeners;
            for (BusyListener listener : targets) {
                listener.onBusyChanged(changed);
            }
        });
    }
}
