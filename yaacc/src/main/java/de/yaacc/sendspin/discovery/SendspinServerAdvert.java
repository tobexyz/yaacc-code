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
package de.yaacc.sendspin.discovery;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;

import de.yaacc.util.YaaccLogger;

/**
 * Announces this phone as a Sendspin <b>server</b> by mDNS (spec, "Client Initiated Connections"): the service type
 * {@value SendspinServiceType#SERVER_TYPE} on the port of the listener, with the TXT records {@code path} (required) and
 * {@code name}. A client that does not announce itself finds the phone this way and connects to it. Opt-in (the user's switch), and
 * only while the listener runs. The instance name is the id of this server. The Android part only: what is announced and when is
 * decided by the hub.
 */
public final class SendspinServerAdvert {

    private final Context context;
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener listener;
    private WifiManager.MulticastLock multicastLock;

    public SendspinServerAdvert(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Registers the service. Does nothing if it is registered already.
     *
     * @param instanceName the DNS-SD instance name (the id of this server: at most 63 bytes)
     * @param displayName  the {@code name} of the TXT record
     * @param path         the {@code path} of the TXT record, the one the listener serves
     */
    public synchronized void register(String instanceName, String displayName, String path, int port) {
        register(SendspinServiceType.SERVER_TYPE, instanceName, displayName, path, port);
    }

    /** As above, for another service type: {@link SendspinServiceType#TYPE} announces a player (the phone as a player). */
    public synchronized void register(String serviceType, String instanceName, String displayName, String path, int port) {
        if (listener != null) {
            return;
        }
        nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        if (nsdManager == null) {
            return;
        }
        acquireLock();
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(instanceName);
        info.setServiceType(serviceType);
        info.setPort(port);
        info.setAttribute("path", path);
        info.setAttribute("name", displayName);
        listener = new NsdManager.RegistrationListener() {
            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                YaaccLogger.w(SendspinServerAdvert.class.getName(), "Sendspin announcement failed, code " + errorCode);
                synchronized (SendspinServerAdvert.this) {
                    listener = null;
                    releaseLock();
                }
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                YaaccLogger.d(SendspinServerAdvert.class.getName(), "Sendspin announcement did not end, code " + errorCode);
            }

            @Override
            public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                YaaccLogger.d(SendspinServerAdvert.class.getName(), "Sendspin server announced");
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
                YaaccLogger.d(SendspinServerAdvert.class.getName(), "Sendspin server announcement ended");
            }
        };
        try {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (RuntimeException e) {
            YaaccLogger.w(SendspinServerAdvert.class.getName(), "Sendspin announcement refused: " + e.getClass().getSimpleName());
            listener = null;
            releaseLock();
        }
    }

    /** Ends the announcement. Safe to call when nothing is registered. */
    public synchronized void unregister() {
        NsdManager.RegistrationListener current = listener;
        listener = null;
        if (current != null && nsdManager != null) {
            try {
                nsdManager.unregisterService(current);
            } catch (RuntimeException e) {
                YaaccLogger.d(SendspinServerAdvert.class.getName(), "unregister: " + e.getClass().getSimpleName());
            }
        }
        releaseLock();
    }

    private void acquireLock() {
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("yaacc-sendspin-advert");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (RuntimeException e) {
            multicastLock = null; // without the lock the announcement may still work
        }
    }

    private void releaseLock() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
            }
        } catch (RuntimeException ignored) {
            // released
        }
        multicastLock = null;
    }
}
