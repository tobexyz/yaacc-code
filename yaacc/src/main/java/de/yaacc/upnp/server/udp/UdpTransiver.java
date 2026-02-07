package de.yaacc.upnp.server.udp;

import android.content.Context;
import de.yaacc.util.YaaccLogger;

import de.yaacc.util.Exceptions;
import org.fourthline.cling.model.UnsupportedDataException;
import org.fourthline.cling.model.message.IncomingDatagramMessage;
import org.fourthline.cling.model.message.OutgoingDatagramMessage;

import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.yaacc.upnp.protocol.ProtocolCreationException;
import de.yaacc.upnp.protocol.ReceivingAsync;
import de.yaacc.upnp.protocol.UpnpProtocolHandler;
import de.yaacc.util.InterfaceResolutionHelper;

public class UdpTransiver {

    private static int TTL = 4;
    private UpnpProtocolHandler protocolHandler;
    private int MAX_DATAGRAM_BYTES = 640;

    private MulticastSocket socket;

    private ExecutorService receiverExecutor;
    private ExecutorService protocolExecutor;
    private Context context;

    public UdpTransiver() {
        receiverExecutor = Executors.newSingleThreadExecutor();
        protocolExecutor = Executors.newFixedThreadPool(10);
    }

    public void init(Context context, UpnpProtocolHandler protocolHandler) {
        this.protocolHandler = protocolHandler;
        this.context = context;
        initSocket();
    }

    private void initSocket() {
        InterfaceResolutionHelper.InterfaceHolder usableInterface = InterfaceResolutionHelper.getNetworkInterface(context);
        try {

            // TODO: UPNP VIOLATION: The spec does not prohibit using the 1900 port here again, however, the
            // Netgear ReadyNAS miniDLNA implementation will no longer answer if it has to send search response
            // back via UDP unicast to port 1900... so we use an ephemeral port
            YaaccLogger.v(getClass().getName(), "Creating bound socket (for datagram input/output) on: " + usableInterface.inetAddress);
            InetSocketAddress localAddress = new InetSocketAddress(usableInterface.inetAddress, 0);
            socket = new MulticastSocket(localAddress);
            socket.setTimeToLive(TTL);
            socket.setReceiveBufferSize(262144); // Keep a backlog of incoming datagrams if we are not fast enough
            YaaccLogger.v(getClass().getName(), "Socket created and bound to: " + socket.getLocalSocketAddress() + " on interface: " + usableInterface.networkInterface.getDisplayName());
        } catch (Exception ex) {
            throw new IllegalStateException("Could not initialize " + getClass().getSimpleName() + ": " + ex);
        }
    }

    public void send(OutgoingDatagramMessage message) {
        DatagramPacket packet = DatagramHelper.write(message);
        YaaccLogger.v(getClass().getName(), "Sending UDP datagram packet to: " + message.getDestinationAddress() + ":" + message.getDestinationPort());
        send(packet);
    }

    public void send(DatagramPacket datagram) {
        protocolExecutor.execute(() -> {
            try {
                socket.send(datagram);
            } catch (SocketException ex) {
                YaaccLogger.v(getClass().getName(), "Socket closed, aborting datagram send to: " + datagram.getAddress());
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
                try {
                    YaaccLogger.w(getClass().getName(), socket.getNetworkInterface() + " Exception sending datagram to: " + datagram.getAddress() + ": " + ex, ex);
                } catch (SocketException se) {
                    YaaccLogger.e(getClass().getName(), " Exception sending datagram to: " + datagram.getAddress() + ": " + ex, ex);
                }
            }

        });
    }

    public void execute() {
        YaaccLogger.v(getClass().getName(), "execute() called, submitting receiver task");
        receiverExecutor.execute(() -> {
            YaaccLogger.v(getClass().getName(), "Receiver task started");
            try {
                YaaccLogger.v(getClass().getName(), "Entering blocking receiving loop, listening for UDP datagrams on: " + socket.getLocalAddress());
            } catch (Exception e) {
                YaaccLogger.e(getClass().getName(), "Error getting local address", e);
            }

            while (true) {

                try {
                    byte[] buf = new byte[MAX_DATAGRAM_BYTES];
                    DatagramPacket datagram = new DatagramPacket(buf, buf.length);
                    YaaccLogger.v(getClass().getName(), "UDP before");
                    socket.receive(datagram);

                    YaaccLogger.v(getClass().getName(),
                            "UDP datagram received from: "
                                    + datagram.getAddress().getHostAddress()
                                    + ":" + datagram.getPort()
                    );

                    try {
                        IncomingDatagramMessage<?> msg = DatagramHelper.read(socket.getInterface(), datagram);
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
                    YaaccLogger.v(getClass().getName(), "Socket closed asynchronously", ex);
                    break;
                } catch (UnsupportedDataException ex) {
                    YaaccLogger.v(getClass().getName(), "Could not read datagram: " + ex.getMessage(), ex);
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }
            try {
                if (!socket.isClosed()) {
                    YaaccLogger.v(getClass().getName(), "Closing unicast socket");
                    socket.close();
                }
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    public void cancel() {
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

}
