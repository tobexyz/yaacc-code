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
package de.yaacc.multiroom.ui;

import static de.yaacc.multiroom.ui.UiFixtures.client;
import static de.yaacc.multiroom.ui.UiFixtures.group;
import static de.yaacc.multiroom.ui.UiFixtures.status;
import static de.yaacc.multiroom.ui.UiFixtures.stream;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import de.yaacc.multiroom.SnapcastServerLink;
import de.yaacc.multiroom.control.SnapcastCaller;
import de.yaacc.multiroom.control.SnapcastException;
import de.yaacc.multiroom.control.SnapcastModelUpdater;
import de.yaacc.multiroom.control.SnapcastOperations;
import de.yaacc.multiroom.control.SnapcastServerStatus;
import de.yaacc.multiroom.discovery.SnapcastEndpoint;
import de.yaacc.multiroom.discovery.SnapcastServerKey;

/**
 * The changes the Snapcast rows make on a server: they run off the calling thread, a quick series of slider values is
 * coalesced to the latest one, the group volume moves the members relative to their levels at the start of the gesture,
 * and a failure is reported once with a cleaned and short reason. A fake server link with a real
 * {@link SnapcastOperations} and a recording caller. Plain JVM.
 */
public class SnapcastControlsTest {

    private static final SnapcastServerKey KEY = SnapcastEndpoint.parse("192.168.1.5").toKey();
    private static final String GROUP = "g1";

    /** Records the calls and answers with an empty result (or a canned failure). */
    private static class RecordingCaller implements SnapcastCaller {
        final List<String> methods = new ArrayList<>();
        final List<JSONObject> params = new ArrayList<>();
        SnapcastException failure;

        @Override
        public synchronized JSONObject call(String method, JSONObject callParams) throws SnapcastException {
            methods.add(method);
            try {
                params.add(callParams == null ? null : new JSONObject(callParams.toString()));
                if (failure != null) {
                    throw failure;
                }
                if ("Group.SetClients".equals(method)) {
                    return new JSONObject("{\"server\":{\"groups\":[],\"streams\":[]}}");
                }
                return new JSONObject();
            } catch (org.json.JSONException e) {
                throw new AssertionError(e);
            }
        }

        synchronized int count(String method) {
            int n = 0;
            for (String m : methods) {
                if (m.equals(method)) {
                    n++;
                }
            }
            return n;
        }

        synchronized JSONObject last(String method) {
            for (int i = methods.size() - 1; i >= 0; i--) {
                if (methods.get(i).equals(method)) {
                    return params.get(i);
                }
            }
            return null;
        }

        synchronized List<JSONObject> all(String method) {
            List<JSONObject> result = new ArrayList<>();
            for (int i = 0; i < methods.size(); i++) {
                if (methods.get(i).equals(method)) {
                    result.add(params.get(i));
                }
            }
            return result;
        }
    }

    /** A link whose model is kept up to date by our own calls, like the real session. */
    private static class ModelLink implements SnapcastServerLink {
        final SnapcastModelUpdater updater = new SnapcastModelUpdater(() -> 0L, () -> { });
        final SnapcastOperations operations;

        ModelLink(RecordingCaller caller, SnapcastServerStatus status) {
            updater.setStatus(status);
            operations = new SnapcastOperations(caller, updater);
        }

        @Override
        public SnapcastServerKey getKey() {
            return KEY;
        }

        @Override
        public SnapcastServerStatus getStatus() {
            return updater.getStatus();
        }

        @Override
        public boolean isOnline() {
            return true;
        }

        @Override
        public void addListener(Listener listener) {
        }

        @Override
        public void removeListener(Listener listener) {
        }

        @Override
        public SnapcastOperations getOperations() {
            return operations;
        }

        @Override
        public void start() {
        }

        @Override
        public void retry() {
        }

        @Override
        public void close() {
        }
    }

    /** Collects tasks until the test runs them. */
    private static final class QueueExecutor implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runAll() {
            while (!tasks.isEmpty()) {
                tasks.poll().run();
            }
        }
    }

    private final RecordingCaller caller = new RecordingCaller();
    private final QueueExecutor actions = new QueueExecutor();
    private final QueueExecutor volumes = new QueueExecutor();
    private final AtomicLong now = new AtomicLong(1_000);
    private final List<SnapcastControls.Action> failedActions = new ArrayList<>();
    private final List<SnapcastControls.Failure> failures = new ArrayList<>();
    private ModelLink link;
    private SnapcastControls controls;

    @Before
    public void setUp() {
        link = new ModelLink(caller, status(
                Arrays.asList(
                        group(GROUP, "Living", "Default",
                                client("a", "Kitchen", true, 40, false),
                                client("b", "Hall", true, 60, true),
                                client("c", "Attic", false, 100, false)),
                        group("g2", "Other", "Default", client("d", "Desk", true, 10, false))),
                stream("Default", "idle"), stream("Radio", "idle")));
        controls = new SnapcastControls(key -> KEY.equals(key) ? link : null, actions, volumes, () -> now.get(),
                (action, failure) -> {
                    failedActions.add(action);
                    failures.add(failure);
                });
    }

    private int volumeOf(String clientId) {
        return link.getStatus().findClient(clientId).getVolume().getPercent();
    }

    // ---- group volume

    @Test
    public void nothingRunsOnTheCallingThread() {
        controls.setGroupVolume(KEY, GROUP, 70);
        controls.setGroupMuted(KEY, GROUP, true);

        assertTrue("queued, not run", caller.methods.isEmpty());
        assertEquals(1, volumes.tasks.size());
        assertEquals(1, actions.tasks.size());
    }

    @Test
    public void theGroupVolumeMovesTheConnectedMembersByTheSameNumberOfPoints() {
        controls.beginGroupGesture(KEY, GROUP);
        controls.setGroupVolume(KEY, GROUP, 60); // the average of 40 and 60 is 50
        volumes.runAll();

        assertEquals(50, volumeOf("a"));
        assertEquals(70, volumeOf("b"));
        assertEquals("a disconnected member is not touched", 100, volumeOf("c"));
        assertEquals(2, caller.count("Client.SetVolume"));
    }

    @Test
    public void aMutedMemberMovesWithTheGroupAndStaysMuted() {
        controls.beginGroupGesture(KEY, GROUP);
        controls.setGroupVolume(KEY, GROUP, 60);
        volumes.runAll();

        assertTrue(link.getStatus().findClient("b").getVolume().isMuted());
    }

    @Test
    public void aSeriesOfValuesBecomesOneCallWithTheLatestValue() {
        controls.beginGroupGesture(KEY, GROUP);
        controls.setGroupVolume(KEY, GROUP, 52);
        controls.setGroupVolume(KEY, GROUP, 55);
        controls.setGroupVolume(KEY, GROUP, 60);

        assertEquals("one task for the series", 1, volumes.tasks.size());
        volumes.runAll();

        assertEquals(2, caller.count("Client.SetVolume"));
        assertEquals(50, volumeOf("a"));
    }

    @Test
    public void dragBackRestoresTheLevelsOfTheStartOfTheGesture() {
        controls.beginGroupGesture(KEY, GROUP);
        controls.setGroupVolume(KEY, GROUP, 90);
        volumes.runAll();
        controls.setGroupVolume(KEY, GROUP, 50);
        volumes.runAll();

        assertEquals(40, volumeOf("a"));
        assertEquals(60, volumeOf("b"));
    }

    @Test
    public void aNewGestureStartsFromTheLevelsOfThatMoment() {
        controls.beginGroupGesture(KEY, GROUP);
        controls.setGroupVolume(KEY, GROUP, 60);
        volumes.runAll();
        controls.beginGroupGesture(KEY, GROUP);
        controls.setGroupVolume(KEY, GROUP, 60);
        volumes.runAll();

        assertEquals("the second gesture changes nothing: it starts at 60", 50, volumeOf("a"));
        assertEquals(70, volumeOf("b"));
    }

    @Test
    public void withoutABeginAGapStartsANewGesture() {
        controls.setGroupVolume(KEY, GROUP, 60);
        volumes.runAll();
        now.addAndGet(SnapcastControls.GESTURE_GAP_MILLIS + 1);
        controls.setGroupVolume(KEY, GROUP, 70); // from the levels now (avg 60), +10
        volumes.runAll();

        assertEquals(60, volumeOf("a"));
        assertEquals(80, volumeOf("b"));
    }

    @Test
    public void callsCloserThanTheGapBelongToOneGesture() {
        controls.setGroupVolume(KEY, GROUP, 60);
        volumes.runAll();
        now.addAndGet(100);
        controls.setGroupVolume(KEY, GROUP, 40);
        volumes.runAll();

        assertEquals(30, volumeOf("a"));
        assertEquals(50, volumeOf("b"));
    }

    @Test
    public void aGroupThatVanishedIsReportedAsGoneAndNothingIsSent() {
        controls.setGroupVolume(KEY, "nope", 60);
        volumes.runAll();

        assertEquals(0, caller.methods.size());
        assertEquals(Collections.singletonList(SnapcastControls.Action.GROUP_VOLUME), failedActions);
        assertEquals(SnapcastControls.Failure.Kind.GONE, failures.get(0).getKind());
    }

    // ---- group mute, stream

    @Test
    public void groupMuteIsSentToTheGroup() {
        controls.setGroupMuted(KEY, GROUP, true);
        actions.runAll();

        assertEquals(GROUP, caller.last("Group.SetMute").optString("id"));
        assertTrue(caller.last("Group.SetMute").optBoolean("mute"));
        assertTrue(link.getStatus().findGroup(GROUP).isMuted());
    }

    @Test
    public void theStreamOfAGroupIsSet() {
        controls.setGroupStream(KEY, GROUP, "Radio");
        actions.runAll();

        assertEquals("Radio", caller.last("Group.SetStream").optString("stream_id"));
        assertEquals("g1", caller.last("Group.SetStream").optString("id"));
        assertEquals("Radio", link.getStatus().findGroup(GROUP).getStreamId());
    }

    // ---- clients

    @Test
    public void aClientSliderKeepsTheMuteFlagAndSendsTheLatestValueOnly() {
        controls.setClientVolume(KEY, "b", 20);
        controls.setClientVolume(KEY, "b", 25);
        controls.setClientVolume(KEY, "b", 30);
        volumes.runAll();

        assertEquals(1, caller.count("Client.SetVolume"));
        JSONObject sent = caller.last("Client.SetVolume");
        assertEquals(30, sent.optJSONObject("volume").optInt("percent"));
        assertTrue("b was muted and stays muted", sent.optJSONObject("volume").optBoolean("muted"));
    }

    @Test
    public void twoClientsAreCoalescedSeparately() {
        controls.setClientVolume(KEY, "a", 11);
        controls.setClientVolume(KEY, "b", 22);
        volumes.runAll();

        assertEquals(11, volumeOf("a"));
        assertEquals(22, volumeOf("b"));
    }

    @Test
    public void clientMuteKeepsTheVolume() {
        controls.setClientMuted(KEY, "a", true);
        actions.runAll();

        JSONObject volume = caller.last("Client.SetVolume").optJSONObject("volume");
        assertEquals(40, volume.optInt("percent"));
        assertTrue(volume.optBoolean("muted"));
    }

    @Test
    public void latencyIsSent() {
        controls.setClientLatency(KEY, "a", 250);
        actions.runAll();

        assertEquals(250, caller.last("Client.SetLatency").optInt("latency"));
        assertEquals(250, link.getStatus().findClient("a").getLatencyMs());
    }

    @Test
    public void theNewNameIsCleanedBeforeItIsSent() {
        controls.renameClient(KEY, "a", "  Pantry\n\u200B ");
        actions.runAll();

        assertEquals("Pantry??", caller.last("Client.SetName").optString("name"));
    }

    @Test
    public void anEmptyNameResetsTheName() {
        controls.renameClient(KEY, "a", "   ");
        actions.runAll();

        assertEquals("", caller.last("Client.SetName").optString("name", "missing"));
    }

    // ---- members of a group

    @Test
    public void addingAClientSendsTheFullWantedList() {
        controls.addClients(KEY, GROUP, Arrays.asList("d"));
        actions.runAll();

        assertEquals("[\"a\",\"b\",\"c\",\"d\"]", caller.last("Group.SetClients").optJSONArray("clients").toString());
        assertEquals(GROUP, caller.last("Group.SetClients").optString("id"));
    }

    @Test
    public void removingAClientSendsTheOtherMembers() {
        controls.removeClient(KEY, GROUP, "b");
        actions.runAll();

        assertEquals("[\"a\",\"c\"]", caller.last("Group.SetClients").optJSONArray("clients").toString());
    }

    @Test
    public void theLastMemberIsNotRemovedAndTheUserIsTold() {
        controls.removeClient(KEY, "g2", "d");
        actions.runAll();

        assertEquals(0, caller.count("Group.SetClients"));
        assertEquals(SnapcastControls.Failure.Kind.NOT_POSSIBLE, failures.get(0).getKind());
    }

    @Test
    public void anUnknownMemberIsNotRemoved() {
        controls.removeClient(KEY, GROUP, "zzz");
        actions.runAll();

        assertEquals(0, caller.count("Group.SetClients"));
        assertEquals(SnapcastControls.Failure.Kind.NOT_POSSIBLE, failures.get(0).getKind());
    }

    // ---- failures

    @Test
    public void aServerWithoutALinkIsUnreachable() {
        SnapcastControls none = new SnapcastControls(key -> null, actions, volumes, () -> now.get(),
                (action, failure) -> failures.add(failure));
        none.setGroupMuted(KEY, GROUP, true);
        actions.runAll();

        assertEquals(SnapcastControls.Failure.Kind.UNREACHABLE, failures.get(0).getKind());
    }

    @Test
    public void aLinkWithoutOperationsIsUnreachable() {
        SnapcastControls none = new SnapcastControls(key -> new ModelLink(caller, link.getStatus()) {
            @Override
            public SnapcastOperations getOperations() {
                return null;
            }
        }, actions, volumes, () -> now.get(), (action, failure) -> failures.add(failure));
        none.setClientLatency(KEY, "a", 5);
        actions.runAll();

        assertEquals(SnapcastControls.Failure.Kind.UNREACHABLE, failures.get(0).getKind());
    }

    @Test
    public void aLostConnectionIsUnreachable() {
        caller.failure = new SnapcastException(SnapcastException.Kind.NOT_CONNECTED, 0, "x", null, null);
        controls.setClientLatency(KEY, "a", 5);
        actions.runAll();

        assertEquals(SnapcastControls.Failure.Kind.UNREACHABLE, failures.get(0).getKind());
        assertEquals(SnapcastControls.Action.CLIENT_LATENCY, failedActions.get(0));
    }

    @Test
    public void aTimeoutIsReportedAsATimeout() {
        caller.failure = new SnapcastException(SnapcastException.Kind.TIMEOUT, 0, "slow", null, null);
        controls.setGroupStream(KEY, GROUP, "Radio");
        actions.runAll();

        assertEquals(SnapcastControls.Failure.Kind.TIMEOUT, failures.get(0).getKind());
    }

    @Test
    public void aLoginDemandIsReportedAsUnauthorized() {
        caller.failure = new SnapcastException(SnapcastException.Kind.SERVER_ERROR, 401, "Unauthorized", null, null);
        controls.setGroupMuted(KEY, GROUP, true);
        actions.runAll();

        assertEquals(SnapcastControls.Failure.Kind.UNAUTHORIZED, failures.get(0).getKind());
    }

    @Test
    public void aServerErrorShowsOnlyACleanedAndShortenedReason() {
        StringBuilder text = new StringBuilder("tcp\u200B sources \u2060are not allowed ");
        for (int i = 0; i < 100; i++) {
            text.append("blah ");
        }
        caller.failure = new SnapcastException(SnapcastException.Kind.SERVER_ERROR, -32603, "Internal error",
                text.toString(), null);
        controls.setGroupStream(KEY, GROUP, "Radio");
        actions.runAll();

        SnapcastControls.Failure failure = failures.get(0);
        assertEquals(SnapcastControls.Failure.Kind.REJECTED, failure.getKind());
        assertFalse(failure.getReason().contains("\u200B"));
        assertFalse(failure.getReason().contains("\u2060"));
        assertTrue(failure.getReason().length() <= 80);
        assertTrue(failure.getReason(), failure.getReason().startsWith("Internal error: tcp sources are not allowed"));
    }

    @Test
    public void otherFailuresCarryNoServerText() {
        caller.failure = new SnapcastException(SnapcastException.Kind.MALFORMED, 0, "secret 10.0.0.1", null, null);
        controls.setClientLatency(KEY, "a", 5);
        actions.runAll();

        assertEquals(SnapcastControls.Failure.Kind.FAILED, failures.get(0).getKind());
        assertEquals("", failures.get(0).getReason());
    }

    @Test
    public void aFailureDoesNotStopTheNextChange() {
        caller.failure = new SnapcastException(SnapcastException.Kind.TIMEOUT, 0, "slow", null, null);
        controls.setClientLatency(KEY, "a", 5);
        actions.runAll();
        caller.failure = null;
        controls.setClientLatency(KEY, "a", 6);
        actions.runAll();

        assertEquals(1, failures.size());
        assertEquals(6, link.getStatus().findClient("a").getLatencyMs());
    }

    @Test
    public void aReporterThatThrowsDoesNotBreakTheWorker() {
        SnapcastControls loud = new SnapcastControls(key -> null, actions, volumes, () -> now.get(), (action, failure) -> {
            throw new IllegalStateException("boom");
        });
        loud.setGroupMuted(KEY, GROUP, true);
        actions.runAll(); // must not throw
        loud.setGroupMuted(KEY, GROUP, false);
        actions.runAll();
    }

    @Test
    public void aWrongArgumentIsReportedNotThrownOnTheWorker() {
        controls.setClientLatency(KEY, "", 5);
        controls.setGroupStream(KEY, GROUP, "");
        actions.runAll(); // the library throws IllegalArgumentException for an empty id: it must not escape

        assertEquals(2, failures.size());
        assertEquals(SnapcastControls.Failure.Kind.FAILED, failures.get(0).getKind());
    }

    @Test
    public void realThreadsRunTheChangeOffTheCallingThread() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            final CountDownLatch done = new CountDownLatch(1);
            final Thread[] ran = new Thread[1];
            RecordingCaller threadCaller = new RecordingCaller() {
                @Override
                public synchronized JSONObject call(String method, JSONObject callParams) throws SnapcastException {
                    ran[0] = Thread.currentThread();
                    done.countDown();
                    return super.call(method, callParams);
                }
            };
            ModelLink threaded = new ModelLink(threadCaller, link.getStatus());
            SnapcastControls real = new SnapcastControls(key -> threaded, worker, worker, () -> now.get(),
                    (action, failure) -> { });

            real.setClientLatency(KEY, "a", 5);

            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertNotNull(ran[0]);
            assertFalse(Thread.currentThread() == ran[0]);
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    public void nothingIsRememberedAboutAFinishedGesture() {
        controls.beginGroupGesture(KEY, GROUP);
        controls.setGroupVolume(KEY, GROUP, 60);
        volumes.runAll();

        assertNull(controls.pendingVolumeFor(KEY, GROUP));
        assertEquals(0, volumes.tasks.size());
    }
}
