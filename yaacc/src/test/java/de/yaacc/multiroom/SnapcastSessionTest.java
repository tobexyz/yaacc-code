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
package de.yaacc.multiroom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import de.yaacc.multiroom.control.MonotonicClock;
import de.yaacc.multiroom.control.RetryScheduler;
import de.yaacc.multiroom.control.SnapcastException;
import de.yaacc.multiroom.control.SnapcastRpc;
import de.yaacc.multiroom.control.SnapcastVolume;
import de.yaacc.multiroom.control.SocketTransport;
import de.yaacc.multiroom.discovery.SnapcastEndpoint;

/**
 * The session of one server against a fake control server on the loopback address: a fresh status on connect, the
 * model follows notifications and our own changes, a failed read is repeated within the rate limit, a server error
 * is not, an offline server keeps its last status, and close releases every thread.
 */
public class SnapcastSessionTest {

    private static final String CLIENT_ID = "02:00:00:00:00:01";
    private static final String GROUP_ID = "aaaaaaaa-0000-4000-8000-000000000001";

    /** Collects the timer tasks; the test runs them when it wants. */
    private static final class ManualScheduler implements RetryScheduler {
        private final List<Runnable> tasks = Collections.synchronizedList(new ArrayList<Runnable>());

        @Override
        public void schedule(Runnable task, long delayMillis) {
            tasks.add(task);
        }

        /** Runs the tasks that are waiting now (those they schedule wait for the next call). */
        void runAll() {
            List<Runnable> snapshot;
            synchronized (tasks) {
                snapshot = new ArrayList<>(tasks);
                tasks.clear();
            }
            for (Runnable task : snapshot) {
                task.run();
            }
        }
    }

    private final AtomicLong now = new AtomicLong(1_000);
    private final MonotonicClock clock = now::get;
    private final ManualScheduler scheduler = new ManualScheduler();
    private final AtomicInteger changes = new AtomicInteger();
    private final List<AutoCloseable> toClose = new ArrayList<>();
    private SessionFakeServer server;
    private SnapcastSession session;
    private final Set<Thread> threadsBefore = new HashSet<>();

    @Before
    public void setUp() throws Exception {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().startsWith("snapcast-session-") && thread.isAlive()) {
                threadsBefore.add(thread);
            }
        }
        server = new SessionFakeServer("status_one_group.json");
        toClose.add(server);
    }

    @After
    public void tearDown() throws Exception {
        if (session != null) {
            session.close();
        }
        for (AutoCloseable closeable : toClose) {
            closeable.close();
        }
    }

    private SnapcastSession open() {
        SnapcastEndpoint endpoint = SnapcastEndpoint.parse("127.0.0.1:" + server.port());
        SnapcastRpc.Config config = new SnapcastRpc.Config().callTimeoutMs(2_000).readTimeoutMs(2_000)
                .backoffInitialMs(10).backoffMaxMs(10);
        session = new SnapcastSession(endpoint.toKey(), new SocketTransport(endpoint.getAddress(), endpoint.getPort()),
                config, clock, scheduler);
        session.addListener(link -> changes.incrementAndGet());
        session.start();
        return session;
    }

    private static boolean await(BooleanSupplier condition) {
        long end = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    /** True if a thread of a session that this test created is still running (threads that were there before do not count). */
    private boolean sessionThreadsAlive() {
        Set<Thread> current = new HashSet<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().startsWith("snapcast-session-") && thread.isAlive()) {
                current.add(thread);
            }
        }
        current.removeAll(threadsBefore);
        return !current.isEmpty();
    }

    @Test
    public void itReadsAFreshStatusWhenTheConnectionComesUp() {
        open();

        assertTrue(await(() -> session.getStatus() != null));
        assertTrue(session.isOnline());
        assertEquals(1, session.getStatus().getGroups().size());
        assertEquals(1, server.statusRequests());
        assertTrue("the listeners were told", changes.get() > 0);
        assertNull(session.getLastErrorKind());
    }

    @Test
    public void theAddressOfTheControlConnectionIsTheLoopbackAddressItConnectedTo() throws Exception {
        open();
        assertTrue(await(() -> session.isOnline()));

        assertEquals(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), session.getPeerAddress());
    }

    @Test
    public void aNotificationPatchesTheModelAndTellsTheListeners() {
        open();
        assertTrue(await(() -> session.getStatus() != null));
        assertFalse(session.getStatus().findGroup(GROUP_ID).isMuted());
        int before = changes.get();

        server.push(Fixtures.read("notification_group_on_mute.json"));

        assertTrue(await(() -> session.getStatus().findGroup(GROUP_ID).isMuted()));
        assertTrue(await(() -> changes.get() > before));
        assertEquals("no re-read for a notification the model understands", 1, server.statusRequests());
    }

    @Test
    public void anUnknownNotificationMakesItReadTheStatusAgain() {
        open();
        assertTrue(await(() -> session.getStatus() != null));

        server.push("{\"jsonrpc\":\"2.0\",\"method\":\"Something.New\",\"params\":{}}");

        assertTrue(await(() -> server.statusRequests() == 2));
    }

    @Test
    public void aFailedReadIsRepeated() {
        server.queueModes(SessionFakeServer.Mode.MALFORMED);

        open();

        assertTrue(await(() -> session.getStatus() != null));
        assertEquals(2, server.statusRequests());
        // the worker publishes the status and clears the error one statement later: wait for it, do not assert at once
        assertTrue("the error is cleared by the successful read", await(() -> session.getLastErrorKind() == null));
    }

    @Test
    public void aFailedReadIsRepeatedWithinTheRateLimitAndNotForever() throws Exception {
        server.setDefaultMode(SessionFakeServer.Mode.MALFORMED);

        open();

        await(() -> server.statusRequests() == 2);
        assertEquals(2, server.statusRequests());
        // the limit holds the third request back until a second has passed; the tick delivers it
        for (int second = 0; second < 300 && server.statusRequests() < 1 + SnapcastSession.MAX_READ_RETRIES; second++) {
            now.addAndGet(1_000);
            scheduler.runAll();
            Thread.sleep(10); // the reads run on the worker
        }
        assertTrue(await(() -> server.statusRequests() == 1 + SnapcastSession.MAX_READ_RETRIES));
        now.addAndGet(5_000);
        scheduler.runAll();
        assertEquals("it gave up", 1 + SnapcastSession.MAX_READ_RETRIES, server.statusRequests());
        assertNull(session.getStatus());
        assertEquals(SnapcastException.Kind.MALFORMED, session.getLastErrorKind());
        assertTrue("the connection is up, only the status is missing", session.isOnline());
    }

    @Test
    public void aServerErrorIsNotRepeated() {
        server.queueModes(SessionFakeServer.Mode.UNAUTHORIZED);

        open();

        assertTrue(await(() -> session.getLastErrorCode() == 401));
        now.addAndGet(5_000);
        scheduler.runAll();
        assertEquals(SnapcastException.Kind.SERVER_ERROR, session.getLastErrorKind());
        assertEquals(1, server.statusRequests());
        assertNull(session.getStatus());
    }

    @Test
    public void anOfflineServerKeepsItsLastStatusAndReadsAFreshOneWhenItIsBack() {
        open();
        assertTrue(await(() -> session.getStatus() != null));
        int before = changes.get();

        server.dropConnections();

        assertTrue(await(() -> !session.isOnline()));
        assertNotNull("the last status stays", session.getStatus());
        assertTrue(await(() -> changes.get() > before));
        assertTrue(await(() -> {
            scheduler.runAll(); // the reconnect timer
            return session.isOnline();
        }));
        assertTrue(await(() -> server.statusRequests() == 2));
    }

    @Test
    public void aChangeOfOurOwnIsInTheModelAndTheListenersAreTold() throws Exception {
        open();
        assertTrue(await(() -> session.getStatus() != null));
        int before = changes.get();

        SnapcastVolume actual = session.getOperations().setClientVolume(CLIENT_ID, new SnapcastVolume(30, false));

        assertEquals(42, actual.getPercent()); // what the fake server answers
        assertEquals(42, session.getStatus().findClient(CLIENT_ID).getVolume().getPercent());
        assertTrue(changes.get() > before);
    }

    @Test
    public void closeReleasesEveryThreadAndStopsTheCallbacks() {
        open();
        assertTrue(await(() -> session.getStatus() != null));
        assertTrue(sessionThreadsAlive());

        session.close();
        int after = changes.get();
        server.push(Fixtures.read("notification_group_on_mute.json"));

        assertTrue(await(() -> !sessionThreadsAlive()));
        assertFalse(session.isOnline());
        assertEquals(after, changes.get());
        session.close(); // twice is fine
    }

    @Test
    public void startingTwiceOpensOneConnection() {
        open();
        session.start();
        assertTrue(await(() -> session.isOnline()));

        assertEquals(1, server.accepted());
    }
}
