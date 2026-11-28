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
package de.yaacc.multiroom.server;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import de.yaacc.util.YaaccLogger;

/**
 * The mDNS announcement of the built-in Snapcast server (issue #330, requirement R9): {@code _snapcast._tcp} for the stream port and
 * {@code _snapcast-ctrl._tcp} for the control port, as a snapserver of version 0.33 or newer announces them, under a name that tells it is
 * a phone ("YAACC &lt;name&gt;"). Registered while the server runs, taken back when it stops; no text records are published.
 */
public final class SnapcastServerAdvert {

    private final NsdManager manager;
    private final String name;
    private NsdManager.RegistrationListener streamListener;
    private NsdManager.RegistrationListener controlListener;

    public SnapcastServerAdvert(Context context, String name) {
        this.manager = (NsdManager) context.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        this.name = name;
    }

    public synchronized void register(int streamPort, int controlPort) {
        if (manager == null) {
            return;
        }
        unregister();
        streamListener = announce("_snapcast._tcp", streamPort);
        controlListener = announce("_snapcast-ctrl._tcp", controlPort);
    }

    private NsdManager.RegistrationListener announce(String type, int port) {
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(name);
        info.setServiceType(type);
        info.setPort(port);
        NsdManager.RegistrationListener listener = new NsdManager.RegistrationListener() {
            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                YaaccLogger.d(SnapcastServerAdvert.class.getName(), "the announcement of " + type + " failed: " + errorCode);
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                // it ends with the process
            }

            @Override
            public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                // the system may rename it when the name is taken; nothing depends on the final name
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
            }
        };
        try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (RuntimeException refused) {
            YaaccLogger.d(SnapcastServerAdvert.class.getName(), "the announcement of " + type + " was refused");
            return null;
        }
        return listener;
    }

    public synchronized void unregister() {
        if (manager == null) {
            return;
        }
        for (NsdManager.RegistrationListener l : new NsdManager.RegistrationListener[]{streamListener, controlListener}) {
            if (l != null) {
                try {
                    manager.unregisterService(l);
                } catch (RuntimeException notRegistered) {
                    // already gone
                }
            }
        }
        streamListener = null;
        controlListener = null;
    }
}
