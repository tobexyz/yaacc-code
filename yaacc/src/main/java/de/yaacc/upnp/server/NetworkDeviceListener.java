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
package de.yaacc.upnp.server;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import de.yaacc.R;
import de.yaacc.upnp.protocol.UpnpProtocolHandler;
import de.yaacc.upnp.registry.Registry;
import de.yaacc.upnp.server.http.HttpRequestSender;
import de.yaacc.upnp.server.udp.MulticastReceiver;
import de.yaacc.upnp.server.udp.UdpTransiver;
import de.yaacc.util.YaaccLogger;

public class NetworkDeviceListener {
    private final Context context;
    private final WifiManager wifiManager;
    private final Registry registry;
    private final YaaccUpnpServerService service;
    private HttpRequestSender httpRequestSender;
    private WifiManager.MulticastLock multicastLock;
    private WifiManager.WifiLock wifiLock;
    private boolean isAppInForeground = true;
    private Runnable wifiLockChangeListener;

    private Network currentNetwork;
    private MulticastReceiver multicastReceiver;

    private UdpTransiver udpTransiver;
    private UpnpProtocolHandler upnpProtocolHandler;

    private final Object lock = new Object();
    private boolean isHotspotEnabled = false;
    private BroadcastReceiver networkStateReceiver;

    private boolean isHotspotEnabled() {
        try {
            WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            java.lang.reflect.Method method = wifiManager.getClass().getDeclaredMethod("isWifiApEnabled");
            return (Boolean) method.invoke(wifiManager);
        } catch (Exception e) {
            YaaccLogger.w(getClass().getName(), "Failed to check hotspot state", e);
            return false;
        }
    }

    public NetworkDeviceListener(Context context, Registry registry, YaaccUpnpServerService service) throws IllegalStateException {
        this.service = service;
        this.context = context;
        this.registry = registry;
        this.wifiManager = ((WifiManager) context.getSystemService(Context.WIFI_SERVICE));
        // Check hotspot state on startup
        isHotspotEnabled = isHotspotEnabled();
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (!isCellular()) {
            currentNetwork = connectivityManager.getActiveNetwork();

        }
        if (currentNetwork != null || isWifiOrHotspot()) {
            multicastReceiver = new MulticastReceiver();
            udpTransiver = new UdpTransiver();
            httpRequestSender = new HttpRequestSender();
            enable();
        }
        connectivityManager.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                super.onAvailable(network);
                if (!isCellular() && !network.equals(currentNetwork)) {
                    YaaccLogger.d(getClass().getName(), String.format("Network available %s", network));
                    if (currentNetwork != null) {
                        disable();
                        YaaccLogger.d(getClass().getName(), String.format("Network disabled %s", currentNetwork));
                    }
                    currentNetwork = network;
                    if (multicastReceiver == null) {
                        multicastReceiver = new MulticastReceiver();
                    }
                    if (udpTransiver == null) {
                        udpTransiver = new UdpTransiver();
                    }
                    if (httpRequestSender == null) {
                        httpRequestSender = new HttpRequestSender();
                    }
                    enable();
                    YaaccLogger.d(getClass().getName(), String.format("Network enabled %s", currentNetwork));
                }

            }

            @Override
            public void onLost(@NonNull Network network) {
                super.onLost(network);
                if (network.equals(currentNetwork)) {
                    YaaccLogger.d(getClass().getName(), String.format("Network lost %s", network));
                    disable();
                    YaaccLogger.d(getClass().getName(), String.format("Network disabled %s", currentNetwork));
                    currentNetwork = null;
                }
            }
        });

        // Register for WiFi and access point state changes
        networkStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                    YaaccLogger.d(getClass().getName(), "WiFi state changed");
                    onNetworkStateChange();
                } else if ("android.net.wifi.WIFI_AP_STATE_CHANGED".equals(action)) {
                    int apState = intent.getIntExtra("wifi_state", 0);
                    isHotspotEnabled = (apState == 13); // WIFI_AP_STATE_ENABLED
                    YaaccLogger.d(getClass().getName(), "Access point state changed: " + (isHotspotEnabled ? "enabled" : "disabled"));
                    onNetworkStateChange();
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction("android.net.wifi.WIFI_AP_STATE_CHANGED");
        context.registerReceiver(networkStateReceiver, filter);
    }

    public void enable() {
        synchronized (lock) {
            YaaccLogger.v(getClass().getName(), "in android router enable");

            // Enable multicast on the WiFi network interface,
            // requires android.permission.CHANGE_WIFI_MULTICAST_STATE
            if (isWifiOrHotspot()) {
                setWiFiMulticastLock(true);
                setWifiLock(true);
            }
            if (multicastReceiver == null) multicastReceiver = new MulticastReceiver();
            if (udpTransiver == null) udpTransiver = new UdpTransiver();
            if (httpRequestSender == null) httpRequestSender = new HttpRequestSender();
            upnpProtocolHandler = new UpnpProtocolHandler(context, registry, udpTransiver, multicastReceiver, httpRequestSender);
            multicastReceiver.init(context, upnpProtocolHandler);
            multicastReceiver.execute();
            udpTransiver.init(context, upnpProtocolHandler);
            udpTransiver.execute();
            service.updateNotification();
        }
    }

    public void disable() {
        synchronized (lock) {
            YaaccLogger.v(getClass().getName(), "in android router disable");
            // Disable multicast on WiFi network interface,
            // requires android.permission.CHANGE_WIFI_MULTICAST_STATE
            if (isWifiOrHotspot()) {
                setWiFiMulticastLock(false);
                setWifiLock(false);
            }
            if (upnpProtocolHandler != null) {
                upnpProtocolHandler = null;
            }
            if (multicastReceiver != null) {
                multicastReceiver.cancel();
                multicastReceiver = null;
            }
            if (udpTransiver != null) {
                udpTransiver.cancel();
                udpTransiver = null;
            }
            service.updateNotification();
        }
    }

    private boolean isWifi() {
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager.getNetworkCapabilities(connectivityManager.getActiveNetwork()) == null) {
            return false;
        }
        return connectivityManager.getNetworkCapabilities(connectivityManager.getActiveNetwork()).hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    private boolean isWifiOrHotspot() {
        return isWifi() || isHotspotEnabled;
    }

    private boolean isCellular() {
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager.getNetworkCapabilities(connectivityManager.getActiveNetwork()) == null) {
            return false;
        }
        return connectivityManager.getNetworkCapabilities(connectivityManager.getActiveNetwork()).hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
    }

    protected void setWiFiMulticastLock(boolean enable) {
        if (multicastLock == null) {
            multicastLock = wifiManager.createMulticastLock(getClass().getSimpleName());
        }

        if (enable) {
            if (multicastLock.isHeld()) {
                YaaccLogger.w(getClass().getName(), "WiFi multicast lock already acquired");
            } else {
                YaaccLogger.d(getClass().getName(), "WiFi multicast lock acquired");
                multicastLock.acquire();
            }
        } else {
            if (multicastLock.isHeld()) {
                YaaccLogger.d(getClass().getName(), "WiFi multicast lock released");
                multicastLock.release();
            } else {
                YaaccLogger.w(getClass().getName(), "WiFi multicast lock already released");
            }
        }
    }

    protected void setWifiLock(boolean enable) {
        if (wifiLock == null) {
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, getClass().getSimpleName());
        }

        // Smart WiFi lock: only hold if needed
        boolean shouldHold = enable && shouldHoldWifiLock();

        if (shouldHold) {
            if (wifiLock.isHeld()) {
                YaaccLogger.w(getClass().getName(), "WiFi lock already acquired");
            } else {
                YaaccLogger.d(getClass().getName(), "WiFi lock acquired");
                wifiLock.acquire();
            }
        } else {
            if (wifiLock.isHeld()) {
                YaaccLogger.d(getClass().getName(), "WiFi lock released");
                wifiLock.release();
            } else {
                YaaccLogger.w(getClass().getName(), "WiFi lock already released");
            }
        }
    }

    public boolean isWifiLockHeld() {
        return wifiLock != null && wifiLock.isHeld();
    }

    private boolean shouldHoldWifiLock() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        // Hold lock if server enabled
        boolean serverEnabled = prefs.getBoolean(context.getString(R.string.settings_local_server_chkbx), false);

        // Hold lock if renderer enabled
        boolean rendererEnabled = prefs.getBoolean(context.getString(R.string.settings_local_server_receiver_chkbx), false);

        // Hold lock if app in foreground (for discovery)
        boolean needsForDiscovery = isAppInForeground;

        boolean shouldHold = serverEnabled || rendererEnabled || needsForDiscovery;

        YaaccLogger.d(getClass().getName(),
                "WiFi lock decision: server=" + serverEnabled +
                        ", renderer=" + rendererEnabled +
                        ", foreground=" + isAppInForeground +
                        " -> " + (shouldHold ? "HOLD" : "RELEASE"));

        return shouldHold;
    }

    public void setAppInForeground(boolean inForeground) {
        if (this.isAppInForeground != inForeground) {
            YaaccLogger.d(getClass().getName(), "App foreground state changed: " + inForeground);
            this.isAppInForeground = inForeground;
            // Update WiFi lock based on new state
            if (isWifi()) {
                setWifiLock(true); // Will check shouldHoldWifiLock internally
            }
        }
    }

    public UdpTransiver getUdpTransiver() {
        return udpTransiver;
    }

    public HttpRequestSender getHttpRequestSender() {
        return httpRequestSender;
    }

    public UpnpProtocolHandler getUpnpProtocolHandler() {
        return upnpProtocolHandler;
    }

    public boolean isInitalized() {
        return udpTransiver != null && multicastReceiver != null && upnpProtocolHandler != null;
    }

    private void onNetworkStateChange() {
        YaaccLogger.d(getClass().getName(), "Network state change detected");
        synchronized (lock) {
            if (isWifiOrHotspot()) {
                if (!isInitalized()) {
                    YaaccLogger.d(getClass().getName(), "Reinitializing network components");
                    if (currentNetwork == null) {
                        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                        currentNetwork = connectivityManager.getActiveNetwork();
                    }
                    if (currentNetwork != null) {
                        if (multicastReceiver == null) multicastReceiver = new MulticastReceiver();
                        if (udpTransiver == null) udpTransiver = new UdpTransiver();
                        if (httpRequestSender == null) httpRequestSender = new HttpRequestSender();
                        enable();
                        // Notify service to restart UPnP device
                        if (service != null) {
                            service.onNetworkStateChange();
                        }
                    }
                }
            } else {
                YaaccLogger.d(getClass().getName(), "Not on WiFi or hotspot, disabling");
                disable();
                // Notify service to stop UPnP device
                if (service != null) {
                    service.onNetworkStateChange();
                }
            }
        }
    }

    public void cleanup() {
        if (networkStateReceiver != null) {
            try {
                context.unregisterReceiver(networkStateReceiver);
            } catch (IllegalArgumentException e) {
                YaaccLogger.w(getClass().getName(), "Receiver not registered");
            }
        }
    }
}
