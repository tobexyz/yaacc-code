package de.yaacc.upnp.server.udp;

import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;

import org.fourthline.cling.model.UnsupportedDataException;
import org.fourthline.cling.model.message.IncomingDatagramMessage;
import org.fourthline.cling.model.message.OutgoingDatagramMessage;
import org.fourthline.cling.protocol.ProtocolCreationException;
import org.seamless.util.Exceptions;

import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.SocketException;
import java.util.concurrent.Executors;

import de.yaacc.upnp.protocol.ReceivingAsync;
import de.yaacc.upnp.protocol.UpnpProtocolHandler;
import de.yaacc.util.InterfaceResolutionHelper;

public class UdpTransiver extends AsyncTask<Void, Void, Void> {

    private static int TTL = 4;
    private UpnpProtocolHandler protocolHandler;
    private int MAX_DATAGRAM_BYTES = 640;

    private MulticastSocket socket;

    public UdpTransiver() {
    }

    public void init(Context context, UpnpProtocolHandler protocolHandler) {
        this.protocolHandler = protocolHandler;
        InterfaceResolutionHelper.InterfaceHolder usableInterface = InterfaceResolutionHelper.getNetworkInterface(context);
        try {

            // TODO: UPNP VIOLATION: The spec does not prohibit using the 1900 port here again, however, the
            // Netgear ReadyNAS miniDLNA implementation will no longer answer if it has to send search response
            // back via UDP unicast to port 1900... so we use an ephemeral port
            Log.v(getClass().getName(), "Creating bound socket (for datagram input/output) on: " + usableInterface.inetAddress);
            InetSocketAddress localAddress = new InetSocketAddress(usableInterface.inetAddress, 0);
            socket = new MulticastSocket(localAddress);
            socket.setTimeToLive(TTL);
            socket.setReceiveBufferSize(262144); // Keep a backlog of incoming datagrams if we are not fast enough
        } catch (Exception ex) {
            throw new IllegalStateException("Could not initialize " + getClass().getSimpleName() + ": " + ex);
        }
    }

    public void send(OutgoingDatagramMessage message) {
        DatagramPacket packet = DatagramHelper.write(message);
        Log.v(getClass().getName(), "Sending UDP datagram packet to: " + message.getDestinationAddress() + ":" + message.getDestinationPort());
        send(packet);
    }

    public void send(DatagramPacket datagram) {
        try {
            socket.send(datagram);
        } catch (SocketException ex) {
            Log.v(getClass().getName(), "Socket closed, aborting datagram send to: " + datagram.getAddress());
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            try {
                Log.w(getClass().getName(), socket.getNetworkInterface() + " Exception sending datagram to: " + datagram.getAddress() + ": " + ex, ex);
            } catch (SocketException se) {
                Log.e(getClass().getName(), " Exception sending datagram to: " + datagram.getAddress() + ": " + ex, ex);
            }
        }
    }


    @Override
    protected Void doInBackground(Void... voids) {

        Log.v(getClass().getName(), "Entering blocking receiving loop, listening for UDP datagrams on: " + socket.getLocalAddress());

        while (true) {

            try {
                byte[] buf = new byte[MAX_DATAGRAM_BYTES];
                DatagramPacket datagram = new DatagramPacket(buf, buf.length);

                socket.receive(datagram);

                Log.v(getClass().getName(),
                        "UDP datagram received from: "
                                + datagram.getAddress().getHostAddress()
                                + ":" + datagram.getPort()
                );

                try {
                    IncomingDatagramMessage<?> msg = DatagramHelper.read(socket.getInterface(), datagram);
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
                Log.v(getClass().getName(), "Closing unicast socket");
                socket.close();
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return null;
    }

    @Override
    protected void onCancelled() {
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

}
