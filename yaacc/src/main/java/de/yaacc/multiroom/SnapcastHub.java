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
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import de.yaacc.R;
import de.yaacc.multiroom.control.MonotonicClock;
import de.yaacc.multiroom.control.RetryScheduler;
import de.yaacc.multiroom.control.SnapcastRpc;
import de.yaacc.multiroom.discovery.SnapcastDirectory;
import de.yaacc.multiroom.discovery.SnapcastDiscoveryLog;
import de.yaacc.multiroom.discovery.SnapcastDiscoveryReport;
import de.yaacc.multiroom.discovery.SnapcastManualServers;
import de.yaacc.multiroom.discovery.SnapcastNsd;
import de.yaacc.multiroom.discovery.SnapcastPreferencesStorage;
import de.yaacc.multiroom.discovery.SnapcastResolver;
import de.yaacc.multiroom.feed.PcmFeederOutput;
import de.yaacc.multiroom.feed.SnapcastFeedController;
import de.yaacc.multiroom.ui.SnapcastServerListModel;

/**
 * The app-wide Snapcast part, the counterpart of {@code YxcMonitor.getShared()}: the directory of servers, the manual
 * server list, the mDNS discovery, and the receivers that join them. It holds the application context only, and it
 * does nothing until a page tells it to: {@link #pageStarted()} (the receivers page is visible) opens the connections to
 * the listed servers and, <b>only when the setting {@link SnapcastSettings#KEY_DISCOVERY} is on</b> (default off), starts
 * the discovery (and with it the multicast lock); {@link #pageStopped()} stops the discovery at once and closes the
 * connections after a grace period. The setting is read live ({@link SnapcastDiscoveryGate}): switching it off stops the
 * scan and drops the servers that only the scan knew, so with it off only the typed-in and the remembered servers are
 * used.
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
    private final SnapcastDiscoveryLog discoveryLog = new SnapcastDiscoveryLog(MonotonicClock.SYSTEM);
    private final SnapcastNsd nsd;
    private final SnapcastReceivers receivers;
    private final SnapcastFeedSessions feeds;
    private final SnapcastDiscoveryGate gate;
    // kept in a field: the preferences hold their listeners weakly
    private final SharedPreferences.OnSharedPreferenceChangeListener settingListener;

    private SnapcastHub(Context context) {
        this.context = context.getApplicationContext();
        this.manualServers = new SnapcastManualServers(new SnapcastPreferencesStorage(this.context));
        this.selectedServers = new SnapcastSelectedServers(
                new SnapcastPreferencesStorage(this.context, SnapcastPreferencesStorage.SELECTED_KEY), directory);
        this.nsd = new SnapcastNsd(this.context, directory, discoveryLog);
        final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "snapcast-hub");
            thread.setDaemon(true);
            return thread;
        });
        final SnapcastResolver resolver = SnapcastResolver.system();
        final RetryScheduler scheduler = (task, delayMillis) -> {
            try {
                timer.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException closed) {
                // not expected: the hub lives as long as the process
            }
        };
        this.receivers = new SnapcastReceivers(directory,
                endpoint -> new SnapcastSession(endpoint.toKey(), new ResolvingTransport(endpoint, resolver),
                        new SnapcastRpc.Config(), MonotonicClock.SYSTEM, null),
                scheduler,
                () -> new SnapcastGroupDevice.Labels(this.context.getString(R.string.snapcast_group),
                        this.context.getString(R.string.snapcast_offline)),
                CLOSE_GRACE_MILLIS);
        this.feeds = new SnapcastFeedSessions(receivers, SnapcastMarkerStore.forContext(this.context), scheduler,
                PcmFeederOutput.FACTORY);
        directory.setManualServers(manualServers.list());
        // the servers of the selected groups are known at once, without a scan (see SnapcastSelectedServers)
        selectedServers.restore();
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this.context);
        this.gate = new SnapcastDiscoveryGate(() -> SnapcastSettings.isDiscoveryEnabled(preferences::getBoolean),
                new SnapcastDiscoveryGate.Discovery() {
                    @Override
                    public void start() {
                        nsd.start();
                    }

                    @Override
                    public void stop() {
                        nsd.stop();
                    }
                }, directory::discoveryStopped);
        this.settingListener = (changed, key) -> {
            if (SnapcastSettings.isDiscoveryKey(key)) {
                gate.settingChanged();
            }
        };
        preferences.registerOnSharedPreferenceChangeListener(settingListener);
    }

    /**
     * The server list of the settings: typed-in servers (validated strictly, at most 16), the ones the network or a
     * selected group brought, and "remove", which also clears the remembered flag and the selected groups of the server.
     */
    public SnapcastServerListModel newServerListModel() {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        final String key = context.getString(R.string.settings_selected_receivers_title);
        SnapcastServerAdmin admin = new SnapcastServerAdmin(manualServers, directory, selectedServers,
                new SnapcastServerAdmin.SelectionStore() {
                    @Override
                    public Set<String> read() {
                        Set<String> stored = preferences.getStringSet(key, null);
                        return stored == null ? new HashSet<String>() : new HashSet<>(stored);
                    }

                    @Override
                    public void write(Set<String> receiverIds) {
                        preferences.edit().putStringSet(key, new HashSet<>(receiverIds)).apply();
                    }
                });
        return new SnapcastServerListModel(manualServers, directory, admin);
    }

    /**
     * The controller that plays into a group (task 4.4). The name of this phone in the stream name is the one the UPnP
     * server announces (the same preference and default as {@code YaaccUpnpServerService.getLocalServerName}), so
     * nothing new about the phone is disclosed to the Snapcast server and its controllers. The caller must
     * {@link SnapcastFeedController#release() release} it when its player ends.
     */
    public SnapcastFeedController newFeedController(SnapcastGroupDevice group, SnapcastFeedController.Listener listener) {
        return feeds.newController(group, phoneName(), listener);
    }

    private String phoneName() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        return preferences.getString(context.getString(R.string.settings_local_server_name_key), "Yaacc");
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

    /** The receivers page is visible: connect to the listed servers, and discover if the setting is on. */
    public void pageStarted() {
        directory.setManualServers(manualServers.list());
        gate.pageStarted();
        receivers.pageStarted();
    }

    /** A receivers page was hidden: the discovery stops with the last one, the connections close after the grace. */
    public void pageStopped() {
        gate.pageStopped();
        receivers.pageStopped();
    }

    /**
     * The Snapcast server settings are open (task 11.6): with the switch on the scan runs while they are, so that the report
     * shows a live scan. Only the scan, not the connections of a receivers page. Pair with {@link #diagnosticsClosed()}.
     */
    public void diagnosticsOpened() {
        gate.pageStarted();
    }

    public void diagnosticsClosed() {
        gate.pageStopped();
    }

    /**
     * The discovery report for the screen and the clipboard (see {@link SnapcastDiscoveryReport}). It names the servers
     * and addresses found: the caller says so near the copy button.
     */
    public String discoveryReport() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        List<SnapcastDiscoveryReport.ServerLine> servers = new java.util.ArrayList<>();
        for (SnapcastDirectory.Entry entry : directory.servers()) {
            SnapcastDiscoveryReport.Origin origin = entry.isManual() ? SnapcastDiscoveryReport.Origin.TYPED
                    : entry.isRemembered() && !entry.isDiscovered() && !entry.isLost() ? SnapcastDiscoveryReport.Origin.REMEMBERED
                    : SnapcastDiscoveryReport.Origin.FOUND;
            servers.add(new SnapcastDiscoveryReport.ServerLine(entry.getKey().asString(), entry.getDisplayName(), origin));
        }
        SnapcastDiscoveryReport.Facts facts = new SnapcastDiscoveryReport.Facts()
                .device(android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + " / API " + android.os.Build.VERSION.SDK_INT)
                .switchOn(SnapcastSettings.isDiscoveryEnabled(preferences::getBoolean))
                .scanRunning(nsd.isStarted())
                .wifi(wifiState())
                .multicastPermission(multicastPermission())
                .multicastLock(nsd.lockState())
                .servers(servers);
        return SnapcastDiscoveryReport.of(facts, discoveryLog.snapshot());
    }

    private SnapcastDiscoveryReport.Tri multicastPermission() {
        try {
            return context.checkSelfPermission(android.Manifest.permission.CHANGE_WIFI_MULTICAST_STATE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED
                    ? SnapcastDiscoveryReport.Tri.YES : SnapcastDiscoveryReport.Tri.NO;
        } catch (RuntimeException unreadable) {
            return SnapcastDiscoveryReport.Tri.UNKNOWN;
        }
    }

    /** Whether the default network is Wi-Fi (or Ethernet, which also carries mDNS); needs ACCESS_NETWORK_STATE, which the app has. */
    private SnapcastDiscoveryReport.Tri wifiState() {
        try {
            android.net.ConnectivityManager manager = (android.net.ConnectivityManager) context
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) {
                return SnapcastDiscoveryReport.Tri.UNKNOWN;
            }
            android.net.Network network = manager.getActiveNetwork();
            android.net.NetworkCapabilities capabilities = network == null ? null : manager.getNetworkCapabilities(network);
            if (capabilities == null) {
                return SnapcastDiscoveryReport.Tri.NO;
            }
            if (capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                    || capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) {
                return SnapcastDiscoveryReport.Tri.YES;
            }
            // a VPN hides what carries it: not a reason to say "no Wi-Fi"
            return capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
                    ? SnapcastDiscoveryReport.Tri.UNKNOWN : SnapcastDiscoveryReport.Tri.NO;
        } catch (RuntimeException unreadable) {
            return SnapcastDiscoveryReport.Tri.UNKNOWN;
        }
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
