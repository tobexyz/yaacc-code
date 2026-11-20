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
package de.yaacc.fcast.discovery;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.net.InetAddress;

import de.yaacc.multiroom.control.MonotonicClock;
import de.yaacc.multiroom.discovery.SnapcastBrowseHandle;
import de.yaacc.multiroom.discovery.SnapcastResolveQueue;
import de.yaacc.multiroom.discovery.SnapcastResolveRetry;
import de.yaacc.util.YaaccLogger;

/**
 * The Android side of the receiver discovery: browses {@value FCastServiceType#TYPE} with {@link NsdManager} and gives every resolved
 * service to the {@link FCastDirectory}. Thin on purpose: what to accept (address ranges, ports, caps, names) is decided by the
 * directory, the order of the resolutions by {@link SnapcastResolveQueue} and which failures are retried by
 * {@link SnapcastResolveRetry} (both of the Snapcast discovery, used as they are, decision D15). This class can not be tested on
 * the JVM; everything it does is written to the {@link FCastDiscoveryLog} for the report the user can read.
 *
 * <p><b>Lifetime.</b> Nothing runs until {@link #start()}, which the receivers page calls (through the gate) when it becomes visible;
 * {@link #stop()} ends the browse, the queue and the multicast lock, which is held only between the two. No permission beyond what the
 * app has for UPnP ({@code CHANGE_WIFI_MULTICAST_STATE}) is needed.
 *
 * <p><b>Trust.</b> An announcement is not authenticated: the name is display text, the address comes from the resolution as a number
 * (no name lookup here), the TXT attributes are hints.
 */
public final class FCastNsd {
    private static final long RESOLVE_TIMEOUT_MILLIS = 10_000L;
    private static final int QUEUE_CAPACITY = 2 * FCastDirectory.MAX_DISCOVERED;

    private final Context context;
    private final FCastDirectory directory;
    private final FCastDiscoveryLog log;
    private final SnapcastResolveQueue<NsdServiceInfo> queue;
    private final SnapcastResolveRetry retry = new SnapcastResolveRetry();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pump = this::pump;

    private NsdManager nsdManager;
    private WifiManager.MulticastLock multicastLock;
    private FCastDiscoveryReport.LockState lockState = FCastDiscoveryReport.LockState.NOT_HELD;
    private SnapcastBrowseHandle handle;
    private NsdManager.DiscoveryListener listener;
    private boolean started;
    private int generation;
    private int scanId;

    public FCastNsd(Context context, FCastDirectory directory, FCastDiscoveryLog log) {
        this.context = context.getApplicationContext();
        this.directory = directory;
        this.log = log;
        this.queue = new SnapcastResolveQueue<>(MonotonicClock.SYSTEM, RESOLVE_TIMEOUT_MILLIS, QUEUE_CAPACITY);
    }

    public synchronized boolean isStarted() {
        return started;
    }

    /** The state of the multicast lock, for the report. */
    public synchronized FCastDiscoveryReport.LockState lockState() {
        if (multicastLock != null) {
            try {
                return multicastLock.isHeld() ? FCastDiscoveryReport.LockState.HELD : FCastDiscoveryReport.LockState.NOT_HELD;
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
        directory.discoveryRestarted();
        log.scanStarted();
        scanId = log.scanId();
        retry.clear();
        nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        if (nsdManager == null) {
            log.record(scanId, FCastDiscoveryLog.Kind.BROWSE_REFUSED, "(no NsdManager)", 0, "");
            return;
        }
        acquireMulticastLock();
        final int myGeneration = ++generation;
        handle = new SnapcastBrowseHandle();
        listener = newListener(handle, myGeneration, scanId);
        log.record(scanId, FCastDiscoveryLog.Kind.BROWSE_REQUESTED, FCastServiceType.TYPE, 0, "");
        try {
            nsdManager.discoverServices(FCastServiceType.TYPE, NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (RuntimeException e) {
            // the platform refused (no network service, a permission): the manual receivers still work
            YaaccLogger.w(FCastNsd.class.getName(), "FCast discovery could not be started: " + e.getClass().getSimpleName());
            handle.onStartFailed();
            log.record(scanId, FCastDiscoveryLog.Kind.BROWSE_REFUSED, FCastServiceType.TYPE, 100, e.getClass().getSimpleName());
            releaseMulticastLock();
            return;
        }
        started = true;
    }

    private NsdManager.DiscoveryListener newListener(final SnapcastBrowseHandle browse, final int myGeneration, final int myScan) {
        return new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                browse.onStartFailed();
                log.record(myScan, FCastDiscoveryLog.Kind.BROWSE_START_FAILED, FCastServiceType.TYPE, errorCode, "");
                YaaccLogger.w(FCastNsd.class.getName(), "FCast discovery did not start, code " + errorCode);
                browseFailed(myGeneration);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                log.record(myScan, FCastDiscoveryLog.Kind.BROWSE_STOP_FAILED, FCastServiceType.TYPE, errorCode, "");
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
                log.record(myScan, FCastDiscoveryLog.Kind.BROWSE_STARTED, FCastServiceType.TYPE, 0, "");
                if (browse.onStarted()) {
                    stopBrowse(this); // a stop that came before the platform confirmed the start
                }
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                log.record(myScan, FCastDiscoveryLog.Kind.BROWSE_STOPPED, FCastServiceType.TYPE, 0, "");
            }

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                if (info == null || !isCurrent(myGeneration)) {
                    return;
                }
                if (!FCastServiceType.isFCast(info.getServiceType())) {
                    log.record(myScan, FCastDiscoveryLog.Kind.FOUND_IGNORED, String.valueOf(info.getServiceType()), 0, "");
                    return;
                }
                log.record(myScan, FCastDiscoveryLog.Kind.FOUND, info.getServiceName(), 0, "");
                if (queue.offer(serviceId(info), info)) {
                    pump();
                } else {
                    log.record(myScan, FCastDiscoveryLog.Kind.QUEUE_FULL, info.getServiceName(), 0, "");
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo info) {
                if (info != null && isCurrent(myGeneration)) {
                    log.record(myScan, FCastDiscoveryLog.Kind.LOST, info.getServiceName(), 0, "");
                    retry.lost(serviceId(info));
                    directory.serviceLost(serviceId(info));
                }
            }
        };
    }

    private static String serviceId(NsdServiceInfo info) {
        return FCastServiceType.serviceId(info.getServiceType(), info.getServiceName());
    }

    /** Stops browsing, drops the queue and releases the multicast lock. Does nothing when it is stopped. */
    public synchronized void stop() {
        if (!started) {
            return;
        }
        started = false;
        generation++;
        handler.removeCallbacksAndMessages(null);
        queue.clear();
        retry.clear();
        if (nsdManager != null && listener != null && handle != null && handle.requestStop()) {
            stopBrowse(listener);
        }
        listener = null;
        handle = null;
        releaseMulticastLock();
    }

    private void stopBrowse(NsdManager.DiscoveryListener toStop) {
        final NsdManager manager;
        synchronized (this) {
            manager = nsdManager;
        }
        if (manager == null) {
            return;
        }
        try {
            manager.stopServiceDiscovery(toStop);
        } catch (IllegalArgumentException alreadyStopped) {
            // the listener was not registered (the start failed meanwhile)
        }
    }

    private synchronized void browseFailed(int expectedGeneration) {
        if (expectedGeneration != generation || !started) {
            return;
        }
        started = false;
        queue.clear();
        releaseMulticastLock();
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
        final SnapcastResolveQueue.Pending<NsdServiceInfo> queued = queue.next();
        if (queued == null) {
            return;
        }
        final NsdServiceInfo asked = queued.getItem();
        try {
            manager.resolveService(asked, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                    log.record(myScan, FCastDiscoveryLog.Kind.RESOLVE_FAILED, asked.getServiceName(), errorCode, "");
                    queue.done(queued);
                    if (retry.shouldRetry(queued.getName(), errorCode)) {
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
                        boolean listed = directory.serviceFound(queued.getName(), info.getServiceName(), host, info.getPort(),
                                FCastServiceTxt.parse(info.getAttributes()));
                        FCastEndpoint endpoint = FCastEndpoint.fromAddress(host, info.getPort());
                        FCastDiscoveryLog.Kind kind = host == null ? FCastDiscoveryLog.Kind.RESOLVED_EMPTY
                                : listed ? FCastDiscoveryLog.Kind.RESOLVED : FCastDiscoveryLog.Kind.RESOLVED_REFUSED;
                        log.record(myScan, kind, asked.getServiceName(), 0, endpoint != null ? endpoint.toString()
                                : host != null ? host.getHostAddress() + ":" + info.getPort() : "");
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

    private synchronized boolean isCurrent(int expectedGeneration) {
        return started && expectedGeneration == generation;
    }

    private void acquireMulticastLock() {
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("yaacc-fcast");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
                lockState = FCastDiscoveryReport.LockState.HELD;
            } else {
                lockState = FCastDiscoveryReport.LockState.UNAVAILABLE;
            }
        } catch (RuntimeException e) {
            multicastLock = null; // without the lock the discovery may still work
            lockState = FCastDiscoveryReport.LockState.FAILED;
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
        lockState = FCastDiscoveryReport.LockState.NOT_HELD;
    }
}
