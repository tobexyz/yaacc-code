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
package de.yaacc.sendspin.hub;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import de.yaacc.sendspin.session.WsConnection;
import de.yaacc.sendspin.session.WsHandshake;

import org.junit.After;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/** The listening side that clients of the client-initiated mode connect to: caps, the filter of addresses, the checks of the upgrade. */
public class InboundServerTest {

    private static final class Arrival {
        final Connector.Link link;
        final InetAddress remote;

        Arrival(Connector.Link link, InetAddress remote) {
            this.link = link;
            this.remote = remote;
        }
    }

    private InboundServer server;
    private final BlockingQueue<Arrival> arrivals = new ArrayBlockingQueue<>(16);

    @After
    public void tearDown() throws IOException {
        if (server != null) {
            server.close();
        }
        Arrival a;
        while ((a = arrivals.poll()) != null) {
            a.link.close();
        }
    }

    private InboundServer start(java.util.function.Predicate<InetAddress> accept, int maxConnections, int timeoutMillis) throws IOException {
        server = new InboundServer(0, "/sendspin", accept, (link, remote) -> arrivals.add(new Arrival(link, remote)), maxConnections,
                timeoutMillis);
        server.start();
        return server;
    }

    private WsConnection connect(String path) throws Exception {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.port());
        socket.setSoTimeout(5000);
        BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
        WsHandshake.connect(in, socket.getOutputStream(), "127.0.0.1", server.port(), path, new SecureRandom());
        return new WsConnection(in, socket.getOutputStream(), true, 1 << 20, new SecureRandom());
    }

    @Test
    public void aClientThatUpgradesOnTheRightPathArrivesWithItsAddress() throws Exception {
        start(address -> true, 4, 2000);
        assertTrue("an ephemeral port was bound", server.port() > 0);
        WsConnection client = connect("/sendspin");
        Arrival arrival = arrivals.poll(5, TimeUnit.SECONDS);
        assertNotNull(arrival);
        assertTrue(arrival.remote.isLoopbackAddress());
        client.sendText("hello");
        assertEquals("hello", new String(arrival.link.ws().receive().data(), StandardCharsets.UTF_8));
    }

    @Test
    public void aRequestForAnotherPathIsRefused() throws Exception {
        start(address -> true, 4, 2000);
        try {
            connect("/other");
        } catch (IOException expected) {
            // the server answers 404 or closes
        }
        assertNull(arrivals.poll(500, TimeUnit.MILLISECONDS));
    }

    @Test
    public void aRequestWithAnOriginHeaderIsRefusedAsTheSpecificationDemands() throws Exception {
        start(address -> true, 4, 2000);
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(("GET /sendspin HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\nOrigin: http://evil.example\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            byte[] answer = new byte[12];
            int read = socket.getInputStream().read(answer);
            assertTrue(new String(answer, 0, Math.max(read, 0), StandardCharsets.US_ASCII).contains("403"));
        }
        assertNull(arrivals.poll(300, TimeUnit.MILLISECONDS));
    }

    @Test
    public void anAddressTheFilterRefusesIsClosedBeforeAnyReading() throws Exception {
        start(address -> false, 4, 2000);
        try {
            connect("/sendspin");
        } catch (IOException expected) {
            // closed
        }
        assertNull(arrivals.poll(500, TimeUnit.MILLISECONDS));
    }

    @Test
    public void atMostTheGivenNumberOfConnectionsAreHeldAtOnce() throws Exception {
        start(address -> true, 2, 2000);
        connect("/sendspin");
        connect("/sendspin");
        Arrival first = arrivals.poll(5, TimeUnit.SECONDS);
        assertNotNull(arrivals.poll(5, TimeUnit.SECONDS));
        try {
            connect("/sendspin");
        } catch (IOException expected) {
            // refused
        }
        assertNull("a third one is not accepted", arrivals.poll(500, TimeUnit.MILLISECONDS));
        first.link.close(); // a closed link makes room
        WsConnection again = connect("/sendspin");
        assertNotNull(arrivals.poll(5, TimeUnit.SECONDS));
        assertNotNull(again);
    }

    @Test
    public void aClientThatSaysNothingIsDroppedAfterTheTimeout() throws Exception {
        start(address -> true, 1, 300);
        try (Socket silent = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
            silent.setSoTimeout(3000);
            assertEquals("the server closes the idle connection", -1, silent.getInputStream().read());
        }
        WsConnection ok = connect("/sendspin"); // and the slot is free again
        assertNotNull(arrivals.poll(5, TimeUnit.SECONDS));
        assertNotNull(ok);
    }

    @Test
    public void closingStopsNewConnectionsButKeepsTheOnesThatAreEstablished() throws Exception {
        start(address -> true, 4, 2000);
        WsConnection client = connect("/sendspin");
        Arrival arrival = arrivals.poll(5, TimeUnit.SECONDS);
        assertNotNull(arrival);
        server.close();
        assertFalse(server.isRunning());
        // nothing of ours accepts any more: a connection is refused, or (the port can be taken by another process at once) it never arrives
        try {
            connect("/sendspin");
        } catch (IOException | RuntimeException expected) {
            // refused, or not our server
        }
        assertNull("no new arrival after close", arrivals.poll(300, TimeUnit.MILLISECONDS));
        client.sendText("still here");
        assertEquals("still here", new String(arrival.link.ws().receive().data(), StandardCharsets.UTF_8));
    }

}
