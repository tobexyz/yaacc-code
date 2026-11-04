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
package de.yaacc.player;

import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.RemoteDevice;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.yaacc.R;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.YaaccLogger;
import de.yaacc.yxc.YxcDeviceSnapshot;
import de.yaacc.yxc.YxcDistributionInfo;
import de.yaacc.yxc.YxcGroupDirectory;
import de.yaacc.yxc.YxcMonitor;
import de.yaacc.yxc.YxcNames;
import de.yaacc.yxc.YxcPlaybackRedirect;

/**
 * Applies {@link YxcPlaybackRedirect} where players are created: what is sent to a client of a group goes to the
 * server of the group, with a short hint; a client whose server is not known gets nothing. The group state is read
 * fresh for the devices involved first (the last state may be older than the last change of the group), on the
 * calling worker thread. Receivers that do not speak the protocol are never touched, and with none of them
 * selected nothing is read at all.
 */
final class YxcPlayTargets {

    private YxcPlayTargets() {
    }

    static Collection<Device<?, ?, ?>> resolve(UpnpClient upnpClient, Collection<Device<?, ?, ?>> devices) {
        try {
            return resolveChecked(upnpClient, devices);
        } catch (RuntimeException e) {
            YaaccLogger.w(YxcPlayTargets.class.getName(), "Group redirect failed, playing as selected", e);
            return devices;
        }
    }

    private static Collection<Device<?, ?, ?>> resolveChecked(UpnpClient upnpClient, Collection<Device<?, ?, ?>> devices) {
        YxcMonitor monitor = YxcMonitor.getShared();
        boolean mayUseNetwork = Looper.myLooper() != Looper.getMainLooper();
        List<String> selected = new ArrayList<>();
        for (Device<?, ?, ?> device : devices) {
            selected.add(device.getIdentity().getUdn().getIdentifierString());
        }
        Map<String, String> everyRenderer = descriptorUrls(upnpClient.getDevicesProvidingAvTransportService());
        if (mayUseNetwork) {
            // the selection of receivers is stored: after an app start no screen has told the monitor about them yet
            monitor.prepareNow(only(everyRenderer, selected), YxcMonitor.DEFAULT_NOW_BUDGET_MILLIS);
        }
        if (!anyKnown(monitor, selected)) {
            return devices;
        }
        YxcPlaybackRedirect.Plan plan = readAndPlan(monitor, selected, mayUseNetwork);
        if (!plan.getRefused().isEmpty() && mayUseNetwork) {
            // a client whose server is not known (not watched yet): look at the other receivers as well
            monitor.prepareNow(everyRenderer, YxcMonitor.DEFAULT_NOW_BUDGET_MILLIS);
            plan = readAndPlan(monitor, selected, true);
        }
        if (plan.getRedirected().isEmpty() && plan.getRefused().isEmpty()) {
            return devices;
        }
        List<Device<?, ?, ?>> result = new ArrayList<>();
        for (String udn : plan.getTargets()) {
            Device<?, ?, ?> device = upnpClient.getDevice(udn);
            if (device != null) {
                result.add(device);
            }
        }
        for (Map.Entry<String, String> redirect : plan.getRedirected().entrySet()) {
            tell(upnpClient, R.string.yxc_play_redirected, name(upnpClient, redirect.getKey()), name(upnpClient, redirect.getValue()));
        }
        for (String udn : plan.getRefused()) {
            tell(upnpClient, R.string.yxc_play_refused, name(upnpClient, udn));
        }
        return result;
    }

    private static boolean anyKnown(YxcMonitor monitor, List<String> selected) {
        for (YxcDeviceSnapshot snapshot : monitor.snapshot()) {
            if (selected.contains(snapshot.getUdn())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads the group state of the devices that decide the send (the selected ones, every server, and selected devices
     * not read yet), within the budget, and plans from what the devices said.
     */
    private static YxcPlaybackRedirect.Plan readAndPlan(YxcMonitor monitor, List<String> selected, boolean mayUseNetwork) {
        if (mayUseNetwork) {
            Set<String> toRead = new LinkedHashSet<>();
            for (YxcDeviceSnapshot snapshot : monitor.snapshot()) {
                YxcDistributionInfo.Role role = snapshot.getState().getRole();
                boolean isSelected = selected.contains(snapshot.getUdn());
                if (isSelected || role == YxcDistributionInfo.Role.SERVER) {
                    toRead.add(snapshot.getUdn());
                }
            }
            monitor.refreshGroupInfoNow(toRead, YxcMonitor.DEFAULT_NOW_BUDGET_MILLIS);
        }
        return YxcPlaybackRedirect.plan(selected, new YxcGroupDirectory(monitor.snapshot()));
    }

    private static Map<String, String> only(Map<String, String> all, Collection<String> udns) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : all.entrySet()) {
            if (udns.contains(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    /** The receivers on the network, by UDN, with the URL the registry fetched their description from. */
    private static Map<String, String> descriptorUrls(Collection<Device<?, ?, ?>> devices) {
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

    private static String name(UpnpClient upnpClient, String udn) {
        Device<?, ?, ?> device = upnpClient.getDevice(udn);
        if (device == null || device.getDetails() == null) {
            return "?";
        }
        String name = YxcNames.clean(device.getDetails().getFriendlyName());
        return name == null ? "?" : name;
    }

    private static void tell(UpnpClient upnpClient, int resource, Object... args) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(upnpClient.getContext(), upnpClient.getContext().getString(resource, args), Toast.LENGTH_LONG).show());
    }
}
