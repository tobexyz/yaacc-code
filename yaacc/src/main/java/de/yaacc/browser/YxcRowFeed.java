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
package de.yaacc.browser;

import android.os.Handler;
import android.os.Looper;

import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.RemoteDevice;

import java.net.URL;
import java.util.Collection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.yaacc.yxc.YxcDeviceState;
import de.yaacc.yxc.YxcMonitor;

/**
 * Connects a screen that shows receiver rows to {@link YxcMonitor}: subscribes with the receivers on screen
 * and tells the screen, on the main thread, which row to rebind. A screen starts it when it comes to the front
 * and stops it when it goes; the monitor itself keeps its event source for a while after the last stop, so
 * switching between the Receiver tab and the sheet does not reopen it.
 */
final class YxcRowFeed implements YxcMonitor.Listener, YxcGroupController.BusyListener {

    /** The screen's side: rebind the row of this device (UDN as the row binder uses it). */
    interface Rows {
        void onGroupStateChanged(String udn);
    }

    private final Rows rows;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    // devices whose row is to be rebound; one post to the main thread serves all of them
    private final Set<String> pending = new LinkedHashSet<>();

    YxcRowFeed(Rows rows) {
        this.rows = rows;
    }

    void start(Collection<Device<?, ?, ?>> devices) {
        running = true;
        YxcGroupController.getShared().addBusyListener(this);
        YxcMonitor.getShared().subscribe(this, descriptorUrls(devices));
    }

    void stop() {
        running = false;
        YxcGroupController.getShared().removeBusyListener(this);
        YxcMonitor.getShared().unsubscribe(this);
    }

    @Override
    public void onStateChanged(String udn, YxcDeviceState state) {
        synchronized (pending) {
            boolean idle = pending.isEmpty();
            pending.add(udn);
            if (!idle) {
                return; // a post is on its way and will take this one too
            }
        }
        handler.post(this::drain);
    }

    /** Devices became busy or free: their rows change (called on the main thread). */
    @Override
    public void onBusyChanged(java.util.Set<String> changedUdns) {
        if (!running) {
            return;
        }
        for (String udn : changedUdns) {
            rows.onGroupStateChanged(udn);
        }
    }

    private void drain() {
        List<String> udns;
        synchronized (pending) {
            udns = new ArrayList<>(pending);
            pending.clear();
        }
        if (!running) {
            return;
        }
        for (String udn : udns) {
            rows.onGroupStateChanged(udn);
        }
    }

    /**
     * The receivers that are on the network, by UDN, with the URL the registry fetched their description from.
     * The phone's own renderer and the dummy device have none and are left out; nothing here comes from
     * user input.
     */
    static Map<String, String> descriptorUrls(Collection<Device<?, ?, ?>> devices) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Device<?, ?, ?> device : devices) {
            if (device instanceof RemoteDevice) {
                URL url = ((RemoteDevice) device).getIdentity().getDescriptorURL();
                if (url != null) {
                    result.put(device.getIdentity().getUdn().getIdentifierString(), url.toString());
                }
            }
        }
        return result;
    }
}
