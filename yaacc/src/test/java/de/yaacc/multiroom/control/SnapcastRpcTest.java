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
package de.yaacc.multiroom.control;

import static de.yaacc.multiroom.control.FakeSnapcastServer.fixtureWithId;
import static de.yaacc.multiroom.control.FakeSnapcastServer.result;
import static de.yaacc.multiroom.control.RpcTestSupport.await;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import de.yaacc.multiroom.control.RpcTestSupport.BlockingListener;
import de.yaacc.multiroom.control.RpcTestSupport.FailingTransport;
import de.yaacc.multiroom.control.RpcTestSupport.ManualClock;
import de.yaacc.multiroom.control.RpcTestSupport.ManualScheduler;
import de.yaacc.multiroom.control.RpcTestSupport.RecordingListener;
import de.yaacc.multiroom.control.RpcTestSupport.StuckWriteConnection;
import de.yaacc.multiroom.control.RpcTestSupport.ThrowingConnection;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** The JSON-RPC client against a fake control server on the loopback address (no sleeping for backoff). */
public class SnapcastRpcTest {

    private final List<AutoCloseable> toClose = new ArrayList<>();
    private final ExecutorService listenerExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "test-listener");
        thread.setDaemon(true);
        return thread;
    });
    private final ManualScheduler scheduler = new ManualScheduler();
    private final RecordingListener listener = new RecordingListener();
    private final ManualClock clock = new ManualClock();

    /** The rpc threads that existed before the test: only the ones the test created are looked at. */
    private Set<Thread> threadsBefore;

    @Before
    public void rememberThreads() {
        threadsBefore = snapcastThreads();
    }

    @After
    public void tearDown() {
        for (int i = toClose.size() - 1; i >= 0; i--) {
            try {
                toClose.get(i).close();
            } catch (Exception ignored) {
                // closing
            }
        }
        listenerExecutor.shutdownNow();
    }

    private FakeSnapcastServer server(FakeSnapcastServer.Script script) throws Exception {
        FakeSnapcastServer server = new FakeSnapcastServer(script);
        toClose.add(server);
        return server;
    }

    private FakeSnapcastServer responding(FakeSnapcastServer.Responder responder) throws Exception {
        FakeSnapcastServer server = FakeSnapcastServer.responding(responder);
        toClose.add(server);
        return server;
    }

    private static SnapcastRpc.Config fastConfig() {
        return new SnapcastRpc.Config().callTimeoutMs(3_000).readTimeoutMs(2_000).connectTimeoutMs(2_000)
                .lineDeadlineMs(3_000);
    }

    private SnapcastRpc rpc(int port, SnapcastRpc.Config config) {
        SnapcastRpc rpc = new SnapcastRpc(new SocketTransport("127.0.0.1", port), config, scheduler, listenerExecutor, clock);
        rpc.addListener(listener);
        toClose.add(rpc);
        return rpc;
    }

    private SnapcastRpc connected(FakeSnapcastServer server, SnapcastRpc.Config config) {
        SnapcastRpc rpc = rpc(server.port(), config);
        rpc.start();
        assertTrue("connected", await(() -> rpc.getState() == SnapcastRpc.State.CONNECTED, 5_000));
        return rpc;
    }

    private static List<String> lines(String... lines) {
        return Arrays.asList(lines);
    }

    // ---- requests and responses

    @Test
    public void requestAndResponseAreMatchedById() throws Exception {
        final List<JSONObject> requests = Collections.synchronizedList(new ArrayList<JSONObject>());
        FakeSnapcastServer server = responding(request -> {
            requests.add(request);
            return lines(result(request.get("id"), "{\"mute\":true}"));
        });
        SnapcastRpc rpc = connected(server, fastConfig());

        JSONObject answer = rpc.call("Group.SetMute", new JSONObject().put("id", "g").put("mute", true));
        JSONObject second = rpc.call("Server.GetStatus", null);

        assertTrue(answer.getBoolean("mute"));
        assertTrue(second.getBoolean("mute"));
        assertEquals(2, requests.size());
        JSONObject first = requests.get(0);
        assertEquals("2.0", first.getString("jsonrpc"));
        assertEquals("Group.SetMute", first.getString("method"));
        assertTrue(SnapcastFixtures.similar(new JSONObject().put("id", "g").put("mute", true), first.getJSONObject("params")));
        assertTrue("the id is a number generated here", first.get("id") instanceof Number);
        assertNotEquals(first.getLong("id"), requests.get(1).getLong("id"));
        assertFalse("no params when none given", requests.get(1).has("params"));
    }

    @Test
    public void twoCallsAnsweredInReverseOrderGetTheirOwnAnswers() throws Exception {
        final List<JSONObject> held = new ArrayList<>();
        FakeSnapcastServer server = responding(request -> {
            synchronized (held) {
                held.add(request);
                if (held.size() < 2) {
                    return null;
                }
                JSONObject first = held.get(0);
                JSONObject secondRequest = held.get(1);
                return lines(
                        result(secondRequest.get("id"), "{\"echo\":" + secondRequest.getJSONObject("params").getInt("n") + "}"),
                        result(first.get("id"), "{\"echo\":" + first.getJSONObject("params").getInt("n") + "}"));
            }
        });
        final SnapcastRpc rpc = connected(server, fastConfig());
        ExecutorService callers = Executors.newFixedThreadPool(2);
        toClose.add(callers::shutdownNow);

        Future<JSONObject> one = callers.submit(() -> rpc.call("X.One", new JSONObject().put("n", 1)));
        assertTrue(await(() -> rpc.getPendingCount() == 1, 2_000));
        Future<JSONObject> two = callers.submit(() -> rpc.call("X.Two", new JSONObject().put("n", 2)));

        assertEquals(1, one.get(5, TimeUnit.SECONDS).getInt("echo"));
        assertEquals(2, two.get(5, TimeUnit.SECONDS).getInt("echo"));
        assertEquals(0, rpc.getPendingCount());
    }

    @Test
    public void aNotificationBetweenTwoResponsesIsDeliveredOffTheSocketThread() throws Exception {
        FakeSnapcastServer server = server(connection -> {
            JSONObject first = connection.readRequest();
            connection.send(SnapcastFixtures.read("notification_group_on_mute.json").replace("\n", " "));
            connection.send(result(first.get("id"), "{\"n\":1}"));
            JSONObject second = connection.readRequest();
            connection.send(SnapcastFixtures.read("notification_client_on_volume_changed.json").replace("\n", " "));
            connection.send(result(second.get("id"), "{\"n\":2}"));
            connection.readLine();
        });
        SnapcastRpc rpc = connected(server, fastConfig());

        assertEquals(1, rpc.call("A.One", null).getInt("n"));
        assertEquals(2, rpc.call("A.Two", null).getInt("n"));

        assertTrue(listener.awaitCount("notification:", 2, 3_000));
        assertEquals(Arrays.asList("notification:Group.OnMute", "notification:Client.OnVolumeChanged"),
                filter(listener.log(), "notification:"));
        assertTrue(listener.notifications.get(0).getBoolean("mute"));
        for (String thread : listener.threads) {
            assertEquals("test-listener", thread);
        }
    }

    private static List<String> filter(List<String> log, String prefix) {
        List<String> result = new ArrayList<>();
        for (String entry : log) {
            if (entry.startsWith(prefix)) {
                result.add(entry);
            }
        }
        return result;
    }

    @Test
    public void theDefaultListenerThreadIsNotTheSocketThread() throws Exception {
        FakeSnapcastServer server = server(connection -> {
            connection.send(SnapcastFixtures.read("notification_group_on_mute.json").replace("\n", " "));
            connection.readLine();
        });
        SnapcastRpc rpc = new SnapcastRpc(new SocketTransport("127.0.0.1", server.port()), fastConfig(), scheduler, null, clock);
        rpc.addListener(listener);
        toClose.add(rpc);

        rpc.start();

        assertTrue(listener.awaitCount("notification:", 1, 5_000));
        String thread = listener.threads.get(0);
        assertFalse(thread, thread.contains("reader"));
        assertTrue(thread, thread.startsWith("snapcast-rpc"));
    }

    @Test
    public void batchesOfNotificationsAndResponsesAreAccepted() throws Exception {
        FakeSnapcastServer server = server(connection -> {
            connection.send(SnapcastFixtures.read("batch_notifications.ndjson").trim());
            JSONObject request = connection.readRequest();
            connection.send("[" + result(request.get("id"), "{\"ok\":true}") + ","
                    + SnapcastFixtures.read("notification_group_on_mute.json").replace("\n", " ") + "]");
            connection.readLine();
        });
        SnapcastRpc rpc = connected(server, fastConfig());

        JSONObject answer = rpc.call("A.B", null);

        assertTrue(answer.getBoolean("ok"));
        assertTrue(listener.awaitCount("notification:", 3, 3_000));
    }

    @Test
    public void aServerRequestAndAnUnknownResponseAreIgnored() throws Exception {
        FakeSnapcastServer server = responding(request -> lines(
                "{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"Client.OnConnect\",\"params\":{}}",
                result(424242, "{}"),
                result("not-a-number", "{}"),
                result(request.get("id"), "{\"ok\":1}")));
        SnapcastRpc rpc = connected(server, fastConfig());

        assertEquals(1, rpc.call("A.B", null).getInt("ok"));
        assertEquals(0, listener.count("notification:"));
        assertEquals(0, listener.count("lost"));
    }

    // ---- errors

    @Test
    public void errorsBecomeExceptionsWithCodeMessageAndData() throws Exception {
        FakeSnapcastServer server = responding(request -> {
            String method = request.getString("method");
            if (method.equals("A.Unauthorized")) {
                return lines(fixtureWithId("error_unauthorized.json", request.get("id")).replace("\n", " "));
            }
            if (method.equals("A.Forbidden")) {
                return lines(fixtureWithId("error_forbidden.json", request.get("id")));
            }
            return lines(fixtureWithId("error_internal.json", request.get("id")));
        });
        SnapcastRpc rpc = connected(server, fastConfig());

        try {
            rpc.call("A.Internal", null);
            fail();
        } catch (SnapcastException e) {
            assertEquals(-32603, e.getCode());
            assertEquals("Internal error", e.getMessage());
            assertEquals("Stream with name 'YAACC test phone' already exists", e.getData());
            assertEquals(SnapcastException.Kind.SERVER_ERROR, e.getKind());
        }
        try {
            rpc.call("A.Unauthorized", null);
            fail();
        } catch (SnapcastException e) {
            assertTrue(e.isUnauthorized());
        }
        try {
            rpc.call("A.Forbidden", null);
            fail();
        } catch (SnapcastException e) {
            assertTrue(e.isForbidden());
        }
        assertEquals("the connection survives errors", SnapcastRpc.State.CONNECTED, rpc.getState());
    }

    @Test
    public void aResponseWithoutResultOrErrorFailsTheCallWithATypedException() throws Exception {
        FakeSnapcastServer server = responding(request ->
                lines("{\"jsonrpc\":\"2.0\",\"id\":" + request.get("id") + "}"));
        SnapcastRpc rpc = connected(server, fastConfig());

        try {
            rpc.call("A.B", null);
            fail();
        } catch (SnapcastException e) {
            assertEquals(SnapcastException.Kind.MALFORMED, e.getKind());
        }
    }

    @Test
    public void fullStatusResponseOfTheFixtureParses() throws Exception {
        FakeSnapcastServer server = responding(request ->
                lines(fixtureWithId("status_three_groups.json", request.get("id")).replace("\n", " ")));
        SnapcastRpc rpc = connected(server, fastConfig());

        SnapcastServerStatus status = new SnapcastOperations(rpc).getStatus();

        assertEquals(3, status.getGroups().size());
    }

    // ---- deadlines

    @Test
    public void aCallWithoutAnyReplyDropsTheConnectionAndStartsTheReconnectCycle() throws Exception {
        FakeSnapcastServer server = server(connection -> {
            while (connection.readLine() != null) {
                // reads, never answers, sends nothing: a box that was switched off looks like this
            }
        });
        SnapcastRpc rpc = connected(server, fastConfig().callTimeoutMs(300));

        long start = System.nanoTime();
        try {
            rpc.call("A.B", null);
            fail();
        } catch (SnapcastException e) {
            assertEquals(SnapcastException.Kind.TIMEOUT, e.getKind());
        }

        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2_000);
        assertEquals(0, rpc.getPendingCount());
        assertTrue(listener.awaitCount("disconnected:", 1, 3_000));
        assertTrue(await(() -> scheduler.pending() == 1, 3_000));
        assertEquals(SnapcastRpc.State.WAITING_RETRY, rpc.getState());
        scheduler.runNext();
        assertTrue(await(() -> rpc.getState() == SnapcastRpc.State.CONNECTED, 5_000));
        assertEquals(2, server.acceptedCount());
    }

    @Test
    public void aSlowAnswerOnALiveConnectionIsNoReasonToDropIt() throws Exception {
        // the server sends other traffic (a notification) but not the answer: it is alive, only slow
        FakeSnapcastServer server = responding(request ->
                lines(SnapcastFixtures.read("notification_group_on_mute.json").replace("\n", " ")));
        SnapcastRpc rpc = connected(server, fastConfig().callTimeoutMs(300));

        try {
            rpc.call("A.B", null);
            fail();
        } catch (SnapcastException e) {
            assertEquals(SnapcastException.Kind.TIMEOUT, e.getKind());
        }

        assertEquals(SnapcastRpc.State.CONNECTED, rpc.getState());
        assertEquals(0, listener.count("disconnected:"));
        assertEquals(0, scheduler.pending());
    }

    @Test
    public void aLateAnswerAfterTheDeadlineDoesNotDisturbTheNextCall() throws Exception {
        final AtomicInteger seen = new AtomicInteger();
        FakeSnapcastServer server = responding(request -> {
            if (seen.incrementAndGet() == 1) {
                // the first call gets no answer in time, but the server is alive (other traffic) ...
                return lines(SnapcastFixtures.read("notification_group_on_mute.json").replace("\n", " "));
            }
            return lines(result(request.get("id") instanceof Number ? ((Number) request.get("id")).longValue() - 1 : 0, "{\"late\":1}"),
                    result(request.get("id"), "{\"now\":1}"));
        });
        SnapcastRpc rpc = connected(server, fastConfig().callTimeoutMs(200));

        try {
            rpc.call("A.First", null);
            fail();
        } catch (SnapcastException e) {
            assertEquals(SnapcastException.Kind.TIMEOUT, e.getKind());
        }
        JSONObject second = rpc.call("A.Second", null);

        assertEquals(1, second.getInt("now"));
        assertFalse(second.has("late"));
    }

    @Test
    public void aDrippingSenderHitsTheCallDeadlineAndTheLineDeadline() throws Exception {
        FakeSnapcastServer server = server(connection -> {
            JSONObject request = connection.readRequest();
            byte[] answer = result(request.get("id"), "{\"slow\":true}").getBytes("UTF-8");
            for (byte b : answer) {
                connection.sendRaw(new byte[]{b});
                Thread.sleep(60); // one byte per 60 ms: the line would take seconds
            }
        });
        // the line deadline needs the real clock
        SnapcastRpc rpc = new SnapcastRpc(new SocketTransport("127.0.0.1", server.port()),
                fastConfig().callTimeoutMs(400).lineDeadlineMs(400).readTimeoutMs(1_000), scheduler, listenerExecutor,
                MonotonicClock.SYSTEM);
        rpc.addListener(listener);
        toClose.add(rpc);
        rpc.start();
        assertTrue(await(() -> rpc.getState() == SnapcastRpc.State.CONNECTED, 5_000));

        long start = System.nanoTime();
        try {
            rpc.call("A.B", null);
            fail();
        } catch (SnapcastException e) {
            assertEquals(SnapcastException.Kind.TIMEOUT, e.getKind());
        }

        assertTrue("took " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1_500);
        assertTrue("the slow line costs the connection", listener.awaitCount("disconnected:", 1, 3_000));
    }

    @Test
    public void aSenderThatStallsInTheMiddleOfALineLosesTheConnection() throws Exception {
        FakeSnapcastServer server = server(connection -> {
            connection.sendRaw("{\"jsonrpc\":\"2.0\",\"met".getBytes("UTF-8"));
            Thread.sleep(5_000);
        });
        SnapcastRpc rpc = rpc(server.port(), fastConfig().readTimeoutMs(150).lineDeadlineMs(10_000));
        rpc.start();

        assertTrue(listener.awaitCount("disconnected:TIMEOUT", 1, 3_000));
    }

    // ---- order of an answer and the notifications behind it

    private static final String FIRST_CLIENT = "02:00:00:00:00:01";

    @Test
    public void aStatusAnswerAndTheNotificationBehindItAreAppliedInLineOrder() throws Exception {
        final String notification = SnapcastFixtures.read("notification_client_on_volume_changed.json").replace("\n", " ");
        FakeSnapcastServer server = server(connection -> {
            while (true) {
                JSONObject request = connection.readRequest();
                if (request == null) {
                    return;
                }
                // the answer (volume 70) and the notification behind it (volume 74) in one write
                String answer = fixtureWithId("status_one_group.json", request.get("id")).replace("\n", " ");
                connection.sendRaw((answer + "\n" + notification + "\n").getBytes("UTF-8"));
            }
        });
        final SnapcastModelUpdater updater = new SnapcastModelUpdater(clock, () -> { });
        final AtomicInteger applied = new AtomicInteger();
        // setStatus runs inside the ordered call, between the notifications, not on this thread afterwards
        SnapcastRpc rpc = rpc(server.port(), fastConfig());
        rpc.addListener(new SnapcastRpc.Listener() {
            @Override
            public void onNotification(String method, JSONObject params) {
                updater.apply(method, params);
                applied.incrementAndGet();
            }
        });
        rpc.start();
        assertTrue(await(() -> rpc.getState() == SnapcastRpc.State.CONNECTED, 5_000));
        SnapcastOperations operations = new SnapcastOperations(rpc, updater);

        for (int round = 1; round <= 1_500; round++) {
            operations.getStatus();
            final int expected = round;
            assertTrue("round " + round, await(() -> applied.get() >= expected, 5_000));
            assertEquals("round " + round + ": the older status must not overwrite the newer notification", 74,
                    updater.getStatus().findClient(FIRST_CLIENT).getVolume().getPercent());
        }
    }

    // ---- ordered results

    @Test
    public void ourOwnChangeIsAppliedToTheModelBecauseTheServerDoesNotNotifyUs() throws Exception {
        FakeSnapcastServer server = responding(request -> {
            if (request.getString("method").equals("Server.GetStatus")) {
                return lines(fixtureWithId("status_one_group.json", request.get("id")).replace("\n", " "));
            }
            // the server answers the caller and tells the OTHER connections; this one hears nothing more
            return lines(result(request.get("id"), "{\"volume\":{\"muted\":true,\"percent\":33}}"));
        });
        SnapcastRpc rpc = connected(server, fastConfig());
        SnapcastModelUpdater updater = new SnapcastModelUpdater(clock, () -> { });
        SnapcastOperations operations = new SnapcastOperations(rpc, updater);
        operations.getStatus();

        operations.setClientVolume(FIRST_CLIENT, new SnapcastVolume(33, true));

        assertEquals(new SnapcastVolume(33, true), updater.getStatus().findClient(FIRST_CLIENT).getVolume());
    }

    @Test
    public void anOrderedHandlerThatFailsFailsTheCallWithATypedException() throws Exception {
        FakeSnapcastServer server = responding(request -> lines(result(request.get("id"), "{\"x\":1}")));
        SnapcastRpc rpc = connected(server, fastConfig());

        try {
            rpc.callOrdered("A.B", null, (SnapcastCaller.ResultHandler<String>) value -> {
                throw SnapcastException.malformed("handler says no");
            });
            fail();
        } catch (SnapcastException e) {
            assertEquals(SnapcastException.Kind.MALFORMED, e.getKind());
        }
        try {
            rpc.callOrdered("A.B", null, (SnapcastCaller.ResultHandler<String>) value -> {
                throw new IllegalStateException("bug in a handler");
            });
            fail();
        } catch (SnapcastException e) {
            assertEquals(SnapcastException.Kind.INTERNAL, e.getKind());
        }
        assertEquals("v", rpc.callOrdered("A.B", null, value -> "v"));
        assertEquals(SnapcastRpc.State.CONNECTED, rpc.getState());
    }

    @Test
    public void anOrderedCallFromAListenerFailsAtOnceInsteadOfWaitingForItself() throws Exception {
        final String trigger = SnapcastFixtures.read("notification_group_on_mute.json").replace("\n", " ");
        FakeSnapcastServer server = server(connection -> {
            connection.send(trigger);
            connection.readLine();
        });
        final AtomicReference<Throwable> seen = new AtomicReference<>();
        final SnapcastRpc rpc = rpc(server.port(), fastConfig().callTimeoutMs(20_000));
        rpc.addListener(new SnapcastRpc.Listener() {
            @Override
            public void onNotification(String method, JSONObject params) {
                try {
                    rpc.callOrdered("A.B", null, value -> "never");
                } catch (Throwable t) {
                    seen.set(t);
                }
            }
        });
        rpc.start();

        assertTrue(await(() -> seen.get() != null, 5_000));
        assertTrue(String.valueOf(seen.get()), seen.get() instanceof IllegalStateException);
    }

    @Test
    public void closingFailsAnOrderedResultThatIsStillWaitingForTheListeners() throws Exception {
        final String trigger = SnapcastFixtures.read("notification_group_on_mute.json").replace("\n", " ");
        FakeSnapcastServer server = server(connection -> {
            connection.send(trigger); // blocks the listener
            JSONObject request = connection.readRequest();
            connection.send(result(request.get("id"), "{}"));
            connection.readLine();
        });
        BlockingListener blocking = new BlockingListener();
        final SnapcastRpc rpc = rpc(server.port(), fastConfig().callTimeoutMs(20_000));
        rpc.addListener(blocking);
        rpc.start();
        assertTrue(await(() -> blocking.delivered.get() == 1, 5_000));
        ExecutorService caller = Executors.newSingleThreadExecutor();
        toClose.add(caller::shutdownNow);
        Future<String> waiting = caller.submit(() -> rpc.callOrdered("A.B", null, value -> "x"));
        assertTrue("the answer waits behind the blocked listener", await(() -> rpc.getQueuedEventCount() == 1, 5_000));

        rpc.close();

        try {
            waiting.get(3, TimeUnit.SECONDS);
            fail();
        } catch (java.util.concurrent.ExecutionException e) {
            assertEquals(SnapcastException.Kind.CLOSED, ((SnapcastException) e.getCause()).getKind());
        }
        blocking.release();
    }

    // ---- forced reconnect and the peer address

    @Test
    public void aForcedReconnectReplacesAConnectionThatLooksAlive() throws Exception {
        FakeSnapcastServer server = server(connection -> {
            while (connection.readLine() != null) {
                // never answers
            }
        });
        final SnapcastRpc rpc = connected(server, fastConfig().callTimeoutMs(20_000));
        ExecutorService caller = Executors.newSingleThreadExecutor();
        toClose.add(caller::shutdownNow);
        Future<JSONObject> waiting = caller.submit(() -> rpc.call("A.B", null));
        assertTrue(await(() -> rpc.getPendingCount() == 1, 3_000));

        rpc.reconnect();

        try {
            waiting.get(3, TimeUnit.SECONDS);
            fail();
        } catch (java.util.concurrent.ExecutionException e) {
            assertEquals(SnapcastException.Kind.CLOSED, ((SnapcastException) e.getCause()).getKind());
        }
        assertTrue(await(() -> rpc.getState() == SnapcastRpc.State.CONNECTED && server.acceptedCount() == 2, 5_000));
        assertTrue(listener.awaitCount("connected", 2, 3_000));
        assertTrue(listener.awaitCount("disconnected:CLOSED", 1, 3_000));
        assertEquals("a forced reconnect needs no backoff timer", 0, scheduler.pending());
    }

    @Test
    public void aForcedReconnectAlsoSkipsTheWaitForARetry() throws Exception {
        FailingTransport transport = new FailingTransport();
        SnapcastRpc rpc = new SnapcastRpc(transport, fastConfig(), scheduler, listenerExecutor, clock);
        toClose.add(rpc);
        rpc.start();
        assertTrue(await(() -> scheduler.pending() == 1, 3_000));

        rpc.reconnect();

        assertTrue(await(() -> transport.attempts == 2, 3_000));
    }

    @Test
    public void aForcedReconnectOnAClosedClientDoesNothing() throws Exception {
        FakeSnapcastServer server = server(connection -> connection.readLine());
        SnapcastRpc rpc = connected(server, fastConfig());
        rpc.close();

        rpc.reconnect();

        assertEquals(SnapcastRpc.State.CLOSED, rpc.getState());
        assertEquals(1, server.acceptedCount());
    }

    @Test
    public void thePeerAddressIsTheAddressOfTheControlConnection() throws Exception {
        FakeSnapcastServer server = server(connection -> connection.readLine());
        SnapcastRpc rpc = rpc(server.port(), fastConfig());
        assertNull("not connected yet", rpc.getPeerAddress());

        rpc.start();
        assertTrue(await(() -> rpc.getState() == SnapcastRpc.State.CONNECTED, 3_000));

        assertEquals("127.0.0.1", rpc.getPeerAddress().getHostAddress());
        rpc.close();
        assertNull(rpc.getPeerAddress());
    }

    // ---- the order of the lifecycle events

    /** The log of the listener without the notifications. */
    private static List<String> lifecycle(List<String> log) {
        List<String> result = new ArrayList<>();
        for (String entry : log) {
            if (!entry.startsWith("notification:") && !entry.equals("lost")) {
                result.add(entry);
            }
        }
        return result;
    }

    @Test
    public void aSlowCloseOfTheReplacedConnectionDoesNotLetTheNewConnectedOvertakeTheDisconnected() throws Exception {
        SnapcastRpc rpc = new SnapcastRpc(connectTimeout -> new RpcTestSupport.SlowCloseConnection(300), fastConfig(),
                scheduler, listenerExecutor, clock);
        rpc.addListener(listener);
        toClose.add(rpc);
        rpc.start();
        assertTrue(listener.awaitCount("connected", 1, 3_000));

        rpc.reconnect();

        assertTrue(listener.awaitCount("connected", 2, 5_000));
        assertTrue(await(() -> rpc.getState() == SnapcastRpc.State.CONNECTED, 3_000));
        assertEquals(Arrays.asList("connected", "disconnected:CLOSED", "connected"), lifecycle(listener.log()));
    }

    @Test
    public void theLifecycleEventsOfManyForcedReconnectsAlternateAndEndWithConnected() throws Exception {
        final int rounds = 400;
        FakeSnapcastServer server = server(connection -> {
            while (connection.readLine() != null) {
                // holds the connection until the client replaces it
            }
        });
        SnapcastRpc rpc = connected(server, fastConfig());

        for (int i = 0; i < rounds; i++) {
            rpc.reconnect();
            assertTrue("round " + i, await(() -> rpc.getState() == SnapcastRpc.State.CONNECTED, 5_000));
        }

        assertTrue(listener.awaitCount("connected", rounds + 1, 10_000));
        assertTrue(listener.awaitCount("disconnected:CLOSED", rounds, 10_000));
        List<String> log = lifecycle(listener.log());
        assertEquals(2 * rounds + 1, log.size());
        for (int i = 0; i < log.size(); i++) {
            assertEquals("event " + i + " of " + log.subList(Math.max(0, i - 3), Math.min(log.size(), i + 3)),
                    i % 2 == 0 ? "connected" : "disconnected:CLOSED", log.get(i));
        }
        assertEquals("connected", log.get(log.size() - 1));
    }

    @Test
    public void aRetryRightAfterTheEndOfASessionCanNotOvertakeItsDisconnectedAndGaveUpEvents() throws Exception {
        final int rounds = 300;
        FakeSnapcastServer server = server(connection -> connection.close()); // accepts and hangs up
        SnapcastRpc rpc = rpc(server.port(), fastConfig().maxFailures(1));
        rpc.start();

        for (int i = 0; i < rounds; i++) {
            assertTrue("round " + i, await(() -> rpc.getState() == SnapcastRpc.State.GAVE_UP, 5_000));
            rpc.retry();
        }
        assertTrue(await(() -> rpc.getState() == SnapcastRpc.State.GAVE_UP, 5_000));

        assertTrue(listener.awaitCount("gaveup", rounds + 1, 10_000));
        List<String> log = lifecycle(listener.log());
        assertEquals(3 * (rounds + 1), log.size());
        for (int i = 0; i < log.size(); i += 3) {
            assertEquals("event " + i, "connected", log.get(i));
            assertTrue("event " + (i + 1) + " of " + log.subList(Math.max(0, i - 3), Math.min(log.size(), i + 6)),
                    log.get(i + 1).startsWith("disconnected:"));
            assertEquals("event " + (i + 2), "gaveup", log.get(i + 2));
        }
    }

    // ---- notifications that nobody can use are not queued

    private static String bigNotification(String method, int chars) {
        StringBuilder blob = new StringBuilder(chars);
        for (int i = 0; i < chars; i++) {
            blob.append('x');
        }
        return "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\",\"params\":{\"blob\":\"" + blob + "\"}}";
    }

    @Test
    public void largeNotificationsOfUnknownMethodsAreDroppedAtTheReader() throws Exception {
        final String trigger = SnapcastFixtures.read("notification_group_on_mute.json").replace("\n", " ");
        final String unknown = bigNotification("Some.Unknown", 1_000_000);
        FakeSnapcastServer server = server(connection -> {
            connection.send(trigger); // blocks the listener
            for (int i = 0; i < 20; i++) {
                connection.send(unknown);
            }
            JSONObject request = connection.readRequest();
            connection.send(result(request.get("id"), "{\"after\":true}"));
            connection.readLine();
        });
        BlockingListener blocking = new BlockingListener();
        SnapcastRpc rpc = rpc(server.port(), fastConfig().callTimeoutMs(20_000));
        rpc.addListener(blocking);
        rpc.start();

        assertTrue(rpc.call("A.After", null).getBoolean("after"));

        assertEquals("unknown notifications keep no params in the queue", 0, rpc.getQueuedEventCount());
        assertEquals(0L, rpc.getQueuedNotificationBytes());
        blocking.release();
        assertTrue("the model is told to read again", listener.awaitCount("lost", 1, 10_000));
        assertEquals("only the known notification reached the listeners", 1, blocking.delivered.get());
        assertEquals(0, listener.count("disconnected:"));
    }

    @Test
    public void largeKnownNotificationsAreBoundedByBytesNotOnlyByCount() throws Exception {
        final String trigger = SnapcastFixtures.read("notification_group_on_mute.json").replace("\n", " ");
        final String known = bigNotification("Group.OnNameChanged", 1_000_000);
        FakeSnapcastServer server = server(connection -> {
            connection.send(trigger); // blocks the listener
            for (int i = 0; i < 30; i++) {
                connection.send(known);
            }
            JSONObject request = connection.readRequest();
            connection.send(result(request.get("id"), "{\"after\":true}"));
            connection.readLine();
        });
        BlockingListener blocking = new BlockingListener();
        SnapcastRpc rpc = rpc(server.port(), fastConfig().callTimeoutMs(20_000));
        rpc.addListener(blocking);
        rpc.start();

        assertTrue(rpc.call("A.After", null).getBoolean("after"));

        assertTrue("queued: " + rpc.getQueuedEventCount(), rpc.getQueuedEventCount() <= 5);
        assertTrue("queued bytes: " + rpc.getQueuedNotificationBytes(),
                rpc.getQueuedNotificationBytes() <= SnapcastLimits.MAX_QUEUED_NOTIFICATION_BYTES);
        assertEquals(4L * 1024 * 1024, new SnapcastRpc.Config().getNotificationQueueBytes());
        blocking.release();
        assertTrue(listener.awaitCount("lost", 1, 10_000));
        assertTrue("delivered: " + blocking.delivered.get(), blocking.delivered.get() <= 6);
        assertEquals(0, listener.count("disconnected:"));
    }

    @Test
    public void streamPropertiesAreDeliveredWithoutTheirParamsAndCostNoBudget() throws Exception {
        final String trigger = SnapcastFixtures.read("notification_group_on_mute.json").replace("\n", " ");
        final String properties = bigNotification("Stream.OnProperties", 500_000);
        FakeSnapcastServer server = server(connection -> {
            connection.send(trigger); // blocks the listener
            for (int i = 0; i < 10; i++) {
                connection.send(properties);
            }
            JSONObject request = connection.readRequest();
            connection.send(result(request.get("id"), "{\"after\":true}"));
            connection.readLine();
        });
        BlockingListener blocking = new BlockingListener();
        SnapcastRpc rpc = rpc(server.port(), fastConfig().callTimeoutMs(20_000));
        rpc.addListener(blocking);
        rpc.start();

        assertTrue(rpc.call("A.After", null).getBoolean("after"));

        assertTrue("queued bytes: " + rpc.getQueuedNotificationBytes(), rpc.getQueuedNotificationBytes() < 1_000);
        blocking.release();
        assertTrue(await(() -> listener.notifications.size() == 11, 10_000));
        assertEquals("no loss: the properties were all taken", 0, listener.count("lost"));
        assertEquals("the params of the properties are dropped", 0, listener.notifications.get(10).length());
    }

    @Test
    public void oneNotificationIsAlwaysAcceptedIntoAnEmptyQueueEvenWhenTheBudgetIsSmallerThanItsLine() throws Exception {
        final String trigger = SnapcastFixtures.read("notification_group_on_mute.json").replace("\n", " ");
        final String big = bigNotification("Group.OnNameChanged", 5_000);
        final CountDownLatch listenerBusy = new CountDownLatch(1);
        FakeSnapcastServer server = server(connection -> {
            connection.send(trigger); // blocks the listener, the queue behind it is empty
            listenerBusy.await(10, TimeUnit.SECONDS);
            for (int i = 0; i < 3; i++) {
                connection.send(big);
            }
            JSONObject request = connection.readRequest();
            connection.send(result(request.get("id"), "{\"after\":true}"));
            connection.readLine();
        });
        BlockingListener blocking = new BlockingListener();
        SnapcastRpc rpc = rpc(server.port(), fastConfig().callTimeoutMs(20_000).notificationQueueBytes(1_000));
        rpc.addListener(blocking);
        rpc.start();
        assertTrue(await(() -> blocking.delivered.get() == 1, 5_000));
        listenerBusy.countDown();

        assertTrue(rpc.call("A.After", null).getBoolean("after"));

        assertEquals("the first one is in the queue", 1, rpc.getQueuedEventCount());
        assertTrue("it is charged its line: " + rpc.getQueuedNotificationBytes(), rpc.getQueuedNotificationBytes() > 1_000);
        blocking.release();
        assertTrue("the others were dropped and reported", listener.awaitCount("lost", 1, 10_000));
        assertTrue(await(() -> blocking.delivered.get() == 2, 5_000));
        assertEquals(2, blocking.delivered.get());
    }

    @Test
    public void anExecutorThatRejectsTheDrainFailsTheOrderedCallAtOnce() throws Exception {
        FakeSnapcastServer server = responding(request -> lines(result(request.get("id"), "{\"ok\":true}")));
        java.util.concurrent.Executor rejecting = task -> {
            throw new java.util.concurrent.RejectedExecutionException("shut down");
        };
        SnapcastRpc rpc = new SnapcastRpc(new SocketTransport("127.0.0.1", server.port()), fastConfig().callTimeoutMs(20_000),
                scheduler, rejecting, clock);
        toClose.add(rpc);
        rpc.start();
        assertTrue(await(() -> rpc.getState() == SnapcastRpc.State.CONNECTED, 5_000));

        long start = System.nanoTime();
        try {
            rpc.callOrdered("A.B", null, result -> result);
            fail();
        } catch (SnapcastException e) {
            assertEquals(SnapcastException.Kind.CLOSED, e.getKind());
        }

        assertTrue("took " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3_000);
        assertEquals(0, rpc.getPendingCount());
    }

    // ---- a server that stops reading

    @Test
    public void aServerThatStopsReadingCostsTheConnectionNotTheCallers() throws Exception {
        FakeSnapcastServer server = server(connection -> Thread.sleep(30_000)); // accepts, never reads
        final SnapcastRpc rpc = connected(server, fastConfig().callTimeoutMs(1_000));
        StringBuilder blob = new StringBuilder();
        for (int i = 0; i < 200_000; i++) {
            blob.append('x');
        }
        final JSONObject params = new JSONObject().put("blob", blob.toString());
        ExecutorService callers = Executors.newFixedThreadPool(20);
        toClose.add(callers::shutdownNow);
        List<Future<JSONObject>> futures = new ArrayList<>();
        long start = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            futures.add(callers.submit((Callable<JSONObject>) () -> rpc.call("A.Big", params)));
        }

        for (Future<JSONObject> future : futures) {
            try {
                future.get(5, TimeUnit.SECONDS);
                fail("answered by a server that does not read");
            } catch (java.util.concurrent.ExecutionException e) {
                SnapcastException.Kind kind = ((SnapcastException) e.getCause()).getKind();
                assertTrue(kind.toString(), kind == SnapcastException.Kind.TIMEOUT
                        || kind == SnapcastException.Kind.CLOSED || kind == SnapcastException.Kind.IO);
            }
        }

        assertTrue("took " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3_000);
        assertTrue("the connection is dropped", listener.awaitCount("disconnected:", 1, 3_000));
        assertEquals(0, rpc.getPendingCount());
    }

    @Test
    public void aWriteThatNeverReturnsEndsAtTheDeadlineAndDropsTheConnection() throws Exception {
        final StuckWriteConnection stuck = new StuckWriteConnection();
        SnapcastRpc rpc = new SnapcastRpc(connectTimeout -> stuck, fastConfig().callTimeoutMs(500), scheduler,
                listenerExecutor, clock);
        rpc.addListener(listener);
        toClose.add(rpc);
        rpc.start();
        assertTrue(await(() -> rpc.getState() == SnapcastRpc.State.CONNECTED, 3_000));

        long start = System.nanoTime();
        try {
            rpc.call("A.B", null);
            fail();
        } catch (SnapcastException e) {
            assertEquals(SnapcastException.Kind.TIMEOUT, e.getKind());
        }

        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2_000);
        assertTrue("the stuck connection was closed", await(stuck::isClosed, 3_000));
        assertTrue(listener.awaitCount("disconnected:", 1, 3_000));
        assertEquals(0, rpc.getPendingCount());
    }

    // ---- caps

    @Test
    public void aLineOverOneMiBDropsTheConnectionAndFailsPendingCalls() throws Exception {
        FakeSnapcastServer server = server(connection -> {
            connection.readRequest();
            byte[] chunk = new byte[64 * 1024];
            Arrays.fill(chunk, (byte) 'x');
            for (int i = 0; i < 32; i++) { // 2 MiB without a newline
                connection.sendRaw(chunk);
            }
        });
        SnapcastRpc rpc = connected(server, fastConfig());

        try {
            rpc.call("A.B", null);
            fail();
        } catch (SnapcastException e) {
            assertTrue(e.getKind().toString(), e.getKind() == SnapcastException.Kind.LIMIT
                    || e.getKind() == SnapcastException.Kind.CLOSED);
        }

        assertTrue(listener.awaitCount("disconnected:LIMIT", 1, 3_000));
        assertTrue(await(() -> scheduler.pending() == 1, 3_000));
    }

    @Test
    public void aLineOfExactlyTheCapIsAccepted() throws Exception {
        final int cap = 2048;
        SnapcastRpc.Config config = fastConfig().maxLineBytes(cap);
        FakeSnapcastServer server = responding(request -> {
            String prefix = "{\"jsonrpc\":\"2.0\",\"id\":" + request.get("id") + ",\"result\":{\"pad\":\"";
            String suffix = "\"}}";
            StringBuilder pad = new StringBuilder();
            while (prefix.length() + pad.length() + suffix.length() < cap) {
                pad.append('p');
            }
            return lines(prefix + pad + suffix);
        });
        SnapcastRpc rpc = connected(server, config);

        assertTrue(rpc.call("A.B", null).getString("pad").length() > 1900);
    }

    @Test
    public void aFloodOfNotificationsDoesNotGrowTheQueueAndTellsThatSomeWereLost() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger delivered = new AtomicInteger();
        SnapcastRpc.Listener blocking = new SnapcastRpc.Listener() {
            @Override
            public void onNotification(String method, JSONObject params) {
                delivered.incrementAndGet();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        final int flood = 100_000;
        final CountDownLatch sent = new CountDownLatch(1);
        FakeSnapcastServer server = server(connection -> {
            String line = "{\"jsonrpc\":\"2.0\",\"method\":\"Client.OnVolumeChanged\",\"params\":{\"id\":\"x\","
                    + "\"volume\":{\"muted\":false,\"percent\":1}}}\n";
            byte[] bytes = line.getBytes("UTF-8");
            java.io.ByteArrayOutputStream block = new java.io.ByteArrayOutputStream();
            for (int i = 0; i < 1000; i++) {
                block.write(bytes);
            }
            byte[] thousand = block.toByteArray();
            for (int i = 0; i < flood / 1000; i++) {
                connection.sendRaw(thousand);
            }
            sent.countDown();
            // ask something afterwards to prove the connection still works
            JSONObject request = connection.readRequest();
            connection.send(result(request.get("id"), "{\"after\":true}"));
            connection.readLine();
        });
        SnapcastRpc.Config config = fastConfig().notificationQueueSize(256).callTimeoutMs(20_000);
        SnapcastRpc rpc = rpc(server.port(), config);
        rpc.addListener(blocking);
        rpc.start();

        assertTrue(sent.await(30, TimeUnit.SECONDS));
        JSONObject after = rpc.call("A.After", null);

        assertTrue(after.getBoolean("after"));
        assertTrue("queued: " + rpc.getQueuedEventCount(), rpc.getQueuedEventCount() <= 256 + 8);
        release.countDown();
        assertTrue(listener.awaitCount("lost", 1, 10_000));
        assertTrue("delivered: " + delivered.get(), await(() -> delivered.get() > 0, 5_000));
        Thread.sleep(200);
        assertTrue("delivered: " + delivered.get(), delivered.get() <= 256 + 8);
        assertEquals("the connection was not dropped for a flood", 0, listener.count("disconnected:"));
    }

    @Test
    public void callsBeyondThePendingLimitFailAtOnce() throws Exception {
        FakeSnapcastServer server = server(connection -> {
            while (connection.readLine() != null) {
                // never answers
            }
        });
        final SnapcastRpc rpc = connected(server, fastConfig().callTimeoutMs(20_000));
        assertEquals(32, SnapcastLimits.MAX_PENDING_CALLS);
        ExecutorService callers = Executors.newFixedThreadPool(32);
        toClose.add(callers::shutdownNow);
        List<Future<JSONObject>> futures = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            futures.add(callers.submit((Callable<JSONObject>) () -> rpc.call("A.B", null)));
        }
        assertTrue(await(() -> rpc.getPendingCount() == 32, 10_000));

        long start = System.nanoTime();
        try {
            rpc.call("A.Overflow", null);
            fail("33rd call accepted");
        } catch (SnapcastException e) {
            assertEquals(SnapcastException.Kind.LIMIT, e.getKind());
        }

        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1_000);
        rpc.close();
        for (Future<JSONObject> future : futures) {
            try {
                future.get(5, TimeUnit.SECONDS);
                fail();
            } catch (java.util.concurrent.ExecutionException e) {
                assertEquals(SnapcastException.Kind.CLOSED, ((SnapcastException) e.getCause()).getKind());
            }
        }
    }

    // ---- bad lines

    @Test
    public void garbageLinesAreIgnoredAndRequestAReReadButTheConnectionStays() throws Exception {
        FakeSnapcastServer server = server(connection -> {
            connection.send("garbage");
            connection.send("{");
            StringBuilder deep = new StringBuilder();
            for (int i = 0; i < 10_000; i++) {
                deep.append('[');
            }
            connection.send(deep.toString());
            StringBuilder big = new StringBuilder("[");
            for (int i = 0; i < 100_000; i++) {
                big.append(i == 0 ? "{}" : ",{}");
            }
            connection.send(big.append(']').toString());
            StringBuilder sixtyFive = new StringBuilder("[");
            for (int i = 0; i < 65; i++) {
                sixtyFive.append(i == 0 ? "" : ",").append("{\"jsonrpc\":\"2.0\",\"method\":\"A.B\",\"params\":{}}");
            }
            connection.send(sixtyFive.append(']').toString());
            JSONObject request = connection.readRequest();
            connection.send(result(request.get("id"), "{\"alive\":true}"));
            connection.readLine();
        });
        SnapcastRpc rpc = connected(server, fastConfig());

        assertTrue(rpc.call("A.B", null).getBoolean("alive"));

        assertTrue(listener.awaitCount("lost", 1, 3_000));
        assertEquals("nothing of the hostile lines reached the listeners", 0, listener.count("notification:"));
        assertEquals(SnapcastRpc.State.CONNECTED, rpc.getState());
    }

    @Test
    public void aBatchOfExactly64IsAccepted() throws Exception {
        FakeSnapcastServer server = server(connection -> {
            StringBuilder batch = new StringBuilder("[");
            for (int i = 0; i < 64; i++) {
                batch.append(i == 0 ? "" : ",").append("{\"jsonrpc\":\"2.0\",\"method\":\"Group.OnMute\",\"params\":{}}");
            }
            connection.send(batch.append(']').toString());
            connection.readLine();
        });
        SnapcastRpc rpc = connected(server, fastConfig());

        assertTrue(listener.awaitCount("notification:", 64, 3_000));
    }

    @Test
    public void manyBadLinesInARowDropTheConnection() throws Exception {
        FakeSnapcastServer server = server(connection -> {
            for (int i = 0; i < 100; i++) {
                connection.send("not json " + i);
            }
            connection.readLine();
        });
        SnapcastRpc rpc = rpc(server.port(), fastConfig().maxBadLines(16));
        rpc.start();

        assertTrue(listener.awaitCount("disconnected:MALFORMED", 1, 3_000));
    }

    @Test
    public void aValidLineResetsTheCountOfBadLines() throws Exception {
        FakeSnapcastServer server = server(connection -> {
            for (int round = 0; round < 5; round++) {
                for (int i = 0; i < 10; i++) {
                    connection.send("junk");
                }
                connection.send(SnapcastFixtures.read("notification_group_on_mute.json").replace("\n", " "));
            }
            connection.readLine();
        });
        SnapcastRpc rpc = rpc(server.port(), fastConfig().maxBadLines(16));
        rpc.start();

        assertTrue(listener.awaitCount("notification:", 5, 3_000));
        assertEquals(0, listener.count("disconnected:"));
    }

    // ---- reconnect

    @Test
    public void aLostConnectionFailsPendingCallsAndReconnects() throws Exception {
        final AtomicInteger connectionNumber = new AtomicInteger();
        FakeSnapcastServer server = server(connection -> {
            int number = connectionNumber.incrementAndGet();
            JSONObject request = connection.readRequest();
            if (number == 1) {
                connection.close(); // dies with the request unanswered
                return;
            }
            connection.send(result(request.get("id"), "{\"second\":true}"));
            connection.readLine();
        });
        SnapcastRpc rpc = connected(server, fastConfig());

        try {
            rpc.call("A.B", null);
            fail();
        } catch (SnapcastException e) {
            assertTrue(e.getKind().toString(), e.getKind() == SnapcastException.Kind.CLOSED
                    || e.getKind() == SnapcastException.Kind.IO);
        }

        assertTrue(listener.awaitCount("disconnected:", 1, 3_000));
        assertTrue(await(() -> scheduler.pending() == 1, 3_000));
        assertEquals(1_000, scheduler.lastDelay());
        assertEquals(SnapcastRpc.State.WAITING_RETRY, rpc.getState());
        scheduler.runNext();
        assertTrue(await(() -> rpc.getState() == SnapcastRpc.State.CONNECTED, 5_000));
        assertTrue(rpc.call("A.B", null).getBoolean("second"));
        assertEquals(2, listener.count("connected"));
    }

    @Test
    public void callsWhileWaitingToReconnectFailAtOnce() throws Exception {
        FailingTransport transport = new FailingTransport();
        SnapcastRpc rpc = new SnapcastRpc(transport, fastConfig(), scheduler, listenerExecutor, clock);
        toClose.add(rpc);
        rpc.start();
        assertTrue(await(() -> scheduler.pending() == 1, 3_000));

        try {
            rpc.call("A.B", null);
            fail();
        } catch (SnapcastException e) {
            assertEquals(SnapcastException.Kind.NOT_CONNECTED, e.getKind());
        }
    }

    @Test
    public void backoffDoublesAndTheFifthFailureStopsUntilRetry() throws Exception {
        FailingTransport transport = new FailingTransport();
        SnapcastRpc rpc = new SnapcastRpc(transport, fastConfig(), scheduler, listenerExecutor, clock);
        rpc.addListener(listener);
        toClose.add(rpc);

        rpc.start();
        for (int attempt = 1; attempt <= 4; attempt++) {
            final int expectedTasks = 1;
            assertTrue("attempt " + attempt, await(() -> scheduler.pending() == expectedTasks, 3_000));
            scheduler.runNext();
        }
        assertTrue(await(() -> rpc.getState() == SnapcastRpc.State.GAVE_UP, 3_000));

        assertEquals(Arrays.asList(1_000L, 2_000L, 4_000L, 8_000L), scheduler.delays());
        assertEquals(5, transport.attempts);
        assertEquals(0, scheduler.pending());
        assertTrue(listener.awaitCount("gaveup", 1, 2_000));
        assertNotNull(listener.lastCause);

        // nothing happens by itself any more
        Thread.sleep(100);
        assertEquals(5, transport.attempts);

        rpc.retry();
        assertTrue(await(() -> transport.attempts == 6, 3_000));
        assertTrue(await(() -> scheduler.pending() == 1, 3_000));
        assertEquals("the count starts again", 1_000L, scheduler.lastDelay());
    }

    @Test
    public void backoffIsCappedAt30Seconds() throws Exception {
        FailingTransport transport = new FailingTransport();
        SnapcastRpc.Config config = fastConfig().backoffInitialMs(10_000).backoffMaxMs(30_000).maxFailures(8);
        SnapcastRpc rpc = new SnapcastRpc(transport, config, scheduler, listenerExecutor, clock);
        toClose.add(rpc);

        rpc.start();
        for (int attempt = 1; attempt <= 7; attempt++) {
            assertTrue("attempt " + attempt, await(() -> scheduler.pending() == 1, 3_000));
            scheduler.runNext();
        }
        assertTrue(await(() -> rpc.getState() == SnapcastRpc.State.GAVE_UP, 3_000));

        assertEquals(Arrays.asList(10_000L, 20_000L, 30_000L, 30_000L, 30_000L, 30_000L, 30_000L), scheduler.delays());
    }

    @Test
    public void defaultsAreTheDocumentedOnes() {
        SnapcastRpc.Config config = new SnapcastRpc.Config();

        assertEquals(10_000, config.getCallTimeoutMs());
        assertEquals(1 << 20, config.getMaxLineBytes());
        assertEquals(32, config.getMaxPending());
        assertEquals(64, config.getMaxBatch());
        assertEquals(5, config.getMaxFailures());
        assertEquals(30_000, config.getBackoffMaxMs());
        assertEquals(1_000, config.getBackoffInitialMs());
    }

    @Test
    public void aConnectionThatLivedLongEnoughResetsTheFailureCount() throws Exception {
        final FakeSnapcastServer server = server(connection -> connection.readLine());
        final AtomicInteger attempts = new AtomicInteger();
        SnapcastTransport flaky = connectTimeout -> {
            if (attempts.incrementAndGet() <= 2) {
                throw new java.io.IOException("refused");
            }
            return new SocketTransport("127.0.0.1", server.port()).open(connectTimeout);
        };
        SnapcastRpc rpc = new SnapcastRpc(flaky, fastConfig(), scheduler, listenerExecutor, clock);
        rpc.addListener(listener);
        toClose.add(rpc);

        rpc.start();
        assertTrue(await(() -> scheduler.pending() == 1, 3_000));
        scheduler.runNext();
        assertTrue(await(() -> scheduler.pending() == 1, 3_000));
        scheduler.runNext();
        assertTrue(await(() -> rpc.getState() == SnapcastRpc.State.CONNECTED, 3_000));
        assertEquals(Arrays.asList(1_000L, 2_000L), scheduler.delays());

        clock.advance(60_000);
        server.closeConnection(0);

        assertTrue(await(() -> scheduler.pending() == 1, 3_000));
        assertEquals("a long connection starts the backoff again", 1_000L, scheduler.lastDelay());
    }

    @Test
    public void aConnectionThatDiesAtOnceDoesNotResetTheFailureCount() throws Exception {
        final FakeSnapcastServer server = server(connection -> connection.close());
        SnapcastRpc rpc = rpc(server.port(), fastConfig());

        rpc.start();
        for (int attempt = 1; attempt <= 4; attempt++) {
            assertTrue("attempt " + attempt, await(() -> scheduler.pending() == 1, 3_000));
            scheduler.runNext();
        }
        assertTrue(await(() -> rpc.getState() == SnapcastRpc.State.GAVE_UP, 3_000));

        assertEquals(Arrays.asList(1_000L, 2_000L, 4_000L, 8_000L), scheduler.delays());
    }

    @Test
    public void retryWhileWaitingConnectsAtOnceAndIgnoresTheOldTimer() throws Exception {
        final AtomicInteger connections = new AtomicInteger();
        FakeSnapcastServer server = server(connection -> {
            connections.incrementAndGet();
            connection.readLine();
        });
        final AtomicInteger attempts = new AtomicInteger();
        SnapcastTransport firstFails = connectTimeout -> {
            if (attempts.incrementAndGet() == 1) {
                throw new java.io.IOException("refused");
            }
            return new SocketTransport("127.0.0.1", server.port()).open(connectTimeout);
        };
        SnapcastRpc rpc = new SnapcastRpc(firstFails, fastConfig(), scheduler, listenerExecutor, clock);
        toClose.add(rpc);
        rpc.start();
        assertTrue(await(() -> scheduler.pending() == 1, 3_000));

        rpc.retry();
        assertTrue(await(() -> rpc.getState() == SnapcastRpc.State.CONNECTED, 3_000));
        scheduler.runNext(); // the timer of the failed attempt fires late

        Thread.sleep(150);
        assertEquals("no second connection", 1, connections.get());
        assertEquals(SnapcastRpc.State.CONNECTED, rpc.getState());
    }

    // ---- backstop

    @Test
    public void aStackOverflowInTheReaderIsCaughtAtTheWorkerBoundary() throws Exception {
        assertErrorIsContained(new StackOverflowError("test"));
    }

    @Test
    public void anOutOfMemoryErrorInTheReaderIsCaughtAtTheWorkerBoundary() throws Exception {
        assertErrorIsContained(new OutOfMemoryError("test"));
    }

    @Test
    public void aRuntimeExceptionInTheReaderIsCaughtToo() throws Exception {
        assertErrorIsContained(new IllegalStateException("test"));
    }

    private void assertErrorIsContained(final Throwable toThrow) throws Exception {
        final AtomicReference<Throwable> uncaught = new AtomicReference<>();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> uncaught.set(throwable));
        try {
            SnapcastRpc rpc = new SnapcastRpc(connectTimeout -> new ThrowingConnection(toThrow), fastConfig(), scheduler,
                    listenerExecutor, clock);
            rpc.addListener(listener);
            toClose.add(rpc);

            rpc.start();

            assertTrue(listener.awaitCount("disconnected:INTERNAL", 1, 3_000));
            assertTrue(await(() -> scheduler.pending() == 1, 3_000));
            assertNull("nothing escaped the worker: " + uncaught.get(), uncaught.get());
            assertEquals(SnapcastRpc.State.WAITING_RETRY, rpc.getState());
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    @Test
    public void aListenerThatThrowsDoesNotKillTheDelivery() throws Exception {
        FakeSnapcastServer server = server(connection -> {
            for (int i = 0; i < 3; i++) {
                connection.send(SnapcastFixtures.read("notification_group_on_mute.json").replace("\n", " "));
            }
            connection.readLine();
        });
        SnapcastRpc rpc = rpc(server.port(), fastConfig());
        rpc.addListener(new SnapcastRpc.Listener() {
            @Override
            public void onNotification(String method, JSONObject params) {
                throw new IllegalStateException("listener bug");
            }
        });
        rpc.start();

        assertTrue(listener.awaitCount("notification:", 3, 3_000));
    }

    // ---- close

    @Test
    public void closeReleasesEverything() throws Exception {
        final CountDownLatch serverSawEof = new CountDownLatch(1);
        FakeSnapcastServer server = server(connection -> {
            while (connection.readLine() != null) {
                // read until the client closes
            }
            serverSawEof.countDown();
        });
        final SnapcastRpc rpc = connected(server, fastConfig().callTimeoutMs(20_000));
        ExecutorService caller = Executors.newSingleThreadExecutor();
        toClose.add(caller::shutdownNow);
        Future<JSONObject> blocked = caller.submit(() -> rpc.call("A.B", null));
        assertTrue(await(() -> rpc.getPendingCount() == 1, 3_000));

        rpc.close();

        try {
            blocked.get(3, TimeUnit.SECONDS);
            fail();
        } catch (java.util.concurrent.ExecutionException e) {
            assertEquals(SnapcastException.Kind.CLOSED, ((SnapcastException) e.getCause()).getKind());
        }
        assertEquals(SnapcastRpc.State.CLOSED, rpc.getState());
        assertTrue("the socket is closed", serverSawEof.await(3, TimeUnit.SECONDS));
        assertEquals(0, rpc.getPendingCount());
        assertTrue("no reader thread is left", await(() -> !rpcThreadAlive(), 3_000));
        try {
            rpc.call("A.B", null);
            fail();
        } catch (SnapcastException e) {
            assertEquals(SnapcastException.Kind.CLOSED, e.getKind());
        }
        rpc.close(); // twice is fine
        rpc.start(); // and a closed client stays closed
        rpc.retry();
        assertEquals(SnapcastRpc.State.CLOSED, rpc.getState());
    }

    private static Set<Thread> snapcastThreads() {
        Set<Thread> result = new HashSet<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().startsWith("snapcast-rpc") && thread.isAlive()) {
                result.add(thread);
            }
        }
        return result;
    }

    /** True if a thread of an rpc client that this test created is still running. */
    private boolean rpcThreadAlive() {
        Set<Thread> now = snapcastThreads();
        now.removeAll(threadsBefore);
        return !now.isEmpty();
    }

    @Test
    public void aRetryTimerThatFiresAfterCloseDoesNothing() throws Exception {
        final FakeSnapcastServer server = server(connection -> connection.close());
        SnapcastRpc rpc = rpc(server.port(), fastConfig());
        rpc.start();
        assertTrue(await(() -> scheduler.pending() == 1, 3_000));
        int before = server.acceptedCount();

        rpc.close();
        scheduler.runNext();

        Thread.sleep(150);
        assertEquals(before, server.acceptedCount());
        assertEquals(SnapcastRpc.State.CLOSED, rpc.getState());
    }

    @Test
    public void closeWhileConnectingStopsTheAttempt() throws Exception {
        final CountDownLatch inConnect = new CountDownLatch(1);
        final CountDownLatch finish = new CountDownLatch(1);
        final AtomicReference<RpcTestSupport.ThrowingConnection> opened = new AtomicReference<>();
        SnapcastTransport slow = connectTimeout -> {
            inConnect.countDown();
            try {
                finish.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            RpcTestSupport.ThrowingConnection connection = new RpcTestSupport.ThrowingConnection(new IllegalStateException("never read"));
            opened.set(connection);
            return connection;
        };
        SnapcastRpc rpc = new SnapcastRpc(slow, fastConfig(), scheduler, listenerExecutor, clock);
        rpc.addListener(listener);
        toClose.add(rpc);
        rpc.start();
        assertTrue(inConnect.await(3, TimeUnit.SECONDS));

        rpc.close();
        finish.countDown();

        assertTrue(await(() -> !rpcThreadAlive(), 3_000));
        assertEquals("the late connection is not used", 0, listener.count("connected"));
        assertEquals(0, scheduler.pending());
    }

    @Test
    public void startingTwiceDoesNotOpenTwoConnections() throws Exception {
        FakeSnapcastServer server = server(connection -> connection.readLine());
        SnapcastRpc rpc = rpc(server.port(), fastConfig());

        rpc.start();
        rpc.start();
        assertTrue(await(() -> rpc.getState() == SnapcastRpc.State.CONNECTED, 3_000));
        rpc.start();

        Thread.sleep(150);
        assertEquals(1, server.acceptedCount());
    }

    @Test
    public void aCallWaitsForTheConnectionThatIsBeingOpened() throws Exception {
        FakeSnapcastServer server = responding(request -> lines(result(request.get("id"), "{\"ok\":true}")));
        SnapcastRpc rpc = rpc(server.port(), fastConfig());

        rpc.start();
        JSONObject answer = rpc.call("A.B", null); // no wait for CONNECTED in the test

        assertTrue(answer.getBoolean("ok"));
    }

    @Test
    public void aCallBeforeStartIsRefused() throws Exception {
        FakeSnapcastServer server = server(connection -> connection.readLine());
        SnapcastRpc rpc = rpc(server.port(), fastConfig());

        try {
            rpc.call("A.B", null);
            fail();
        } catch (SnapcastException e) {
            assertEquals(SnapcastException.Kind.NOT_CONNECTED, e.getKind());
        }
    }

    @Test
    public void methodNamesAreChecked() throws Exception {
        FakeSnapcastServer server = responding(request -> lines(result(request.get("id"), "{}")));
        SnapcastRpc rpc = connected(server, fastConfig());

        for (String bad : new String[]{null, "", "has\nnewline"}) {
            try {
                rpc.call(bad, null);
                fail("accepted " + bad);
            } catch (IllegalArgumentException expected) {
                // a programming error, not a server problem
            }
        }
    }

    @Test
    public void anOversizedRequestIsRefusedBeforeItIsSent() throws Exception {
        FakeSnapcastServer server = responding(request -> lines(result(request.get("id"), "{}")));
        SnapcastRpc rpc = connected(server, fastConfig());
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 400_000; i++) {
            huge.append('x');
        }

        try {
            rpc.call("A.B", new JSONObject().put("blob", huge.toString()));
            fail();
        } catch (SnapcastException e) {
            assertEquals(SnapcastException.Kind.LIMIT, e.getKind());
        }
        assertEquals(0, rpc.getPendingCount());
    }

    @Test
    public void peerAddressIsTheAddressThatWasConnected() throws Exception {
        final AtomicReference<java.net.InetAddress> peer = new AtomicReference<>();
        FakeSnapcastServer server = server(connection -> connection.readLine());
        SnapcastTransport.Connection connection = new SocketTransport("127.0.0.1", server.port()).open(1_000);
        try {
            peer.set(connection.getPeerAddress());
        } finally {
            connection.close();
        }

        assertEquals("127.0.0.1", peer.get().getHostAddress());
    }

    @Test
    public void transportFailureKindIsIo() throws Exception {
        FailingTransport transport = new FailingTransport();
        SnapcastRpc rpc = new SnapcastRpc(transport, fastConfig(), scheduler, listenerExecutor, clock);
        rpc.addListener(listener);
        toClose.add(rpc);

        rpc.start();

        assertTrue(await(() -> scheduler.pending() == 1, 3_000));
        assertTrue(listener.awaitCount("disconnected:IO", 1, 3_000));
        assertEquals(SnapcastException.Kind.IO, listener.lastCause.getKind());
    }
}
