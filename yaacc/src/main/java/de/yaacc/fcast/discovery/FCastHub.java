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

import java.util.Collection;
import java.util.List;

import de.yaacc.R;
import de.yaacc.multiroom.SnapcastDiscoveryGate;
import de.yaacc.multiroom.control.MonotonicClock;
import de.yaacc.multiroom.discovery.SnapcastPreferencesStorage;

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
    private final FCastReceivers receivers;
    private final SnapcastDiscoveryGate gate;
    private final SharedPreferences.OnSharedPreferenceChangeListener settingListener;

    private FCastHub(Context context) {
        this.context = context.getApplicationContext();
        this.manualReceivers = new FCastStoredReceivers(new SnapcastPreferencesStorage(this.context, FCastSettings.KEY_MANUAL_RECEIVERS),
                FCastStoredReceivers.MAX_MANUAL);
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

    /** Removes a receiver that was added by hand. @return true if it was in the list */
    public boolean removeManual(FCastEndpoint endpoint) {
        synchronized (manualReceivers) {
            boolean removed = manualReceivers.remove(endpoint);
            if (removed) {
                directory.setManual(manualReceivers.list());
            }
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
