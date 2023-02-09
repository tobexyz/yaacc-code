/*
 * Copyright (C) 2013 4th Line GmbH, Switzerland
 *
 * The contents of this file are subject to the terms of either the GNU
 * Lesser General Public License Version 2 or later ("LGPL") or the
 * Common Development and Distribution License Version 1 or later
 * ("CDDL") (collectively, the "License"). You may not use this file
 * except in compliance with the License. See LICENSE.txt for more
 * information.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.fourthline.cling.transport.impl;

import android.util.Log;

import org.fourthline.cling.model.UnsupportedDataException;
import org.fourthline.cling.transport.Router;
import org.fourthline.cling.transport.spi.DatagramProcessor;
import org.fourthline.cling.transport.spi.InitializationException;
import org.fourthline.cling.transport.spi.MulticastReceiver;
import org.fourthline.cling.transport.spi.NetworkAddressFactory;

import java.net.DatagramPacket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;

/**
 * Default implementation based on a UDP <code>MulticastSocket</code>.
 * <p>
 * Thread-safety is guaranteed through synchronization of methods of this service and
 * by the thread-safe underlying socket.
 * </p>
 *
 * @author Christian Bauer
 */
public class MulticastReceiverImpl implements MulticastReceiver<MulticastReceiverConfigurationImpl> {

    final protected MulticastReceiverConfigurationImpl configuration;

    protected Router router;
    protected NetworkAddressFactory networkAddressFactory;
    protected DatagramProcessor datagramProcessor;

    protected NetworkInterface multicastInterface;
    protected InetSocketAddress multicastAddress;
    protected MulticastSocket socket;

    public MulticastReceiverImpl(MulticastReceiverConfigurationImpl configuration) {
        this.configuration = configuration;
    }

    public MulticastReceiverConfigurationImpl getConfiguration() {
        return configuration;
    }

    synchronized public void init(NetworkInterface networkInterface,
                                  Router router,
                                  NetworkAddressFactory networkAddressFactory,
                                  DatagramProcessor datagramProcessor) throws InitializationException {

        this.router = router;
        this.networkAddressFactory = networkAddressFactory;
        this.datagramProcessor = datagramProcessor;
        this.multicastInterface = networkInterface;

        try {

            Log.i(getClass().getName(), "Creating wildcard socket (for receiving multicast datagrams) on port: " + configuration.getPort());
            multicastAddress = new InetSocketAddress(configuration.getGroup(), configuration.getPort());

            socket = new MulticastSocket(configuration.getPort());
            socket.setReuseAddress(true);
            socket.setReceiveBufferSize(32768); // Keep a backlog of incoming datagrams if we are not fast enough

            Log.i(getClass().getName(), "Joining multicast group: " + multicastAddress + " on network interface: " + multicastInterface.getDisplayName());
            socket.joinGroup(multicastAddress, multicastInterface);

        } catch (Exception ex) {
            throw new InitializationException("Could not initialize " + getClass().getSimpleName() + ": " + ex);
        }
    }

    synchronized public void stop() {
        if (socket != null && !socket.isClosed()) {
            try {
                Log.d(getClass().getName(), "Leaving multicast group");
                socket.leaveGroup(multicastAddress, multicastInterface);
                // Well this doesn't work and I have no idea why I get "java.net.SocketException: Can't assign requested address"
            } catch (Exception ex) {
                Log.d(getClass().getName(), "Could not leave multicast group: ", ex);
            }
            // So... just close it and ignore the log messages
            socket.close();
        }
    }

    public void run() {

        Log.d(getClass().getName(), "Entering blocking receiving loop, listening for UDP datagrams on: " + socket.getLocalAddress());
        while (true) {

            try {
                byte[] buf = new byte[getConfiguration().getMaxDatagramBytes()];
                DatagramPacket datagram = new DatagramPacket(buf, buf.length);

                socket.receive(datagram);

                InetAddress receivedOnLocalAddress =
                        networkAddressFactory.getLocalAddress(
                                multicastInterface,
                                multicastAddress.getAddress() instanceof Inet6Address,
                                datagram.getAddress()
                        );

                Log.d(getClass().getName(),
                        "UDP datagram received from: " + datagram.getAddress().getHostAddress()
                                + ":" + datagram.getPort()
                                + " on local interface: " + multicastInterface.getDisplayName()
                                + " and address: " + receivedOnLocalAddress.getHostAddress()
                );

                router.received(datagramProcessor.read(receivedOnLocalAddress, datagram));

            } catch (SocketException ex) {
                Log.d(getClass().getName(), "Socket closed", ex);
                break;
            } catch (UnsupportedDataException ex) {
                Log.d(getClass().getName(), "Could not read datagram: " + ex.getMessage(), ex);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
        try {
            if (!socket.isClosed()) {
                Log.d(getClass().getName(), "Closing multicast socket");
                socket.close();
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }


}

