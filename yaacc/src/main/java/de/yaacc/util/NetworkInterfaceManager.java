/*
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
package de.yaacc.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Pattern;

import de.yaacc.R;

public class NetworkInterfaceManager {
    private static final Pattern IPV4_PATTERN =
            Pattern.compile(
                    "^(25[0-5]|2[0-4]\\d|[0-1]?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|[0-1]?\\d?\\d)){3}$");

    public static class NetworkInterfaceInfo {
        public String name;
        public String displayName;
        public InetAddress address;
        public boolean isSelected;

        public NetworkInterfaceInfo(String name, String displayName, InetAddress address) {
            this.name = name;
            this.displayName = displayName;
            this.address = address;
        }
    }

    public static List<NetworkInterfaceInfo> getAvailableInterfaces(Context context) {
        List<NetworkInterfaceInfo> result = new ArrayList<>();
        try {
            for (Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces(); 
                 networkInterfaces.hasMoreElements(); ) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();
                
                // Skip loopback and virtual interfaces
                if (networkInterface.isLoopback() || networkInterface.isVirtual()) {
                    continue;
                }
                
                for (Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses(); 
                     inetAddresses.hasMoreElements(); ) {
                    InetAddress inetAddress = inetAddresses.nextElement();
                    if (!inetAddress.isLoopbackAddress() && 
                        inetAddress.getHostAddress() != null && 
                        IPV4_PATTERN.matcher(inetAddress.getHostAddress()).matches()) {
                        
                        String displayName = String.format("%s (%s)", 
                            networkInterface.getDisplayName(), inetAddress.getHostAddress());
                        
                        result.add(new NetworkInterfaceInfo(
                            networkInterface.getName(),
                            displayName,
                            inetAddress
                        ));
                    }
                }
            }
        } catch (SocketException e) {
            YaaccLogger.e(NetworkInterfaceManager.class.getName(), 
                "Error while retrieving network interfaces", e);
        }
        return result;
    }

    public static String getSelectedInterfaceName(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getString(context.getString(R.string.settings_upnp_selected_interface_key), "");
    }

    public static void setSelectedInterfaceName(Context context, String interfaceName) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putString(context.getString(R.string.settings_upnp_selected_interface_key), interfaceName).apply();
    }

    public static NetworkInterfaceInfo getSelectedInterface(Context context) {
        String selectedName = getSelectedInterfaceName(context);
        List<NetworkInterfaceInfo> interfaces = getAvailableInterfaces(context);
        
        for (NetworkInterfaceInfo info : interfaces) {
            if (selectedName.equals(info.name)) {
                return info;
            }
        }
        
        return null;
    }

    public static NetworkInterfaceInfo getBestInterface(Context context) {
        // If specific interface selected, use it
        NetworkInterfaceInfo selected = getSelectedInterface(context);
        if (selected != null) {
            return selected;
        }
        
        // Otherwise use best effort (current behavior)
        InterfaceResolutionHelper.InterfaceHolder holder = InterfaceResolutionHelper.getNetworkInterface(context);
        if (holder.inetAddress != null && holder.networkInterface != null) {
            NetworkInterfaceInfo info = new NetworkInterfaceInfo(
                holder.networkInterface.getName(),
                holder.networkInterface.getDisplayName() + " (" + holder.inetAddress.getHostAddress() + ")",
                holder.inetAddress
            );
            return info;
        }
        return null;
    }
}
