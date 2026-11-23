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
package de.yaacc.fcast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * A bare FCast v3 sender for the tests: the "second sender" of a test (another controller connected to the same receiver)
 * and the peer that checks {@link FakeFCastReceiver} in its own test. It speaks the wire format through
 * {@link FakeFCastReceiver.Wire}, not through the production codec. Not a model of YAACC's own client.
 */
public final class FakeFCastSender implements AutoCloseable {
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    /** Connects to a receiver on the loopback address. */
    public FakeFCastSender(int port) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), port), 2_000);
        socket.setTcpNoDelay(true);
        in = socket.getInputStream();
        out = socket.getOutputStream();
    }

    public void send(int opcode, String json) throws IOException {
        FakeFCastReceiver.Wire.write(out, opcode, json == null ? null : json.getBytes(StandardCharsets.UTF_8));
    }

    public void sendVersion(int version) throws IOException {
        send(FakeFCastReceiver.Op.VERSION, "{\"version\":" + version + "}");
    }

    public void sendInitial(String displayName) throws IOException {
        send(FakeFCastReceiver.Op.INITIAL, "{\"displayName\":\"" + displayName + "\",\"appName\":\"FakeSender\",\"appVersion\":\"1\"}");
    }

    public void sendRaw(byte[] bytes) throws IOException {
        out.write(bytes);
        out.flush();
    }

    /**
     * The next packet; null when the receiver closed the connection.
     *
     * @throws SocketTimeoutException if nothing arrived within the time
     */
    public FakeFCastReceiver.Packet read(int timeoutMillis) throws IOException {
        socket.setSoTimeout(timeoutMillis);
        return FakeFCastReceiver.Wire.read(in);
    }

    /** Reads until a packet with this opcode arrives and returns it (earlier packets are dropped); null if closed first. */
    public FakeFCastReceiver.Packet readUntil(int opcode, int timeoutMillis) throws IOException {
        long end = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (true) {
            long left = (end - System.nanoTime()) / 1_000_000L;
            if (left <= 0) {
                throw new SocketTimeoutException("no packet with opcode " + opcode + " within " + timeoutMillis + " ms");
            }
            FakeFCastReceiver.Packet packet = read((int) left);
            if (packet == null || packet.opcode == opcode) {
                return packet;
            }
        }
    }

    public boolean isClosedByPeer(int timeoutMillis) {
        try {
            return read(timeoutMillis) == null;
        } catch (SocketTimeoutException e) {
            return false;
        } catch (IOException e) {
            return true; // a reset or a broken stream also means the receiver ended the connection
        }
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // closing
        }
    }
}
