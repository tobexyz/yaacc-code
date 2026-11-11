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

import de.yaacc.multiroom.control.MonotonicClock;
import de.yaacc.util.YaaccLogger;

/**
 * The Android side of the server discovery: browses {@code _snapcast-ctrl._tcp} with {@link NsdManager} and gives
 * every resolved service to the {@link SnapcastDirectory}. Thin on purpose: what to accept (the address ranges, the
 * cap, the merge, the names as display text) is decided by the pure classes; the order of the resolutions by
 * {@link SnapcastResolveQueue}. This class cannot be tested on the JVM.
 *
 * <p><b>Lifetime.</b> Nothing runs until {@link #start()}, which the receivers page calls when it becomes visible, and
 * everything stops with {@link #stop()}: the discovery, the queue and the multicast lock. The lock is held only
 * between the two (the platform documents it for the legacy NSD path of older releases; it needs
 * {@code CHANGE_WIFI_MULTICAST_STATE}, which the app has for UPnP). No other permission is needed at the target API of
 * the app.
 *
 * <p><b>Trust.</b> The announcement is not authenticated and the service has no TXT records. The service name is
 * display text and the key to match a "lost" event; the address comes from the resolution as a number, so no name
 * lookup happens here, and the connection later uses that {@link InetAddress}.
 */
public final class SnapcastNsd {

    /** The service type of the TCP control port of a snapserver. */
    static final String SERVICE_TYPE = "_snapcast-ctrl._tcp";
    private static final long RESOLVE_TIMEOUT_MILLIS = 10_000L;
    private static final int QUEUE_CAPACITY = 2 * SnapcastDirectory.MAX_SERVERS;

    private final Context context;
    private final SnapcastDirectory directory;
    private final SnapcastResolveQueue<NsdServiceInfo> queue;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pump = this::pump;

    private NsdManager nsdManager;
    private WifiManager.MulticastLock multicastLock;
    private NsdManager.DiscoveryListener discoveryListener;
    private SnapcastBrowseHandle browseHandle;
    private boolean started;
    private int generation;

    public SnapcastNsd(Context context, SnapcastDirectory directory) {
        this.context = context.getApplicationContext();
        this.directory = directory;
        this.queue = new SnapcastResolveQueue<>(MonotonicClock.SYSTEM, RESOLVE_TIMEOUT_MILLIS, QUEUE_CAPACITY);
    }

    public synchronized boolean isStarted() {
        return started;
    }

    /** Starts browsing. Does nothing when it runs already. */
    public synchronized void start() {
        if (started) {
            return;
        }
        // first: even without a discovery service, a remembered server must get its grace period (nothing confirms it)
        directory.discoveryRestarted();
        nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        if (nsdManager == null) {
            return;
        }
        acquireMulticastLock();
        final int myGeneration = ++generation;
        final SnapcastBrowseHandle handle = new SnapcastBrowseHandle();
        browseHandle = handle;
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                handle.onStartFailed();
                YaaccLogger.w(SnapcastNsd.class.getName(), "Snapcast discovery did not start, code " + errorCode);
                stopIfCurrent(myGeneration);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                YaaccLogger.d(SnapcastNsd.class.getName(), "Snapcast discovery did not stop cleanly, code " + errorCode);
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
                // the services arrive through onServiceFound; a stop that came before this is made now
                if (handle.onStarted()) {
                    stopBrowse(this);
                }
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                // nothing to do
            }

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                if (info == null || info.getServiceType() == null || !info.getServiceType().startsWith(SERVICE_TYPE)
                        || !isCurrent(myGeneration)) {
                    return; // another type, or an earlier browse whose stop is still waiting for its confirmation
                }
                if (queue.offer(info.getServiceName(), info)) {
                    pump();
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo info) {
                if (info != null && isCurrent(myGeneration)) {
                    directory.serviceLost(info.getServiceName());
                }
            }
        };
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
            started = true;
        } catch (RuntimeException e) {
            // the platform refused (no network service, a permission): manual servers still work
            YaaccLogger.w(SnapcastNsd.class.getName(), "Snapcast discovery could not be started: " + e.getClass().getSimpleName());
            releaseMulticastLock();
            discoveryListener = null;
            browseHandle = null;
        }
    }

    /** Stops browsing, drops the queue and releases the multicast lock. Does nothing when it is stopped. */
    public synchronized void stop() {
        if (!started) {
            return;
        }
        started = false;
        generation++;
        handler.removeCallbacks(pump);
        queue.clear();
        // an early stop (before the platform confirmed the start) is held back by the handle until onDiscoveryStarted
        if (nsdManager != null && discoveryListener != null && browseHandle != null && browseHandle.requestStop()) {
            stopBrowse(discoveryListener);
        }
        discoveryListener = null;
        browseHandle = null;
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

    private synchronized void stopIfCurrent(int expectedGeneration) {
        if (expectedGeneration == generation && started) {
            started = false;
            queue.clear();
            discoveryListener = null;
            browseHandle = null;
            releaseMulticastLock();
        }
    }

    /** Starts the next resolution if none is running; called after every event that can free the queue. */
    @SuppressWarnings("deprecation") // resolveService is deprecated from API 34 but is the one call of all API levels
    private void pump() {
        final NsdManager manager;
        final int myGeneration;
        synchronized (this) {
            if (!started) {
                return;
            }
            manager = nsdManager;
            myGeneration = generation;
        }
        SnapcastResolveQueue.Pending<NsdServiceInfo> next = queue.next();
        if (next == null) {
            return;
        }
        final SnapcastResolveQueue.Pending<NsdServiceInfo> queued = next;
        try {
            manager.resolveService(next.getItem(), new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                    queue.done(queued);
                    pump();
                }

                @Override
                public void onServiceResolved(NsdServiceInfo info) {
                    queue.done(queued);
                    if (info != null && isCurrent(myGeneration)) {
                        InetAddress host = info.getHost(); // a number: no lookup
                        directory.serviceFound(info.getServiceName(), host, info.getPort());
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
                multicastLock = wifi.createMulticastLock("yaacc-snapcast");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (RuntimeException e) {
            multicastLock = null; // without the lock the discovery may still work
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
    }
}
