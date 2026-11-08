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

import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.yaacc.util.YaaccLogger;
import de.yaacc.yxc.YxcClient;
import de.yaacc.yxc.YxcMonitor;
import de.yaacc.yxc.YxcSourceActions;
import de.yaacc.yxc.YxcSourceControls;

/**
 * Runs one control of a device's own source (play, stop, next, repeat, seek, ...) off the main thread. Taps on a
 * device while one of its controls is on its way, and taps within {@link #MIN_GAP_MILLIS} of the last one, are dropped:
 * a seek or a next that is sent twice would skip a station. After a control the monitor is asked to read the device
 * again, now and a little later (a seek scans for a while).
 */
final class YxcSourceController {

    /** Taps closer than this to the last accepted one on the same device are dropped. */
    static final long MIN_GAP_MILLIS = 300L;
    private static final long LATER_READ_MILLIS = 1500L;
    private static final long SCAN_POLL_MILLIS = 1000L;
    /** A scan of the whole band takes well under a minute. */
    private static final int MAX_SCAN_POLLS = 60;

    private static YxcSourceController shared;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "yxc-source");
        thread.setDaemon(true);
        return thread;
    });
    private final Object lock = new Object();
    private final Set<String> inFlight = new HashSet<>(); // guarded by lock
    private final Map<String, Long> lastAccepted = new HashMap<>(); // guarded by lock

    static synchronized YxcSourceController getShared() {
        if (shared == null) {
            shared = new YxcSourceController();
        }
        return shared;
    }

    private YxcSourceController() {
    }

    /** Main thread. */
    void perform(String udn, YxcSourceControls.Control control) {
        long now = android.os.SystemClock.elapsedRealtime();
        synchronized (lock) {
            Long last = lastAccepted.get(udn);
            if (inFlight.contains(udn) || (last != null && now - last < MIN_GAP_MILLIS)) {
                return;
            }
            inFlight.add(udn);
            lastAccepted.put(udn, now);
        }
        try {
            executor.execute(() -> {
                try {
                    run(udn, control);
                } finally {
                    synchronized (lock) {
                        inFlight.remove(udn);
                    }
                }
            });
        } catch (RuntimeException e) {
            synchronized (lock) {
                inFlight.remove(udn);
            }
        }
    }

    private void run(String udn, YxcSourceControls.Control control) {
        YxcMonitor monitor = YxcMonitor.getShared();
        YxcClient client = monitor.newClient(udn);
        if (client == null) {
            return;
        }
        de.yaacc.yxc.YxcDeviceState known = monitor.getState(udn);
        YxcSourceActions.Result result = YxcSourceActions.perform(client, control,
                known == null ? null : known.getCapabilities());
        if (result == YxcSourceActions.Result.FAILED) {
            YaaccLogger.w(getClass().getName(), "A control of a device's own source failed");
        }
        // what the device did is read back now and again a little later (the events of the tuner are not used)
        monitor.refreshNow(udn);
        main.postDelayed(() -> monitor.refreshNow(udn), LATER_READ_MILLIS);
        if (control == YxcSourceControls.Control.SEEK_DOWN || control == YxcSourceControls.Control.SEEK_UP) {
            // a scan goes on by itself for a while: follow it, once a second, until it stops
            main.post(() -> pollWhileScanning(udn, 0));
        }
    }

    /** Main thread: reads the play info again every second while the tuner scans, at most {@link #MAX_SCAN_POLLS} times. */
    private void pollWhileScanning(String udn, int attempt) {
        main.postDelayed(() -> {
            YxcMonitor monitor = YxcMonitor.getShared();
            monitor.refreshPlayInfoNow(udn);
            de.yaacc.yxc.YxcDeviceState state = monitor.getState(udn);
            boolean scanning = state != null && state.getTuner() != null && state.getTuner().isScanning();
            // the first reads may still show the state from before the scan began
            if (attempt < MAX_SCAN_POLLS && (scanning || attempt < 2)) {
                pollWhileScanning(udn, attempt + 1);
            }
        }, SCAN_POLL_MILLIS);
    }
}
