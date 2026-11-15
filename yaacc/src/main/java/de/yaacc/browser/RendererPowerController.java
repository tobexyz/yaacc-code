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
import android.os.SystemClock;

import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

import de.yaacc.util.YaaccLogger;

/**
 * Runs the tap on a power button off the main thread (task 12.4): one change per device at a time, taps within a moment of the last
 * one dropped ({@link RendererPowerGate}), the work is {@link RendererPowerToggle} (fresh read, change, read again). The card shows
 * the device as busy meanwhile (the rows are asked to rebind, the way the group operations do it) and again when it is over; the
 * outcome comes back on the main thread. Nothing of the device (name, address, answer) is logged.
 */
final class RendererPowerController {

    /** A device gets this long to change before it is read again. */
    static final long SETTLE_MILLIS = 800L;
    /** Taps closer than this to the end of the last change are dropped. */
    static final long MIN_GAP_MILLIS = 500L;

    private static RendererPowerController shared;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "renderer-power");
        thread.setDaemon(true);
        return thread;
    });
    private final RendererPowerGate gate = new RendererPowerGate(MIN_GAP_MILLIS);
    private final Object lock = new Object();
    private final java.util.Set<String> running = new java.util.HashSet<>(); // guarded by lock

    static synchronized RendererPowerController getShared() {
        if (shared == null) {
            shared = new RendererPowerController();
        }
        return shared;
    }

    private RendererPowerController() {
    }

    boolean isBusy(String key) {
        synchronized (lock) {
            return running.contains(key);
        }
    }

    /**
     * Main thread. The source is made on the worker (it may look a device up).
     *
     * @param key       the device (its UDN), also what the rows are rebound by
     * @param onOutcome main thread, after the change ended (also when it failed)
     */
    void toggle(final String key, final Supplier<RendererPowerToggle.Source> source, final Consumer<RendererPowerToggle.Outcome> onOutcome) {
        if (!gate.tryStart(key, SystemClock.elapsedRealtime())) {
            return;
        }
        synchronized (lock) {
            running.add(key);
        }
        rebind(key); // the card shows the device as busy
        try {
            executor.execute(() -> {
                RendererPowerToggle.Outcome outcome;
                try {
                    outcome = RendererPowerToggle.toggle(source.get(), Thread::sleep, SETTLE_MILLIS);
                } catch (RuntimeException unexpected) {
                    YaaccLogger.w(RendererPowerController.class.getName(), "A power change failed (" + unexpected.getClass().getSimpleName() + ")");
                    outcome = new RendererPowerToggle.Outcome(RendererPower.State.UNKNOWN, RendererPowerToggle.Error.UNREACHABLE);
                }
                final RendererPowerToggle.Outcome result = outcome;
                gate.finished(key, SystemClock.elapsedRealtime());
                main.post(() -> {
                    synchronized (lock) {
                        running.remove(key);
                    }
                    try {
                        onOutcome.accept(result);
                    } finally {
                        rebind(key);
                    }
                });
            });
        } catch (RejectedExecutionException shutDown) {
            gate.finished(key, SystemClock.elapsedRealtime());
            synchronized (lock) {
                running.remove(key);
            }
            rebind(key);
        }
    }

    private void rebind(String key) {
        YxcGroupController.getShared().requestRebind(Collections.singleton(key), 0);
    }
}
