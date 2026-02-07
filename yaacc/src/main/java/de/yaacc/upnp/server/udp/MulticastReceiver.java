
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
import de.yaacc.util.YaaccLogger;

import de.yaacc.util.Exceptions;
import org.fourthline.cling.model.UnsupportedDataException;
import org.fourthline.cling.model.message.IncomingDatagramMessage;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.yaacc.upnp.protocol.ProtocolCreationException;
import de.yaacc.upnp.protocol.ReceivingAsync;
import de.yaacc.upnp.protocol.UpnpProtocolHandler;
import de.yaacc.util.InterfaceResolutionHelper;

/*
Handling of UDP multicast packages
 */
public class MulticastReceiver {

    public static final int UPNP_MULTICAST_PORT = 1900;
    public static final String IPV4_UPNP_MULTICAST_GROUP = "239.255.255.250";

    protected UpnpProtocolHandler protocolHandler;

    protected NetworkInterface multicastInterface;
    protected InetSocketAddress multicastAddress;
    //protected MulticastSocket socket;
    private DatagramChannel channel;
    private Context context;
    private ExecutorService receiverExecutor;
    private ExecutorService protocolExecutor;


    public MulticastReceiver() {
        receiverExecutor = Executors.newSingleThreadExecutor();
        protocolExecutor = Executors.newFixedThreadPool(100);

    }


    public void init(Context context, UpnpProtocolHandler protocolHandler) {
        this.protocolHandler = protocolHandler;
        this.context = context;
        initSocket();
    }

    private void initSocket() {
        InterfaceResolutionHelper.InterfaceHolder usableInterface = InterfaceResolutionHelper.getNetworkInterface(context);
        this.multicastInterface = usableInterface.networkInterface;

        try {

            YaaccLogger.v(getClass().getName(), "Creating wildcard socket (for receiving multicast datagrams) on port: " + UPNP_MULTICAST_PORT);
         /*   multicastAddress = new InetSocketAddress(getMulticastGroup(), UPNP_MULTICAST_PORT);

            socket = new MulticastSocket(UPNP_MULTICAST_PORT);
            socket.setReuseAddress(true);
            socket.setReceiveBufferSize(32768); // Keep a backlog of incoming datagrams if we are not fast enough
            YaaccLogger.v(getClass().getName(), "Joining multicast group: " + multicastAddress + " on network interface: " + multicastInterface.getDisplayName());
            socket.joinGroup(multicastAddress, multicastInterface);
*/

            channel = DatagramChannel.open(StandardProtocolFamily.INET);
            channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            channel.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), UPNP_MULTICAST_PORT));
            channel.join(getMulticastGroup(), multicastInterface);

        } catch (Exception ex) {
            throw new IllegalStateException("Could not initialize " + getClass().getSimpleName() + ": ", ex);
        }
    }

    public InetAddress getMulticastGroup() {
        try {
            return InetAddress.getByName(IPV4_UPNP_MULTICAST_GROUP);
        } catch (UnknownHostException ex) {
            throw new RuntimeException(ex);
        }
    }

    public void execute() {
        receiverExecutor.execute(() -> {
            try {
                YaaccLogger.v(getClass().getName(), "Entering blocking receiving loop, listening for UDP datagrams on: " + channel.getLocalAddress() /*socket.getLocalAddress()*/);
            } catch (IOException e) {
                YaaccLogger.v(getClass().getName(), "Could not get local address: ", e);
            }
            InetAddress receivedOnLocalAddress = InterfaceResolutionHelper.getBindAddresses(context).next();
            ByteBuffer buffer = ByteBuffer.allocate(1024);
            while (true) {
                buffer.clear();
                try {
                    //byte[] buf = new byte[640];
                    //DatagramPacket datagram = new DatagramPacket(buf, buf.length);

                    if (!channel.isOpen()) {
                        initSocket();
                    }
                    InetSocketAddress sender = (InetSocketAddress) channel.receive(buffer);
                    buffer.flip();
                    byte[] data = new byte[buffer.remaining()];
                    buffer.get(data);

                    //socket.receive(datagram);

                    YaaccLogger.v(getClass().getName(),
                            "UDP datagram received from: " + sender.getAddress()//datagram.getAddress().getHostAddress()
                                    + ":" + sender.getPort()//datagram.getPort()
                                    + " on local interface: " + channel.getLocalAddress()//multicastInterface.getDisplayName()
                                    + " and address: " + receivedOnLocalAddress.getHostAddress()
                    );

                    try {
                        IncomingDatagramMessage<?> msg = DatagramHelper.read(receivedOnLocalAddress, data, sender.getAddress(), sender.getPort());
                        ReceivingAsync<?> protocol = protocolHandler.createReceivingAsync(msg);
                        if (protocol == null) {
                            YaaccLogger.v(getClass().getName(), "No protocol, ignoring received message: " + msg);
                            continue;
                        }

                        YaaccLogger.v(getClass().getName(), "Received asynchronous message: " + msg);
                        protocolExecutor.execute(protocol);
                    } catch (ProtocolCreationException ex) {
                        YaaccLogger.w(getClass().getName(), "Handling received datagram failed - " + Exceptions.unwrap(ex).toString());
                    }

                } catch (SocketException ex) {
                    YaaccLogger.v(getClass().getName(), "Socket closed", ex);
                    break;
                } catch (java.nio.channels.AsynchronousCloseException ex) {
                    YaaccLogger.v(getClass().getName(), "Channel closed asynchronously", ex);
                    break;
                } catch (UnsupportedDataException ex) {
                    YaaccLogger.v(getClass().getName(), "Could not read datagram: " + ex.getMessage(), ex);
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }
            try {
                if (channel.isOpen()) {
                    YaaccLogger.v(getClass().getName(), "Closing multicast socket");
                    channel.close();
                }
                /*
                if (!socket.isClosed()) {
                    YaaccLogger.v(getClass().getName(), "Closing multicast socket");
                    socket.close();
                }
                 */
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    public void cancel() {
        /*
        if (socket != null && !socket.isClosed()) {
            try {
                YaaccLogger.v(getClass().getName(), "Leaving multicast group");
                socket.leaveGroup(multicastAddress, multicastInterface);
                // Well this doesn't work and I have no idea why I get "java.net.SocketException: Can't assign requested address"
            } catch (Exception ex) {
                YaaccLogger.v(getClass().getName(), "Could not leave multicast group: ", ex);
            }
            // So... just close it and ignore the log messages
            socket.close();
        }*/
        if (channel != null && channel.isOpen()) {
            try {
                channel.close();
            } catch (IOException ex) {
                YaaccLogger.v(getClass().getName(), "Could not close multicast channel: ", ex);
            }
        }

    }

}

