
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
package de.yaacc.upnp.server.udp;

import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;

import org.fourthline.cling.model.UnsupportedDataException;
import org.fourthline.cling.model.message.IncomingDatagramMessage;
import org.fourthline.cling.protocol.ProtocolCreationException;
import org.seamless.util.Exceptions;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.concurrent.Executors;

import de.yaacc.upnp.protocol.ReceivingAsync;
import de.yaacc.upnp.protocol.UpnpProtocolHandler;
import de.yaacc.util.InterfaceResolutionHelper;

/*
Handling of UDP multicast packages
 */
public class MulticastReceiver extends AsyncTask<Void, Void, Void> {

    public static final int UPNP_MULTICAST_PORT = 1900;
    public static final String IPV4_UPNP_MULTICAST_GROUP = "239.255.255.250";

    protected UpnpProtocolHandler protocolHandler;

    protected NetworkInterface multicastInterface;
    protected InetSocketAddress multicastAddress;
    protected MulticastSocket socket;
    private Context context;


    public MulticastReceiver() {

    }


    public void init(Context context, UpnpProtocolHandler protocolHandler) {
        this.protocolHandler = protocolHandler;
        this.context = context;
        InterfaceResolutionHelper.InterfaceHolder usableInterface = InterfaceResolutionHelper.getNetworkInterface(context);
        this.multicastInterface = usableInterface.networkInterface;

        try {

            Log.v(getClass().getName(), "Creating wildcard socket (for receiving multicast datagrams) on port: " + UPNP_MULTICAST_PORT);
            multicastAddress = new InetSocketAddress(getMulticastGroup(), UPNP_MULTICAST_PORT);

            socket = new MulticastSocket(UPNP_MULTICAST_PORT);
            socket.setReuseAddress(true);
            socket.setReceiveBufferSize(32768); // Keep a backlog of incoming datagrams if we are not fast enough

            Log.v(getClass().getName(), "Joining multicast group: " + multicastAddress + " on network interface: " + multicastInterface.getDisplayName());
            socket.joinGroup(multicastAddress, multicastInterface);

        } catch (Exception ex) {
            throw new IllegalStateException("Could not initialize " + getClass().getSimpleName() + ": " + ex);
        }
    }

    @Override
    protected void onCancelled() {
        if (socket != null && !socket.isClosed()) {
            try {
                Log.v(getClass().getName(), "Leaving multicast group");
                socket.leaveGroup(multicastAddress, multicastInterface);
                // Well this doesn't work and I have no idea why I get "java.net.SocketException: Can't assign requested address"
            } catch (Exception ex) {
                Log.v(getClass().getName(), "Could not leave multicast group: ", ex);
            }
            // So... just close it and ignore the log messages
            socket.close();
        }
    }


    @Override
    protected Void doInBackground(Void... voids) {
        Log.v(getClass().getName(), "Entering blocking receiving loop, listening for UDP datagrams on: " + socket.getLocalAddress());
        while (true) {

            try {
                byte[] buf = new byte[640];
                DatagramPacket datagram = new DatagramPacket(buf, buf.length);

                socket.receive(datagram);

                InetAddress receivedOnLocalAddress = InterfaceResolutionHelper.getBindAddresses(context).next();

                Log.v(getClass().getName(),
                        "UDP datagram received from: " + datagram.getAddress().getHostAddress()
                                + ":" + datagram.getPort()
                                + " on local interface: " + multicastInterface.getDisplayName()
                                + " and address: " + receivedOnLocalAddress.getHostAddress()
                );

                try {
                    IncomingDatagramMessage<?> msg = DatagramHelper.read(receivedOnLocalAddress, datagram);
                    ReceivingAsync<?> protocol = protocolHandler.createReceivingAsync(msg);
                    if (protocol == null) {

                        Log.v(getClass().getName(), "No protocol, ignoring received message: " + msg);
                        break;
                    }

                    Log.v(getClass().getName(), "Received asynchronous message: " + msg);
                    Executors.newSingleThreadExecutor().execute(protocol);
                } catch (ProtocolCreationException ex) {
                    Log.w(getClass().getName(), "Handling received datagram failed - " + Exceptions.unwrap(ex).toString());
                }

            } catch (SocketException ex) {
                Log.v(getClass().getName(), "Socket closed", ex);
                break;
            } catch (UnsupportedDataException ex) {
                Log.v(getClass().getName(), "Could not read datagram: " + ex.getMessage(), ex);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
        try {
            if (!socket.isClosed()) {
                Log.v(getClass().getName(), "Closing multicast socket");
                socket.close();
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return null;
    }

    public InetAddress getMulticastGroup() {
        try {
            return InetAddress.getByName(IPV4_UPNP_MULTICAST_GROUP);
        } catch (UnknownHostException ex) {
            throw new RuntimeException(ex);
        }
    }

}

