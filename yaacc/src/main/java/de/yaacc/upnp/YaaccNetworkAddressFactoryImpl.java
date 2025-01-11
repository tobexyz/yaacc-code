/*
 *
 * Copyright (C) 2024 Tobias Schoene www.yaacc.de
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
package de.yaacc.upnp;

import android.content.Context;
import android.util.Log;

import org.fourthline.cling.model.Constants;
import org.fourthline.cling.transport.spi.NetworkAddressFactory;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;

import de.yaacc.upnp.server.YaaccUpnpServerService;

public class YaaccNetworkAddressFactoryImpl implements NetworkAddressFactory {

    private final Context context;
    private final int streamListenPort;

    public YaaccNetworkAddressFactoryImpl(int streamListenPort, Context context) {
        this.streamListenPort = streamListenPort;
        this.context = context;
    }


    @Override
    public InetAddress getMulticastGroup() {
        try {
            return InetAddress.getByName(Constants.IPV4_UPNP_MULTICAST_GROUP);
        } catch (UnknownHostException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public int getMulticastPort() {
        return Constants.UPNP_MULTICAST_PORT;
    }

    @Override
    public int getStreamListenPort() {
        return streamListenPort;
    }

    @Override
    public Iterator<NetworkInterface> getNetworkInterfaces() {
        List<NetworkInterface> ifList = new ArrayList<>();
        try {
            ifList.add(NetworkInterface.getByName(YaaccUpnpServerService.getIfName(context)));
        } catch (
                SocketException se) {
            Log.d(getClass().getName(),
                    "Error while retrieving network interfaces", se);
        }
        return ifList.iterator();
    }


    @Override
    public Iterator<InetAddress> getBindAddresses() {
        List<InetAddress> result = new ArrayList<>();
        try {
            Enumeration<InetAddress> iter = NetworkInterface.getByName(YaaccUpnpServerService.getIfName(context)).getInetAddresses();
            while (iter.hasMoreElements()) {
                result.add(iter.nextElement());
            }
        } catch (
                SocketException se) {
            Log.d(getClass().getName(),
                    "Error while retrieving network interfaces", se);
        }
        return result.iterator();
    }

    @Override
    public boolean hasUsableNetwork() {
        return !"0.0.0.0".equals(YaaccUpnpServerService.getIpAddress(context));
    }

    @Override
    public Short getAddressNetworkPrefixLength(InetAddress inetAddress) {
        return 0;
    }

    @Override
    public byte[] getHardwareAddress(InetAddress inetAddress) {
        return new byte[0];
    }

    @Override
    public InetAddress getBroadcastAddress(InetAddress inetAddress) {
        return getBindAddresses().next();
    }

    @Override
    public InetAddress getLocalAddress(NetworkInterface networkInterface, boolean isIPv6, InetAddress remoteAddress) throws IllegalStateException {
        return getBindAddresses().next();
    }

    @Override
    public void logInterfaceInformation() {

    }
}
