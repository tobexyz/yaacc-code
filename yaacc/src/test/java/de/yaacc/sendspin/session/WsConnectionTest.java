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
package de.yaacc.sendspin.session;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/** Frames, fragments, control frames and every violation, over a real loopback connection with hand-made frames on the raw side. */
public class WsConnectionTest {

    private final List<Socket> sockets = new ArrayList<>();

    @After
    public void tearDown() throws IOException {
        for (Socket socket : sockets) {
            socket.close();
        }
    }

    /** A client connection (the one under test) and the raw streams of the server end. */
    private static final class Pair {
        WsConnection ws;
        InputStream rawIn;
        OutputStream rawOut;
        Socket clientSocket;
    }

    private Pair connect(boolean clientUnderTest, int max) throws IOException {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Socket a = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
            Socket b = server.accept();
            sockets.add(a);
            sockets.add(b);
            Pair pair = new Pair();
            Socket under = clientUnderTest ? a : b;
            Socket raw = clientUnderTest ? b : a;
            pair.clientSocket = under;
            pair.ws = new WsConnection(new BufferedInputStream(under.getInputStream()), under.getOutputStream(), clientUnderTest, max,
                    new SecureRandom());
            pair.rawIn = new BufferedInputStream(raw.getInputStream());
            pair.rawOut = raw.getOutputStream();
            under.setSoTimeout(2000);
            raw.setSoTimeout(2000);
            return pair;
        }
    }

    /** One frame as a server writes it (unmasked), fin set. */
    private static byte[] frame(int opcode, boolean fin, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((fin ? 0x80 : 0) | opcode);
        if (payload.length < 126) {
            out.write(payload.length);
        } else if (payload.length <= 0xFFFF) {
            out.write(126);
            out.write(payload.length >> 8);
            out.write(payload.length);
        } else {
            out.write(127);
            for (int shift = 56; shift >= 0; shift -= 8) {
                out.write((int) ((long) payload.length >> shift));
            }
        }
        out.write(payload, 0, payload.length);
        return out.toByteArray();
    }

    /** Reads one frame the client wrote: {opcode|fin<<4, masked flag, payload unmasked}. */
    private static Object[] readClientFrame(InputStream in) throws IOException {
        int b0 = in.read();
        int b1 = in.read();
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) {
            len = (in.read() << 8) | in.read();
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) {
                len = (len << 8) | in.read();
            }
        }
        byte[] mask = new byte[4];
        if (masked) {
            for (int i = 0; i < 4; i++) {
                mask[i] = (byte) in.read();
            }
        }
        byte[] payload = new byte[(int) len];
        int off = 0;
        while (off < payload.length) {
            off += in.read(payload, off, payload.length - off);
        }
        if (masked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= mask[i & 3];
            }
        }
        return new Object[]{b0 & 0x0F, masked, payload};
    }

    @Test
    public void aClientMasksWhatItSendsAndAServerDoesNot() throws Exception {
        Pair client = connect(true, 1 << 20);
        client.ws.sendText("hello");
        Object[] frame = readClientFrame(client.rawIn);
        assertEquals(1, frame[0]);
        assertTrue("a client masks", (Boolean) frame[1]);
        assertEquals("hello", new String((byte[]) frame[2], StandardCharsets.UTF_8));

        Pair server = connect(false, 1 << 20);
        server.ws.sendBinary(new byte[]{1, 2, 3});
        byte[] raw = new byte[5];
        int off = 0;
        while (off < 5) {
            off += server.rawIn.read(raw, off, 5 - off);
        }
        assertArrayEquals("fin+binary, length 3, no mask, payload", new byte[]{(byte) 0x82, 3, 1, 2, 3}, raw);
    }

    @Test
    public void messagesOfEverySizeClassRoundTrip() throws Exception {
        for (int size : new int[]{0, 1, 125, 126, 65535, 65536, 200000}) {
            Pair client = connect(true, 1 << 20);
            byte[] data = new byte[size];
            new SecureRandom().nextBytes(data);
            client.rawOut.write(frame(2, true, data));
            WsConnection.Message message = client.ws.receive();
            assertFalse(message.isText());
            assertArrayEquals("size " + size, data, message.data());
        }
    }

    @Test
    public void fragmentedMessagesAreReassembledAndControlFramesMayInterleave() throws Exception {
        Pair client = connect(true, 1 << 20);
        client.rawOut.write(frame(1, false, "he".getBytes(StandardCharsets.UTF_8)));
        client.rawOut.write(frame(9, true, new byte[]{7})); // a ping between the fragments
        client.rawOut.write(frame(0, false, "ll".getBytes(StandardCharsets.UTF_8)));
        client.rawOut.write(frame(0, true, "o".getBytes(StandardCharsets.UTF_8)));
        WsConnection.Message message = client.ws.receive();
        assertTrue(message.isText());
        assertEquals("hello", message.text());
        Object[] pong = readClientFrame(client.rawIn);
        assertEquals("the ping was answered", 10, pong[0]);
        assertArrayEquals(new byte[]{7}, (byte[]) pong[2]);
    }

    @Test
    public void aCloseFrameIsAnsweredAndEndsTheConnection() throws Exception {
        Pair client = connect(true, 1 << 20);
        byte[] payload = new byte[]{(byte) (1001 >> 8), (byte) 1001, 'b', 'y', 'e'};
        client.rawOut.write(frame(8, true, payload));
        try {
            client.ws.receive();
            fail("expected a close");
        } catch (WsConnection.ClosedException e) {
            assertEquals(1001, e.code());
        }
        Object[] answer = readClientFrame(client.rawIn);
        assertEquals(8, answer[0]);
        byte[] echoed = (byte[]) answer[2];
        assertEquals(1001, ((echoed[0] & 0xFF) << 8) | (echoed[1] & 0xFF));
        try {
            client.ws.sendText("late");
            fail("nothing is sent after the close");
        } catch (IOException expected) {
            // fine
        }
    }

    private void expectViolation(byte[] raw, int expectedCode, boolean clientUnderTest) throws Exception {
        Pair pair = connect(clientUnderTest, 1000);
        pair.rawOut.write(raw);
        pair.rawOut.flush();
        try {
            pair.ws.receive();
            fail("expected a violation");
        } catch (WsConnection.ViolationException e) {
            assertEquals(expectedCode, e.code());
        }
        Object[] close = readClientFrame(pair.rawIn);
        assertEquals("a close frame with the code", 8, close[0]);
        byte[] payload = (byte[]) close[2];
        assertEquals(expectedCode, ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF));
    }

    @Test
    public void protocolViolationsCloseWithTheMatchingCode() throws Exception {
        expectViolation(new byte[]{(byte) 0xC1, 0}, 1002, true); // reserved bit
        expectViolation(new byte[]{(byte) 0x83, 0}, 1002, true); // unknown opcode
        expectViolation(frame(9, false, new byte[0]), 1002, true); // a fragmented control frame
        expectViolation(frame(9, true, new byte[126]), 1002, true); // control payload too long
        expectViolation(frame(0, true, new byte[1]), 1002, true); // continuation without a message
        byte[] interleaved = concat(frame(1, false, new byte[]{'a'}), frame(1, true, new byte[]{'b'}));
        expectViolation(interleaved, 1002, true); // a new message inside a fragmented one
        expectViolation(new byte[]{(byte) 0x81, (byte) 0x80, 0, 0, 0, 0}, 1002, true); // a masked frame from a server
        expectViolation(frame(1, true, new byte[]{'x'}), 1002, false); // an unmasked frame to a server
        expectViolation(frame(8, true, new byte[]{1}), 1002, true); // a close frame with one byte
        expectViolation(frame(8, true, new byte[]{0, 5}), 1002, true); // 5 is no close code
    }

    @Test
    public void tooBigAndNotUtf8AreTheirOwnCodes() throws Exception {
        expectViolation(frame(2, true, new byte[1001]), 1009, true); // one frame over the limit of 1000
        expectViolation(concat(frame(2, false, new byte[600]), frame(0, true, new byte[600])), 1009, true); // the sum is over it
        expectViolation(frame(1, true, new byte[]{(byte) 0xC3, 0x28}), 1007, true); // not UTF-8
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @Test
    public void aTimeoutBetweenFramesLeavesTheConnectionUsable() throws Exception {
        Pair client = connect(true, 1 << 20);
        client.clientSocket.setSoTimeout(100);
        try {
            client.ws.receive();
            fail("expected a timeout");
        } catch (SocketTimeoutException expected) {
            // fine: nothing arrived
        }
        client.rawOut.write(frame(1, true, "still here".getBytes(StandardCharsets.UTF_8)));
        assertEquals("still here", client.ws.receive().text());
    }

    @Test
    public void aTimeoutInsideAFrameIsAFailure() throws Exception {
        Pair client = connect(true, 1 << 20);
        client.clientSocket.setSoTimeout(100);
        client.rawOut.write(new byte[]{(byte) 0x81, 10, 'a'}); // 10 bytes announced, one sent
        client.rawOut.flush();
        try {
            client.ws.receive();
            fail("expected a failure");
        } catch (SocketTimeoutException e) {
            fail("a timeout inside a frame must not look like an idle connection");
        } catch (IOException expected) {
            // fine
        }
    }

    @Test
    public void aPeerThatClosesTheSocketWithoutACloseFrameIsAnError() throws Exception {
        Pair client = connect(true, 1 << 20);
        client.rawOut.close(); // closes the socket of the raw end
        try {
            client.ws.receive();
            fail("expected an error");
        } catch (IOException expected) {
            assertFalse(expected instanceof WsConnection.ClosedException);
        }
    }

    @Test
    public void pingsGoOutAndPongsAreCounted() throws Exception {
        Pair client = connect(true, 1 << 20);
        client.ws.sendPing(new byte[]{1, 2});
        Object[] ping = readClientFrame(client.rawIn);
        assertEquals(9, ping[0]);
        assertEquals(0L, client.ws.lastPongNanos());
        client.rawOut.write(frame(10, true, (byte[]) ping[2]));
        client.rawOut.write(frame(1, true, new byte[]{'x'}));
        assertEquals("x", client.ws.receive().text());
        assertTrue(client.ws.lastPongNanos() != 0L);
    }
}
