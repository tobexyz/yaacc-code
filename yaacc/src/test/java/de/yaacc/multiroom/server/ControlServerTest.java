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
package de.yaacc.multiroom.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import de.yaacc.multiroom.SnapcastSession;
import de.yaacc.multiroom.control.MonotonicClock;
import de.yaacc.multiroom.control.SnapcastRpc;
import de.yaacc.multiroom.control.SnapcastVolume;
import de.yaacc.multiroom.control.SocketTransport;
import de.yaacc.multiroom.discovery.SnapcastEndpoint;
import de.yaacc.multiroom.protocol.HelloMessage;
import de.yaacc.multiroom.protocol.MicroClock;

import org.junit.After;
import org.junit.Test;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * The control port of the built-in server against the app's own control client ({@link SnapcastSession}, the one that talks to a real
 * snapserver): status, changes, the streams, who is told what, and the limits (issue #330, task 3.2).
 */
public class ControlServerTest {

    private final AtomicInteger groups = new AtomicInteger();
    private final ServerState state = new ServerState("Phone", "0.35.0-yaacc", 1000, () -> "g" + groups.incrementAndGet(),
            System::currentTimeMillis);
    private final SnapcastServer server = new SnapcastServer(state, MicroClock.monotonic(), a -> true, 16, n -> { });
    private ControlServer control;
    private final List<SnapcastSession> sessions = new ArrayList<>();

    @After
    public void tearDown() {
        for (SnapcastSession s : sessions) {
            s.close();
        }
        if (control != null) {
            control.close();
        }
        server.close();
    }

    private ControlServer start(java.util.function.Predicate<InetAddress> accept, int max) throws Exception {
        server.setSourceFactory((pipeline, spec) -> () -> { });
        control = new ControlServer(server, accept, max);
        control.start(0);
        return control;
    }

    private SnapcastSession connect() {
        SnapcastEndpoint endpoint = SnapcastEndpoint.parse("127.0.0.1:" + control.port());
        SnapcastRpc.Config config = new SnapcastRpc.Config().callTimeoutMs(5_000).readTimeoutMs(2_000).backoffInitialMs(20).backoffMaxMs(50);
        SnapcastSession session = new SnapcastSession(endpoint.toKey(), new SocketTransport(endpoint.getAddress(), endpoint.getPort()), config,
                MonotonicClock.SYSTEM, (task, delay) -> new Thread(() -> {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        return;
                    }
                    task.run();
                }).start());
        sessions.add(session);
        session.start();
        return session;
    }

    private static void waitFor(String what, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(what, condition.getAsBoolean());
    }

    private void client(String id) {
        state.clientConnected(HelloMessage.create(id, "h", "YAACC", "Android", "arm", "1"), "10.0.0.1");
    }

    @Test(timeout = 60000)
    public void theAppsOwnClientReadsTheStatusOfTheBuiltInServer() throws Exception {
        start(a -> true, 4);
        client("c1");
        SnapcastSession session = connect();
        waitFor("online", session::isOnline);
        waitFor("status", () -> session.getStatus() != null && session.getStatus().findClient("c1") != null);
        assertEquals("Phone", session.getStatus().getInfo().getHostName());
    }

    @Test(timeout = 60000)
    public void aVolumeSetByTheAppReachesTheState() throws Exception {
        start(a -> true, 4);
        client("c1");
        SnapcastSession session = connect();
        waitFor("status", () -> session.getStatus() != null && session.getStatus().findClient("c1") != null);
        session.getOperations().setClientVolume("c1", new SnapcastVolume(33, true));
        assertEquals(33, state.settingsOf("c1").getVolume());
        assertTrue(state.settingsOf("c1").isMuted());
    }

    @Test(timeout = 60000)
    public void aStreamAddedByTheAppAppearsAndASecondControllerIsToldButTheFirstIsNot() throws Exception {
        start(a -> true, 4);
        SnapcastSession first = connect();
        SnapcastSession second = connect();
        waitFor("both online", () -> first.isOnline() && second.isOnline() && first.getStatus() != null && second.getStatus() != null);
        String id = first.getOperations().addStream("tcp://0.0.0.0:4953?name=YAACC%20one&sampleformat=48000:16:2");
        assertEquals("YAACC one", id);
        assertTrue(state.hasStream("YAACC one"));
        waitFor("the second controller sees the stream", () -> second.getStatus().findStream("YAACC one") != null);
        waitFor("the first one reads the stream back after its own call", () -> first.getStatus().findStream("YAACC one") != null);
        assertNotNull(first.getStatus().findStream("YAACC one"));
    }

    @Test(timeout = 60000)
    public void aStreamThatIsNotTcpIsRefusedWithAReason() throws Exception {
        start(a -> true, 4);
        SnapcastSession session = connect();
        waitFor("online", session::isOnline);
        try {
            session.getOperations().addStream("process:///bin/sh?name=x");
            org.junit.Assert.fail("refused");
        } catch (de.yaacc.multiroom.control.SnapcastException e) {
            assertEquals(-32602, e.getCode());
        }
        assertFalse(state.hasStream("x"));
    }

    @Test(timeout = 60000)
    public void anAddressTheFilterRefusesIsClosedBeforeAnythingIsRead() throws Exception {
        start(a -> false, 4);
        try (Socket raw = new Socket(InetAddress.getLoopbackAddress(), control.port())) {
            raw.setSoTimeout(5000);
            assertEquals(-1, raw.getInputStream().read());
        }
    }

    @Test(timeout = 60000)
    public void atMostTheGivenNumberOfConnectionsAreServed() throws Exception {
        start(a -> true, 1);
        try (Socket first = new Socket(InetAddress.getLoopbackAddress(), control.port());
             Socket second = new Socket(InetAddress.getLoopbackAddress(), control.port())) {
            second.setSoTimeout(5000);
            assertEquals("the second is closed at once", -1, second.getInputStream().read());
            first.getOutputStream().write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"Server.GetRPCVersion\"}\n".getBytes(StandardCharsets.UTF_8));
            first.getOutputStream().flush();
            first.setSoTimeout(5000);
            assertTrue("the first one is served", first.getInputStream().read() > 0);
        }
    }

    @Test(timeout = 60000)
    public void aLineOfNoEndIsCutAtTheCapAndTheConnectionDropped() throws Exception {
        start(a -> true, 4);
        try (Socket raw = new Socket(InetAddress.getLoopbackAddress(), control.port())) {
            raw.setSoTimeout(10000);
            OutputStream out = raw.getOutputStream();
            byte[] junk = new byte[8192];
            java.util.Arrays.fill(junk, (byte) 'a');
            try {
                for (int i = 0; i < 64; i++) { // 512 KiB without a line end
                    out.write(junk);
                }
                out.flush();
            } catch (java.io.IOException closed) {
                // the server closed already
            }
            assertEquals("the connection is dropped", -1, raw.getInputStream().read());
        }
    }

    @Test(timeout = 60000)
    public void aLineThatIsNotJsonGetsAParseError() throws Exception {
        start(a -> true, 4);
        try (Socket raw = new Socket(InetAddress.getLoopbackAddress(), control.port())) {
            raw.setSoTimeout(5000);
            raw.getOutputStream().write("this is not json\n".getBytes(StandardCharsets.UTF_8));
            raw.getOutputStream().flush();
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(raw.getInputStream(), StandardCharsets.UTF_8));
            String answer = reader.readLine();
            assertTrue(answer, answer.contains("-32700"));
        }
    }

    @Test(timeout = 60000)
    public void aBatchIsAnsweredWithABatch() throws Exception {
        start(a -> true, 4);
        try (Socket raw = new Socket(InetAddress.getLoopbackAddress(), control.port())) {
            raw.setSoTimeout(5000);
            raw.getOutputStream().write(("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"Server.GetRPCVersion\"},"
                    + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"Server.GetStatus\"}]\n").getBytes(StandardCharsets.UTF_8));
            raw.getOutputStream().flush();
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(raw.getInputStream(), StandardCharsets.UTF_8));
            org.json.JSONArray answers = new org.json.JSONArray(reader.readLine());
            assertEquals(2, answers.length());
        }
    }
}
