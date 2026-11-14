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
package de.yaacc.multiroom.discovery;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import de.yaacc.multiroom.control.MonotonicClock;
import de.yaacc.util.YaaccLogger;

/**
 * The Android side of the server discovery: browses the control service of a snapserver with {@link NsdManager} and gives
 * every resolved service to the {@link SnapcastDirectory}. Thin on purpose: what to accept (the address ranges, the
 * cap, the merge, the names as display text) is decided by the pure classes; the order of the resolutions by
 * {@link SnapcastResolveQueue}; which types are browsed by {@link SnapcastServiceTypes}. This class cannot be tested on
 * the JVM.
 *
 * <p><b>Types.</b> One {@code discoverServices} call per type ({@code NsdManager} takes one type per listener): the control
 * port is announced as {@code _snapcast-ctrl._tcp} by Snapcast 0.33 and newer and as {@code _snapcast-tcp._tcp} by 0.32 and
 * older, which browsing only the new name never found; the stream type {@code _snapcast._tcp} is browsed for the report
 * only (a server that announces its stream but not its control port can be told from silence) and is never resolved or
 * listed. Everything the callbacks report goes to the {@link SnapcastDiscoveryLog} for the report the user can read.
 *
 * <p><b>Lifetime.</b> Nothing runs until {@link #start()}, which the receivers page calls when it becomes visible, and
 * everything stops with {@link #stop()}: the discovery, the queue and the multicast lock. The lock is held only
 * between the two (the platform documents it for the legacy NSD path of older releases; it needs
 * {@code CHANGE_WIFI_MULTICAST_STATE}, which the app has for UPnP). No other permission is needed at the target API of
 * the app.
 *
 * <p><b>Trust.</b> The announcement is not authenticated and the service has no TXT records. The service name is
 * display text and (with the type) the key to match a "lost" event; the address comes from the resolution as a number, so no
 * name lookup happens here, and the connection later uses that {@link InetAddress}.
 */
public final class SnapcastNsd {

    private static final long RESOLVE_TIMEOUT_MILLIS = 10_000L;
    private static final int QUEUE_CAPACITY = 2 * SnapcastDirectory.MAX_SERVERS;

    /** One browse: the type, its listener and its stop handle. */
    private static final class Browse {
        final String type;
        final SnapcastBrowseHandle handle = new SnapcastBrowseHandle();
        NsdManager.DiscoveryListener listener;
        boolean failed;

        Browse(String type) {
            this.type = type;
        }
    }

    private final Context context;
    private final SnapcastDirectory directory;
    private final SnapcastDiscoveryLog log;
    private final SnapcastResolveQueue<NsdServiceInfo> queue;
    private final SnapcastResolveRetry retry = new SnapcastResolveRetry();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pump = this::pump;

    private NsdManager nsdManager;
    private WifiManager.MulticastLock multicastLock;
    private SnapcastDiscoveryReport.LockState lockState = SnapcastDiscoveryReport.LockState.NOT_HELD;
    private final List<Browse> browses = new ArrayList<>();
    private boolean started;
    private int generation;
    private int scanId;

    public SnapcastNsd(Context context, SnapcastDirectory directory, SnapcastDiscoveryLog log) {
        this.context = context.getApplicationContext();
        this.directory = directory;
        this.log = log;
        this.queue = new SnapcastResolveQueue<>(MonotonicClock.SYSTEM, RESOLVE_TIMEOUT_MILLIS, QUEUE_CAPACITY);
    }

    public synchronized boolean isStarted() {
        return started;
    }

    /** The state of the multicast lock, for the report. */
    public synchronized SnapcastDiscoveryReport.LockState lockState() {
        if (multicastLock != null) {
            try {
                return multicastLock.isHeld() ? SnapcastDiscoveryReport.LockState.HELD : SnapcastDiscoveryReport.LockState.NOT_HELD;
            } catch (RuntimeException unreadable) {
                return lockState;
            }
        }
        return lockState;
    }

    /** Starts browsing. Does nothing when it runs already. */
    public synchronized void start() {
        if (started) {
            return;
        }
        // first: even without a discovery service, a remembered server must get its grace period (nothing confirms it)
        directory.discoveryRestarted();
        log.scanStarted();
        scanId = log.scanId();
        retry.clear();
        nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        if (nsdManager == null) {
            log.record(SnapcastDiscoveryLog.Kind.BROWSE_REFUSED, "(no NsdManager)", "", SnapcastNsdErrors.PLATFORM_THREW, "");
            return;
        }
        acquireMulticastLock();
        final int myGeneration = ++generation;
        browses.clear();
        for (String type : SnapcastServiceTypes.browsed()) {
            Browse browse = new Browse(type);
            browse.listener = newListener(browse, myGeneration, scanId);
            browses.add(browse);
            log.record(SnapcastDiscoveryLog.Kind.BROWSE_REQUESTED, type, "", 0, "");
            try {
                nsdManager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, browse.listener);
            } catch (RuntimeException e) {
                // the platform refused this one (no network service, a permission): the other types and the manual servers still work
                YaaccLogger.w(SnapcastNsd.class.getName(), "Snapcast discovery could not be started: " + e.getClass().getSimpleName());
                browse.failed = true;
                browse.handle.onStartFailed();
                log.record(SnapcastDiscoveryLog.Kind.BROWSE_REFUSED, type, "", SnapcastNsdErrors.PLATFORM_THREW, "");
            }
        }
        if (allFailed()) {
            browses.clear();
            releaseMulticastLock();
            return;
        }
        started = true;
    }

    private boolean allFailed() {
        for (Browse browse : browses) {
            if (!browse.failed) {
                return false;
            }
        }
        return true;
    }

    private NsdManager.DiscoveryListener newListener(final Browse browse, final int myGeneration, final int myScan) {
        return new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                browse.handle.onStartFailed();
                log.record(myScan, SnapcastDiscoveryLog.Kind.BROWSE_START_FAILED, browse.type, "", errorCode, "");
                YaaccLogger.w(SnapcastNsd.class.getName(), "Snapcast discovery did not start, code " + errorCode);
                browseFailed(browse, myGeneration);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                log.record(myScan, SnapcastDiscoveryLog.Kind.BROWSE_STOP_FAILED, browse.type, "", errorCode, "");
                YaaccLogger.d(SnapcastNsd.class.getName(), "Snapcast discovery did not stop cleanly, code " + errorCode);
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
                log.record(myScan, SnapcastDiscoveryLog.Kind.BROWSE_STARTED, browse.type, "", 0, "");
                // the services arrive through onServiceFound; a stop that came before this is made now
                if (browse.handle.onStarted()) {
                    stopBrowse(browse.listener);
                }
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                log.record(myScan, SnapcastDiscoveryLog.Kind.BROWSE_STOPPED, browse.type, "", 0, "");
            }

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                if (info == null || !isCurrent(myGeneration)) {
                    return; // an earlier browse whose stop is still waiting for its confirmation
                }
                SnapcastServiceTypes.Role role = SnapcastServiceTypes.roleOf(info.getServiceType());
                if (role == null) {
                    log.record(myScan, SnapcastDiscoveryLog.Kind.FOUND_IGNORED, String.valueOf(info.getServiceType()), info.getServiceName(), 0, "");
                    return; // another type
                }
                log.record(myScan, SnapcastDiscoveryLog.Kind.FOUND, info.getServiceType(), info.getServiceName(), 0, "");
                if (!SnapcastServiceTypes.isListed(role)) {
                    return; // the stream service: counted for the report, never resolved
                }
                if (queue.offer(serviceId(info), info)) {
                    pump();
                } else {
                    log.record(myScan, SnapcastDiscoveryLog.Kind.QUEUE_FULL, info.getServiceType(), info.getServiceName(), 0, "");
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo info) {
                if (info != null && isCurrent(myGeneration)) {
                    log.record(myScan, SnapcastDiscoveryLog.Kind.LOST, String.valueOf(info.getServiceType()), info.getServiceName(), 0, "");
                    retry.lost(serviceId(info));
                    directory.serviceLost(serviceId(info));
                }
            }
        };
    }

    private static String serviceId(NsdServiceInfo info) {
        return SnapcastServiceTypes.serviceId(info.getServiceType(), info.getServiceName());
    }

    /** Stops browsing, drops the queue and releases the multicast lock. Does nothing when it is stopped. */
    public synchronized void stop() {
        if (!started) {
            return;
        }
        started = false;
        generation++;
        handler.removeCallbacksAndMessages(null); // the pump and the retries
        queue.clear();
        retry.clear();
        // an early stop (before the platform confirmed the start) is held back by the handle until onDiscoveryStarted
        if (nsdManager != null) {
            for (Browse browse : browses) {
                if (browse.listener != null && browse.handle.requestStop()) {
                    stopBrowse(browse.listener);
                }
            }
        }
        browses.clear();
        releaseMulticastLock();
    }

    private void stopBrowse(NsdManager.DiscoveryListener listener) {
        final NsdManager manager;
        synchronized (this) {
            manager = nsdManager;
        }
        if (manager == null) {
            return;
        }
        try {
            manager.stopServiceDiscovery(listener);
        } catch (IllegalArgumentException alreadyStopped) {
            // the listener was not registered (the start failed meanwhile)
        }
    }

    /** One browse could not start; the others go on, and when none is left the whole discovery is over. */
    private synchronized void browseFailed(Browse browse, int expectedGeneration) {
        if (expectedGeneration != generation || !started) {
            return;
        }
        browse.failed = true;
        if (allFailed()) {
            started = false;
            queue.clear();
            browses.clear();
            releaseMulticastLock();
        }
    }

    /** Starts the next resolution if none is running; called after every event that can free the queue. */
    @SuppressWarnings("deprecation") // resolveService is deprecated from API 34 but is the one call of all API levels
    private void pump() {
        final NsdManager manager;
        final int myGeneration;
        final int myScan;
        synchronized (this) {
            if (!started) {
                return;
            }
            manager = nsdManager;
            myGeneration = generation;
            myScan = scanId;
        }
        SnapcastResolveQueue.Pending<NsdServiceInfo> next = queue.next();
        if (next == null) {
            return;
        }
        final SnapcastResolveQueue.Pending<NsdServiceInfo> queued = next;
        final NsdServiceInfo asked = queued.getItem();
        try {
            manager.resolveService(asked, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                    log.record(myScan, SnapcastDiscoveryLog.Kind.RESOLVE_FAILED, asked.getServiceType(), asked.getServiceName(), errorCode, "");
                    queue.done(queued);
                    if (retry.shouldRetry(queued.getName(), errorCode)) {
                        // a busy platform or a failure that passes: the same service again after a short wait
                        handler.postDelayed(() -> {
                            if (retry.takePending(queued.getName()) && isCurrent(myGeneration) && queue.offer(queued.getName(), asked)) {
                                pump();
                            }
                        }, SnapcastResolveRetry.DELAY_MILLIS);
                    }
                    pump();
                }

                @Override
                public void onServiceResolved(NsdServiceInfo info) {
                    queue.done(queued);
                    retry.succeeded(queued.getName());
                    if (info != null && isCurrent(myGeneration)) {
                        InetAddress host = info.getHost(); // a number: no lookup
                        boolean listed = directory.serviceFound(queued.getName(), info.getServiceName(), host, info.getPort());
                        recordResolved(myScan, asked, host, info.getPort(), listed);
                    }
                    pump();
                }
            });
            // a resolution that is never answered is given up by the queue; look again then
            handler.removeCallbacks(pump);
            handler.postDelayed(pump, RESOLVE_TIMEOUT_MILLIS + 100);
        } catch (RuntimeException e) {
            queue.done(queued);
        }
    }

    private void recordResolved(int forScan, NsdServiceInfo asked, InetAddress host, int port, boolean listed) {
        SnapcastEndpoint endpoint = host == null ? null : SnapcastEndpoint.fromAddress(host, port);
        String where = endpoint != null ? endpoint.toString() : host != null ? host.getHostAddress() + ":" + port : "";
        SnapcastDiscoveryLog.Kind kind = host == null ? SnapcastDiscoveryLog.Kind.RESOLVED_EMPTY
                : listed ? SnapcastDiscoveryLog.Kind.RESOLVED : SnapcastDiscoveryLog.Kind.RESOLVED_REFUSED;
        log.record(forScan, kind, asked.getServiceType(), asked.getServiceName(), 0, where);
    }

    private synchronized boolean isCurrent(int expectedGeneration) {
        return started && expectedGeneration == generation;
    }

    private void acquireMulticastLock() {
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("yaacc-snapcast");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
                lockState = SnapcastDiscoveryReport.LockState.HELD;
            } else {
                lockState = SnapcastDiscoveryReport.LockState.UNAVAILABLE;
            }
        } catch (RuntimeException e) {
            multicastLock = null; // without the lock the discovery may still work
            lockState = SnapcastDiscoveryReport.LockState.FAILED;
        }
    }

    private void releaseMulticastLock() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
            }
        } catch (RuntimeException ignored) {
            // already released
        }
        multicastLock = null;
        lockState = SnapcastDiscoveryReport.LockState.NOT_HELD;
    }
}
