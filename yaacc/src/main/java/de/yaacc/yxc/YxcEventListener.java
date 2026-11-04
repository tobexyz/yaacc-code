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
package de.yaacc.yxc;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;

import de.yaacc.util.YaaccLogger;

/**
 * Receives the UDP events of the devices on a port the system picks. Parses every datagram with
 * {@link YxcEventParser} (bounded, unparsable ones become {@link YxcEvent#unparsable()}) and hands it on with the
 * address it came from; who may be believed is the receiver's decision. One daemon thread per listener.
 */
public final class YxcEventListener implements YxcEventSource {
    /** Events are a few hundred bytes; a larger datagram is cut and then fails to parse. */
    static final int MAX_DATAGRAM_BYTES = 4096;

    private final DatagramSocket socket;
    private final Receiver receiver;
    private final YxcEventParser parser = new YxcEventParser();
    private volatile boolean closed;

    private YxcEventListener(DatagramSocket socket, Receiver receiver) {
        this.socket = socket;
        this.receiver = receiver;
    }

    public static YxcEventListener start(Receiver receiver) throws IOException {
        DatagramSocket socket = new DatagramSocket(0);
        YxcEventListener listener = new YxcEventListener(socket, receiver);
        Thread thread = new Thread(listener::run, "yxc-events");
        thread.setDaemon(true);
        thread.start();
        return listener;
    }

    @Override
    public int getPort() {
        return socket.getLocalPort();
    }

    @Override
    public void close() {
        closed = true;
        socket.close(); // ends the receive in the thread
    }

    private void run() {
        byte[] buffer = new byte[MAX_DATAGRAM_BYTES];
        while (!closed) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (SocketException e) {
                if (!closed) {
                    YaaccLogger.w(getClass().getName(), "Event socket failed", e);
                }
                return;
            } catch (IOException e) {
                YaaccLogger.w(getClass().getName(), "Event receive failed", e);
                return;
            }
            try {
                if (!receiver.accepts(packet.getAddress())) {
                    continue;
                }
                receiver.onEvent(packet.getAddress(), parser.parse(packet.getData(), packet.getOffset(), packet.getLength()));
            } catch (RuntimeException e) {
                YaaccLogger.w(getClass().getName(), "Event not handled", e);
            }
        }
    }
}
