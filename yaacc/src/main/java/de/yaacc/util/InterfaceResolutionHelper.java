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
package de.yaacc.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import org.fourthline.cling.model.NetworkAddress;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import de.yaacc.R;
import de.yaacc.upnp.server.YaaccUpnpServerService;

public class InterfaceResolutionHelper {
    private static final Pattern IPV4_PATTERN =
            Pattern.compile(
                    "^(25[0-5]|2[0-4]\\d|[0-1]?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|[0-1]?\\d?\\d)){3}$");

    public static class InterfaceHolder {
        public NetworkInterface networkInterface;
        public InetAddress inetAddress;
    }

    public static Iterator<InetAddress> getBindAddresses(Context context) {
        List<InetAddress> result = new ArrayList<>();
        if (getIfName(context) != null) {
            try {
                if (NetworkInterface.getByName(getIfName(context)) != null) {
                    Enumeration<InetAddress> iter = NetworkInterface.getByName(getIfName(context)).getInetAddresses();
                    while (iter.hasMoreElements()) {
                        result.add(iter.nextElement());
                    }
                } else {
                    YaaccLogger.d(InterfaceResolutionHelper.class.getName(),
                            "network interface not found by name, maybe device is offline");
                }
            } catch (
                    SocketException se) {
                YaaccLogger.d(InterfaceResolutionHelper.class.getName(),
                        "Error while retrieving network interfaces", se);
            }
        } else {
            YaaccLogger.d(InterfaceResolutionHelper.class.getName(),
                    "network interface name is null, maybe device is offline");
        }
        return result.iterator();
    }

    /**
     * get the ip address of the device
     *
     * @return the address or null if anything went wrong
     */
    public static String getIpAddress(Context context) {
        return getIfAndIpAddress(context)[0];
    }

    public static String getIfName(Context context) {
        return getIfAndIpAddress(context)[1];
    }

    public static String[] getIfAndIpAddress(Context context) {
        String hostAddress = null;
        String[] result = new String[2];
        InterfaceHolder useableInterface = getNetworkInterface(context);
        if (useableInterface.inetAddress == null || useableInterface.networkInterface == null) {
            // maybe wifi is off we have to use the loopback device
            result[0] = "0.0.0.0";
            result[1] = "lo";
            return result;
        }
        hostAddress = useableInterface.inetAddress.getHostAddress();
        // maybe wifi is off we have to use the loopback device
        hostAddress = hostAddress == null ? "0.0.0.0" : hostAddress;
        result[0] = hostAddress;
        result[1] = useableInterface.networkInterface.getName();
        return result;
    }

    public static InterfaceHolder getNetworkInterface(Context context) {
        InterfaceHolder result = new InterfaceHolder();
        
        // Get blacklist of interfaces to exclude
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String blacklistStr = preferences.getString(context.getString(R.string.settings_upnp_if_filter_key), "lo,dummy,rmnet,ccmni,tun");
        List<String> blacklist = new ArrayList<>(List.of(blacklistStr.split(",")));
        blacklist.removeIf(String::isEmpty); // Remove empty strings
        
        // Get selected interface (if any)
        String selectedInterface = NetworkInterfaceManager.getSelectedInterfaceName(context);
        
        try {
            for (Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces(); 
                 networkInterfaces.hasMoreElements(); ) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();
                
                // Skip blacklisted interfaces
                if (blacklist.stream().anyMatch(i -> networkInterface.getName().startsWith(i.trim()))) {
                    continue;
                }
                
                // If specific interface selected, only use that one
                if (!selectedInterface.isEmpty() && !selectedInterface.equals(networkInterface.getName())) {
                    continue;
                }
                
                for (Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses(); 
                     inetAddresses.hasMoreElements(); ) {
                    InetAddress inetAddress = inetAddresses.nextElement();
                    if (!inetAddress.isLoopbackAddress() && 
                        inetAddress.getHostAddress() != null && 
                        IPV4_PATTERN.matcher(inetAddress.getHostAddress()).matches()) {
                        result.inetAddress = inetAddress;
                        result.networkInterface = networkInterface;
                        return result; // Found match, return immediately
                    }
                }
            }
        } catch (SocketException se) {
            YaaccLogger.d(InterfaceResolutionHelper.class.getName(),
                    "Error while retrieving network interfaces", se);
        }
        
        // Fallback to loopback if nothing found
        try {
            result.networkInterface = NetworkInterface.getByName("lo");
            result.inetAddress = InetAddress.getByName("127.0.0.1");
        } catch (SocketException | java.net.UnknownHostException e) {
            YaaccLogger.d(InterfaceResolutionHelper.class.getName(),
                "Error getting loopback interface", e);
        }
        return result;
    }

    public static NetworkAddress getNetworkAddress(Context context) {
        return new NetworkAddress(getNetworkInterface(context).inetAddress, YaaccUpnpServerService.PORT);
    }

}
