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
package de.yaacc.multiroom;

import android.content.Context;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import de.yaacc.R;
import de.yaacc.multiroom.control.MonotonicClock;
import de.yaacc.multiroom.control.SnapcastRpc;
import de.yaacc.multiroom.discovery.SnapcastDirectory;
import de.yaacc.multiroom.discovery.SnapcastManualServers;
import de.yaacc.multiroom.discovery.SnapcastNsd;
import de.yaacc.multiroom.discovery.SnapcastPreferencesStorage;
import de.yaacc.multiroom.discovery.SnapcastResolver;

/**
 * The app-wide Snapcast part, the counterpart of {@code YxcMonitor.getShared()}: the directory of servers, the manual
 * server list, the mDNS discovery, and the receivers that join them. It holds the application context only, and it
 * does nothing until a page tells it to: {@link #pageStarted()} (the receivers page is visible) starts the discovery
 * (and with it the multicast lock) and opens the connections to the listed servers; {@link #pageStopped()} stops the
 * discovery at once and closes the connections after a grace period.
 *
 * <p>This is the thin Android glue; every decision is in the classes it wires together, which are tested on the JVM.
 */
public final class SnapcastHub implements SnapcastGroupSource {

    /** How long the connections stay open after the last page was hidden. */
    static final long CLOSE_GRACE_MILLIS = 30_000L;

    private static SnapcastHub shared;

    private final Context context;
    private final SnapcastDirectory directory = new SnapcastDirectory(MonotonicClock.SYSTEM, SnapcastDirectory.DEFAULT_GRACE_MILLIS);
    private final SnapcastManualServers manualServers;
    private final SnapcastSelectedServers selectedServers;
    private final SnapcastNsd nsd;
    private final SnapcastReceivers receivers;
    private int pages;

    private SnapcastHub(Context context) {
        this.context = context.getApplicationContext();
        this.manualServers = new SnapcastManualServers(new SnapcastPreferencesStorage(this.context));
        this.selectedServers = new SnapcastSelectedServers(
                new SnapcastPreferencesStorage(this.context, SnapcastPreferencesStorage.SELECTED_KEY), directory);
        this.nsd = new SnapcastNsd(this.context, directory);
        final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "snapcast-hub");
            thread.setDaemon(true);
            return thread;
        });
        final SnapcastResolver resolver = SnapcastResolver.system();
        this.receivers = new SnapcastReceivers(directory,
                endpoint -> new SnapcastSession(endpoint.toKey(), new ResolvingTransport(endpoint, resolver),
                        new SnapcastRpc.Config(), MonotonicClock.SYSTEM, null),
                (task, delayMillis) -> {
                    try {
                        timer.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
                    } catch (RejectedExecutionException closed) {
                        // not expected: the hub lives as long as the process
                    }
                },
                () -> new SnapcastGroupDevice.Labels(this.context.getString(R.string.snapcast_group),
                        this.context.getString(R.string.snapcast_offline)),
                CLOSE_GRACE_MILLIS);
        directory.setManualServers(manualServers.list());
        // the servers of the selected groups are known at once, without a scan (see SnapcastSelectedServers)
        selectedServers.restore();
    }

    /** The app-wide hub. */
    public static synchronized SnapcastHub getShared(Context context) {
        if (shared == null) {
            shared = new SnapcastHub(context);
        }
        return shared;
    }

    /** The manual servers (for the settings): the list is read again at every page start. */
    public SnapcastManualServers getManualServers() {
        return manualServers;
    }

    /** The receivers page is visible: discover, and connect to the listed servers. */
    public void pageStarted() {
        boolean first;
        synchronized (this) {
            first = ++pages == 1;
        }
        directory.setManualServers(manualServers.list());
        if (first) {
            nsd.start();
        }
        receivers.pageStarted();
    }

    /** A receivers page was hidden: the discovery stops with the last one, the connections close after the grace. */
    public void pageStopped() {
        boolean last;
        synchronized (this) {
            if (pages == 0) {
                return;
            }
            last = --pages == 0;
        }
        if (last) {
            nsd.stop();
        }
        receivers.pageStopped();
    }

    public void addChangeListener(SnapcastReceivers.ChangeListener listener) {
        receivers.addChangeListener(listener);
    }

    public void removeChangeListener(SnapcastReceivers.ChangeListener listener) {
        receivers.removeChangeListener(listener);
    }

    /** The receivers, for the code that controls a server (the player of a later group). */
    public SnapcastReceivers getReceivers() {
        return receivers;
    }

    @Override
    public List<SnapcastGroupDevice> groups() {
        return receivers.groups();
    }

    @Override
    public SnapcastGroupDevice findGroup(String receiverId) {
        return receivers.findGroup(receiverId);
    }

    @Override
    public boolean isPending(String receiverId) {
        return receivers.isPending(receiverId);
    }

    /** Remembers the servers of the selected groups, so that the selection survives a restart. */
    @Override
    public void selectionChanged(Collection<String> receiverIds) {
        selectedServers.selectionChanged(receiverIds);
    }

    /** Connects to the listed servers of the chosen groups. Does not start the discovery (that is the page's). */
    @Override
    public void wake(Collection<String> receiverIds) {
        directory.setManualServers(manualServers.list());
        receivers.wake(receiverIds);
    }
}
