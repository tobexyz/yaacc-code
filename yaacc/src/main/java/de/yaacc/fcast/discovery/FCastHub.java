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
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;

import androidx.preference.PreferenceManager;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.yaacc.R;
import de.yaacc.fcast.FCastAppSessions;
import de.yaacc.fcast.FCastReceiverLinks;
import de.yaacc.multiroom.SnapcastDiscoveryGate;
import de.yaacc.multiroom.control.MonotonicClock;
import de.yaacc.multiroom.discovery.SnapcastEndpoint;
import de.yaacc.multiroom.discovery.SnapcastPreferencesStorage;
import de.yaacc.multiroom.discovery.SnapcastResolver;

/**
 * The app-wide FCast part, the counterpart of the Snapcast hub: the directory of receivers, the receivers added by hand, the
 * remembered selection, the service discovery and the gate that runs it only while a receivers page is visible and the switch is
 * on. It does nothing until a page tells it to ({@link #pageStarted()}): no thread, no socket, no multicast lock.
 *
 * <p>The page hooks are connected in task 5.2; until then nothing is browsed and, with nothing added by hand, no FCast receiver is
 * in any list.
 */
public final class FCastHub implements FCastReceiverSource {
    private static final long GRACE_MILLIS = FCastDirectory.DEFAULT_GRACE_MILLIS;

    private static FCastHub shared;

    private final Context context;
    private final FCastDirectory directory = new FCastDirectory(MonotonicClock.SYSTEM, GRACE_MILLIS);
    private final FCastDiscoveryLog discoveryLog = new FCastDiscoveryLog();
    private final FCastNsd nsd;
    private final FCastStoredReceivers manualReceivers;
    private final FCastPins pins;
    private final FCastReceivers receivers;
    private final SnapcastDiscoveryGate gate;
    private final SharedPreferences.OnSharedPreferenceChangeListener settingListener;

    private FCastHub(Context context) {
        this.context = context.getApplicationContext();
        this.manualReceivers = new FCastStoredReceivers(new SnapcastPreferencesStorage(this.context, FCastSettings.KEY_MANUAL_RECEIVERS),
                FCastStoredReceivers.MAX_MANUAL);
        this.pins = new FCastPins(new FCastPinStore(new SnapcastPreferencesStorage(this.context, FCastSettings.KEY_RECEIVER_PINS)),
                directory::find);
        FCastStoredReceivers remembered = new FCastStoredReceivers(
                new SnapcastPreferencesStorage(this.context, FCastSettings.KEY_REMEMBERED_RECEIVERS), FCastStoredReceivers.MAX_REMEMBERED);
        this.nsd = new FCastNsd(this.context, directory, discoveryLog);
        this.receivers = new FCastReceivers(directory, () -> new FCastReceiverDevice.Labels(this.context.getString(R.string.fcast_offline)), remembered);
        directory.setManual(manualReceivers.list());
        receivers.restore();
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this.context);
        this.gate = new SnapcastDiscoveryGate(() -> FCastSettings.isDiscoveryEnabled(preferences::getBoolean),
                new SnapcastDiscoveryGate.Discovery() {
                    @Override
                    public void start() {
                        nsd.start();
                    }

                    @Override
                    public void stop() {
                        nsd.stop();
                        directory.discoveryStopped();
                    }
                }, directory::clearDiscovered);
        this.settingListener = (changed, key) -> {
            if (FCastSettings.isDiscoveryKey(key)) {
                gate.settingChanged();
            }
        };
        preferences.registerOnSharedPreferenceChangeListener(settingListener);
    }

    /** The one hub of the process. */
    public static synchronized FCastHub getShared(Context context) {
        if (shared == null) {
            shared = new FCastHub(context);
        }
        return shared;
    }

    /** A receivers page became visible: the scan starts if the switch is on. */
    public void pageStarted() {
        gate.pageStarted();
    }

    /** A receivers page was hidden: the scan stops when no page needs it. */
    public void pageStopped() {
        gate.pageStopped();
    }

    /** For a window that wants the scan while it is open (the receivers list of the settings): give the lease back with {@code close()}. */
    public SnapcastDiscoveryGate.Lease diagnosticsOpened() {
        return gate.pageLease();
    }

    public void addChangeListener(FCastDirectory.Listener listener) {
        directory.addListener(listener);
    }

    public void removeChangeListener(FCastDirectory.Listener listener) {
        directory.removeListener(listener);
    }

    /** The receivers typed in, pasted or scanned (tasks 5.4 to 5.7). */
    public List<FCastDirectory.Known> manualReceivers() {
        return manualReceivers.list();
    }

    /** Adds a receiver by hand; the list of receivers is updated at once. */
    public FCastStoredReceivers.AddResult addManual(FCastEndpoint endpoint, String name) {
        synchronized (manualReceivers) { // the stored list and the directory must see the same order of changes
            FCastStoredReceivers.AddResult result = manualReceivers.add(endpoint, name);
            if (result == FCastStoredReceivers.AddResult.ADDED) {
                directory.setManual(manualReceivers.list());
            }
            return result;
        }
    }

    /** Adds a receiver from a link and keeps the key the link carried (task 7.2). */
    public FCastStoredReceivers.AddResult addManual(FCastEndpoint endpoint, String name, de.yaacc.fcast.protocol.FCastPin pin) {
        synchronized (manualReceivers) {
            return pins.remember(endpoint, pin, addManual(endpoint, name));
        }
    }

    /** The key a connection to this receiver is pinned to, or null if it is not known to offer the secure protocol (it is then spoken to in v3). */
    public de.yaacc.fcast.protocol.FCastPin pinFor(FCastEndpoint endpoint) {
        return pins.pinFor(endpoint);
    }

    /** Removes a receiver that was added by hand. @return true if it was in the list */
    public boolean removeManual(FCastEndpoint endpoint) {
        synchronized (manualReceivers) {
            boolean removed = manualReceivers.remove(endpoint);
            if (removed) {
                directory.setManual(manualReceivers.list());
            }
            pins.forget(endpoint);
            return removed;
        }
    }

    /** The report of the discovery for the user to copy: what was browsed, what the system said, the receivers known. */
    public String discoveryReport() {
        boolean enabled = FCastSettings.isDiscoveryEnabled(PreferenceManager.getDefaultSharedPreferences(context)::getBoolean);
        FCastDiscoveryReport.Facts facts = new FCastDiscoveryReport.Facts(enabled, nsd.isStarted(), isWifiConnected(), nsd.lockState(),
                Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE);
        return FCastDiscoveryReport.build(facts, directory.snapshot(), discoveryLog.snapshot());
    }

    private Boolean isWifiConnected() {
        try {
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) {
                return null;
            }
            Network network = manager.getActiveNetwork();
            NetworkCapabilities capabilities = network == null ? null : manager.getNetworkCapabilities(network);
            return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /** The receivers as the directory knows them (found, typed in, remembered), for the list of the settings. */
    public List<FCastDirectory.Entry> entries() {
        return directory.snapshot();
    }

    private static final int PROBE_TIMEOUT_MILLIS = 2_000;

    private FCastReceiverLinks links;

    /**
     * The sessions that the screens open on demand (the volume of an idle card, the settings sheet); one per receiver, closed a short
     * time after the last use, and closed at once when a player takes the receiver.
     */
    public synchronized FCastReceiverLinks links() {
        if (links == null) {
            final java.util.concurrent.ScheduledExecutorService executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "fcast-links");
                thread.setDaemon(true);
                return thread;
            });
            links = new FCastReceiverLinks((endpoint, listener, ex) ->
                    FCastAppSessions.create(endpoint, listener, ex, de.yaacc.BuildConfig.VERSION_NAME, this::pinFor), executor);
        }
        return links;
    }

    /** The input "Add receiver" with the network of the app behind it (task 5.4). Its calls block: run them on a worker. */
    public FCastAddReceiver newAddReceiver() {
        final SnapcastResolver resolver = SnapcastResolver.system();
        return new FCastAddReceiver(new FCastAddReceiver.Environment() {
            @Override
            public List<FCastDirectory.Entry> receivers() {
                return directory.snapshot();
            }

            @Override
            public boolean answers(FCastEndpoint endpoint) {
                InetAddress address = endpoint.getAddress();
                if (address == null) {
                    return false;
                }
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(address, endpoint.getPort()), PROBE_TIMEOUT_MILLIS);
                    return true;
                } catch (IOException | RuntimeException unreachable) {
                    return false;
                }
            }

            @Override
            public List<FCastSubnet> localSubnets() {
                List<FCastSubnet> subnets = new java.util.ArrayList<>();
                try {
                    // the interface the app uses (its setting, else the first usable one): a receiver on the same network is reached through it
                    de.yaacc.util.InterfaceResolutionHelper.InterfaceHolder holder = de.yaacc.util.InterfaceResolutionHelper.getNetworkInterface(context);
                    if (holder != null && holder.networkInterface != null) {
                        for (java.net.InterfaceAddress address : holder.networkInterface.getInterfaceAddresses()) {
                            if (address.getAddress() != null && !address.getAddress().isLoopbackAddress()) {
                                subnets.add(new FCastSubnet(address.getAddress(), address.getNetworkPrefixLength()));
                            }
                        }
                    }
                } catch (RuntimeException unreadable) {
                    // no networks known: the addresses are tried in the order of the link
                }
                return subnets;
            }

            @Override
            public List<Boolean> answersAll(List<FCastEndpoint> endpoints) {
                // all at the same time: the answer takes as long as the slowest timeout, not the sum of them
                final List<Boolean> results = new java.util.ArrayList<>();
                java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
                        Math.max(1, Math.min(endpoints.size(), 16)), runnable -> {
                            Thread thread = new Thread(runnable, "fcast-probe");
                            thread.setDaemon(true);
                            return thread;
                        });
                try {
                    List<java.util.concurrent.Future<Boolean>> futures = new java.util.ArrayList<>();
                    for (final FCastEndpoint endpoint : endpoints) {
                        futures.add(pool.submit(() -> answers(endpoint)));
                    }
                    for (java.util.concurrent.Future<Boolean> future : futures) {
                        try {
                            results.add(future.get(PROBE_TIMEOUT_MILLIS + 1_000L, java.util.concurrent.TimeUnit.MILLISECONDS));
                        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failed) {
                            results.add(false);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            results.add(false);
                        }
                    }
                } finally {
                    pool.shutdownNow();
                }
                return results;
            }

            @Override
            public InetAddress lookup(String host) throws IOException {
                SnapcastEndpoint endpoint = SnapcastEndpoint.parse(host, FCastEndpoint.DEFAULT_PORT);
                if (endpoint == null) {
                    throw new IOException("not a name");
                }
                return resolver.resolve(endpoint);
            }

            @Override
            public FCastStoredReceivers.AddResult store(FCastEndpoint endpoint, String name) {
                return addManual(endpoint, name);
            }

            @Override
            public FCastStoredReceivers.AddResult store(FCastEndpoint endpoint, String name, de.yaacc.fcast.protocol.FCastPin pin) {
                return addManual(endpoint, name, pin);
            }

            @Override
            public boolean isDiscoveryRunning() {
                return nsd.isStarted();
            }

            @Override
            public void pause(long millis) {
                try {
                    Thread.sleep(millis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    /** Removes a receiver that was added by hand, and takes it out of the stored selection (task 5.4). Blocking: run it on a worker. */
    public FCastReceiverAdmin.Result removeReceiver(FCastEndpoint endpoint) {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        final String key = context.getString(R.string.settings_selected_receivers_title);
        FCastReceiverAdmin admin = new FCastReceiverAdmin(this::removeManual, new FCastReceiverAdmin.SelectionStore() {
            @Override
            public Set<String> read() {
                Set<String> stored = preferences.getStringSet(key, null);
                return stored == null ? new HashSet<String>() : new HashSet<>(stored);
            }

            @Override
            public void write(Set<String> receiverIds) {
                preferences.edit().putStringSet(key, new HashSet<>(receiverIds)).apply();
            }
        }, this::selectionChanged);
        return admin.remove(endpoint);
    }

    @Override
    public List<FCastReceiverDevice> receivers() {
        return receivers.receivers();
    }

    @Override
    public FCastReceiverDevice findReceiver(String receiverId) {
        return receivers.findReceiver(receiverId);
    }

    @Override
    public void selectionChanged(Collection<String> receiverIds) {
        receivers.selectionChanged(receiverIds);
    }
}
