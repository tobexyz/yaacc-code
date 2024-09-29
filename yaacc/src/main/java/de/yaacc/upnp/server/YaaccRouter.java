package de.yaacc.upnp.server;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.util.Log;

import androidx.annotation.NonNull;

import org.fourthline.cling.UpnpServiceConfiguration;
import org.fourthline.cling.protocol.ProtocolFactory;
import org.fourthline.cling.transport.RouterException;
import org.fourthline.cling.transport.RouterImpl;
import org.fourthline.cling.transport.spi.InitializationException;

public class YaaccRouter extends RouterImpl {
    private final Context context;
    private final WifiManager wifiManager;
    private WifiManager.MulticastLock multicastLock;
    private WifiManager.WifiLock wifiLock;

    private Network currentNetwork;

    public YaaccRouter(UpnpServiceConfiguration configuration,
                       ProtocolFactory protocolFactory,
                       Context context) throws InitializationException {
        super(configuration, protocolFactory);
        this.context = context;
        this.wifiManager = ((WifiManager) context.getSystemService(Context.WIFI_SERVICE));
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (!isCellular()) {
            currentNetwork = connectivityManager.getActiveNetwork();

        }
        if (currentNetwork != null) {
            try {
                enable();
            } catch (RouterException e) {
                Log.e(getClass().getName(), String.format("RouterException network enabling %s", currentNetwork), e);
            }
        }
        connectivityManager.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull android.net.Network network) {
                super.onAvailable(network);
                if (!isCellular() && !network.equals(currentNetwork)) {
                    Log.d(getClass().getName(), String.format("Network available %s", network));
                    if (currentNetwork != null) {
                        try {
                            disable();
                            Log.d(getClass().getName(), String.format("Network disabled %s", currentNetwork));
                        } catch (RouterException e) {
                            Log.e(getClass().getName(), String.format("RouterException network disabling %s", currentNetwork), e);
                        }
                    }
                    currentNetwork = network;
                    try {
                        enable();
                        Log.d(getClass().getName(), String.format("Network enabled %s", currentNetwork));
                    } catch (RouterException e) {
                        Log.e(getClass().getName(), String.format("RouterException network enabling %s", currentNetwork), e);
                    }

                }

            }

            @Override
            public void onLost(@NonNull android.net.Network network) {
                super.onLost(network);
                if (network.equals(currentNetwork)) {
                    Log.d(getClass().getName(), String.format("Network lost %s", network));
                    try {
                        disable();
                        Log.d(getClass().getName(), String.format("Network disabled %s", currentNetwork));
                    } catch (RouterException e) {
                        Log.e(getClass().getName(), String.format("RouterException network disabling %s", currentNetwork), e);
                    }
                    currentNetwork = null;
                }
            }
        });
    }

    @Override
    public boolean enable() throws RouterException {
        Log.v(getClass().getName(), "in android router enable");
        lock(writeLock);
        try {
            boolean enabled = super.enable();
            if (enabled) {
                // Enable multicast on the WiFi network interface,
                // requires android.permission.CHANGE_WIFI_MULTICAST_STATE
                if (isWifi()) {
                    setWiFiMulticastLock(true);
                    setWifiLock(true);
                }
            }
            return enabled;
        } finally {
            unlock(writeLock);
            Log.v(getClass().getName(), "leave android router enable");
        }
    }

    @Override
    public boolean disable() throws RouterException {
        Log.v(getClass().getName(), "in android router disable");
        lock(writeLock);
        try {
            // Disable multicast on WiFi network interface,
            // requires android.permission.CHANGE_WIFI_MULTICAST_STATE
            if (isWifi()) {
                setWiFiMulticastLock(false);
                setWifiLock(false);
            }
            return super.disable();
        } finally {
            unlock(writeLock);
            Log.v(getClass().getName(), "leave android router disable");
        }
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
                Log.w(getClass().getName(), "WiFi multicast lock already acquired");
            } else {
                Log.d(getClass().getName(), "WiFi multicast lock acquired");
                multicastLock.acquire();
            }
        } else {
            if (multicastLock.isHeld()) {
                Log.d(getClass().getName(), "WiFi multicast lock released");
                multicastLock.release();
            } else {
                Log.w(getClass().getName(), "WiFi multicast lock already released");
            }
        }
    }

    protected void setWifiLock(boolean enable) {
        if (wifiLock == null) {
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, getClass().getSimpleName());
        }

        if (enable) {
            if (wifiLock.isHeld()) {
                Log.w(getClass().getName(), "WiFi lock already acquired");
            } else {
                Log.d(getClass().getName(), "WiFi lock acquired");
                wifiLock.acquire();
            }
        } else {
            if (wifiLock.isHeld()) {
                Log.d(getClass().getName(), "WiFi lock released");
                wifiLock.release();
            } else {
                Log.w(getClass().getName(), "WiFi lock already released");
            }
        }
    }

}
