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

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import de.yaacc.util.YaaccLogger;

import androidx.annotation.NonNull;

import de.yaacc.upnp.protocol.UpnpProtocolHandler;
import de.yaacc.upnp.registry.Registry;
import de.yaacc.upnp.server.http.HttpRequestSender;
import de.yaacc.upnp.server.udp.MulticastReceiver;
import de.yaacc.upnp.server.udp.UdpTransiver;

public class NetworkDeviceListener {
    private final Context context;
    private final WifiManager wifiManager;
    private final Registry registry;
    private HttpRequestSender httpRequestSender;
    private WifiManager.MulticastLock multicastLock;
    private WifiManager.WifiLock wifiLock;

    private Network currentNetwork;
    private MulticastReceiver multicastReceiver;

    private UdpTransiver udpTransiver;
    private UpnpProtocolHandler upnpProtocolHandler;


    public NetworkDeviceListener(Context context, Registry registry) throws IllegalStateException {
        this.context = context;
        this.registry = registry;
        this.wifiManager = ((WifiManager) context.getSystemService(Context.WIFI_SERVICE));
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (!isCellular()) {
            currentNetwork = connectivityManager.getActiveNetwork();

        }
        if (currentNetwork != null) {
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
    }

    public void enable() {
        YaaccLogger.v(getClass().getName(), "in android router enable");

        // Enable multicast on the WiFi network interface,
        // requires android.permission.CHANGE_WIFI_MULTICAST_STATE
        if (isWifi()) {
            setWiFiMulticastLock(true);
            setWifiLock(true);
        }
        upnpProtocolHandler = new UpnpProtocolHandler(context, registry, udpTransiver, multicastReceiver, httpRequestSender);
        multicastReceiver.init(context, upnpProtocolHandler);
        multicastReceiver.execute();
        udpTransiver.init(context, upnpProtocolHandler);
        udpTransiver.execute();
    }

    public void disable() {
        YaaccLogger.v(getClass().getName(), "in android router disable");
        // Disable multicast on WiFi network interface,
        // requires android.permission.CHANGE_WIFI_MULTICAST_STATE
        if (isWifi()) {
            setWiFiMulticastLock(false);
            setWifiLock(false);
        }
        upnpProtocolHandler = null;
        multicastReceiver.cancel();
        udpTransiver.cancel();
    }

    private boolean isWifi() {
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager.getNetworkCapabilities(connectivityManager.getActiveNetwork()) == null) {
            return false;
        }
        return connectivityManager.getNetworkCapabilities(connectivityManager.getActiveNetwork()).hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
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

        if (enable) {
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
}
