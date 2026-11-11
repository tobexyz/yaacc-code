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
package de.yaacc.multiroom.feed;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import de.yaacc.multiroom.control.RetryScheduler;
import de.yaacc.multiroom.control.SnapcastClient;
import de.yaacc.multiroom.control.SnapcastException;
import de.yaacc.multiroom.control.SnapcastGroup;
import de.yaacc.multiroom.control.SnapcastVolume;

import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Executor;

/**
 * The coordination of a feed without Android and without a network: fakes for the stream handling, the server, the
 * connection to the stream port, the slot, the clock and the timer. The executor is direct unless a test needs to see
 * what is queued.
 */
public class SnapcastFeedControllerTest {

    private static final String GROUP = "group-1";
    private static final InetAddress PEER = address(127, 0, 0, 1);
    private static final InetAddress OTHER = address(127, 0, 0, 2);

    private static InetAddress address(int... parts) {
        try {
            byte[] bytes = new byte[parts.length];
            for (int i = 0; i < parts.length; i++) {
                bytes[i] = (byte) parts[i];
            }
            return InetAddress.getByAddress(bytes);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // ---- fakes

    /** One shared list of calls, in order, so that a test can see what came before what. */
    private final List<String> calls = new ArrayList<>();

    private class FakeStreams implements FeedStreams {
        SnapcastStreams.EnsureResult ensureResult = ready(4955);
        SnapcastStreams.UseResult useResult = SnapcastStreams.UseResult.OK;
        boolean removeIfIdleResult = true;
        boolean shutdownResult = true;
        RuntimeException ensureFailure;
        RuntimeException shutdownFailure;
        int ensureCalls;
        int useForCalls;
        int releaseCalls;
        int noteAudioCalls;
        int removeIfIdleCalls;
        int shutdownCalls;

        @Override
        public SnapcastStreams.EnsureResult ensure() {
            ensureCalls++;
            calls.add("ensure");
            if (ensureFailure != null) {
                throw ensureFailure;
            }
            return ensureResult;
        }

        @Override
        public SnapcastStreams.UseResult useFor(String groupId) {
            useForCalls++;
            calls.add("useFor");
            assertEquals(GROUP, groupId);
            return useResult;
        }

        @Override
        public SnapcastStreams.ReleaseResult release(String groupId) {
            releaseCalls++;
            calls.add("release");
            return SnapcastStreams.ReleaseResult.RESTORED;
        }

        @Override
        public void noteAudio() {
            noteAudioCalls++;
        }

        @Override
        public boolean removeIfIdle() {
            removeIfIdleCalls++;
            calls.add("removeIfIdle");
            return removeIfIdleResult;
        }

        @Override
        public boolean shutdown() {
            shutdownCalls++;
            calls.add("shutdown");
            if (shutdownFailure != null) {
                throw shutdownFailure;
            }
            return shutdownResult;
        }
    }

    private static SnapcastStreams.EnsureResult ready(int port) {
        return new SnapcastStreams.EnsureResult(SnapcastStreams.Status.READY, "YAACC Phone 0123abcd", port, false, null, null);
    }

    private static SnapcastStreams.EnsureResult failed(SnapcastStreams.Status status, String reason, String hint) {
        return new SnapcastStreams.EnsureResult(status, null, 0, false, reason, hint);
    }

    private class FakeServer implements FeedServer {
        boolean controlUp = true;
        InetAddress peer = PEER;
        SnapcastGroup group = group(60, 80);
        final List<Object[]> volumeCalls = new ArrayList<>();
        final List<Boolean> muteCalls = new ArrayList<>();
        Runnable duringAwait;
        long generation = 7;
        /** The control connection is replaced while the start reads the peer address. */
        boolean reconnectWhileReadingPeer;

        @Override
        public long connectionGeneration() {
            calls.add("generation");
            return generation;
        }

        @Override
        public boolean awaitControl(long timeoutMillis) {
            calls.add("awaitControl");
            if (duringAwait != null) {
                duringAwait.run();
            }
            return controlUp;
        }

        @Override
        public InetAddress peerAddress() {
            calls.add("peer");
            if (reconnectWhileReadingPeer) {
                generation++;
            }
            return peer;
        }

        @Override
        public SnapcastGroup findGroup(String groupId) {
            assertEquals(GROUP, groupId);
            return group;
        }

        @Override
        public void setGroupVolume(SnapcastGroup current, Map<String, Integer> startLevels, int percent)
                throws SnapcastException {
            volumeCalls.add(new Object[]{current, new LinkedHashMap<>(startLevels), percent});
        }

        @Override
        public void setGroupMuted(String groupId, boolean muted) {
            muteCalls.add(muted);
        }
    }

    private static SnapcastGroup group(int... volumes) {
        List<SnapcastClient> clients = new ArrayList<>();
        for (int i = 0; i < volumes.length; i++) {
            clients.add(new SnapcastClient("c" + i, "client " + i, "", true, new SnapcastVolume(volumes[i], false), 0));
        }
        return new SnapcastGroup(GROUP, "Room", false, "Default", clients);
    }

    private final class FakeOutput implements FeedOutput {
        final List<byte[]> written = new ArrayList<>();
        int discards;
        boolean closed;
        boolean running = true;
        int queued;
        int queuedDrainPerSleep = Integer.MAX_VALUE;
        boolean accept = true;
        RuntimeException closeFailure;
        final InetAddress address;
        final int port;
        final Runnable onFailed;

        FakeOutput(InetAddress address, int port, Runnable onFailed) {
            this.address = address;
            this.port = port;
            this.onFailed = onFailed;
        }

        @Override
        public boolean write(byte[] data, int offset, int length) {
            if (!accept) {
                return false;
            }
            written.add(Arrays.copyOfRange(data, offset, offset + length));
            return true;
        }

        @Override
        public void discard() {
            discards++;
        }

        @Override
        public boolean isRunning() {
            return running && !closed;
        }

        @Override
        public int getQueuedBytes() {
            return queued;
        }

        @Override
        public void close() {
            closed = true;
            calls.add("close");
            if (closeFailure != null) {
                throw closeFailure;
            }
        }

        @Override
        public String statistics() {
            return "chunks=" + written.size();
        }
    }

    private final class FakeOutputs implements FeedOutputFactory {
        final List<FakeOutput> opened = new ArrayList<>();
        IOException failure;

        @Override
        public FeedOutput open(InetAddress address, int port, Runnable onFailed) throws IOException {
            calls.add("open");
            if (failure != null) {
                throw failure;
            }
            FakeOutput output = new FakeOutput(address, port, onFailed);
            opened.add(output);
            return output;
        }

        FakeOutput last() {
            return opened.get(opened.size() - 1);
        }
    }

    private static final class FakeSlot implements FeedSlot {
        boolean available = true;
        boolean held;
        int acquires;

        @Override
        public boolean tryAcquire(long waitMillis) {
            acquires++;
            if (!available && !held) {
                return false;
            }
            held = true;
            return true;
        }

        @Override
        public void release() {
            held = false;
        }
    }

    /** Sleeping moves the clock; nothing really waits. */
    private static final class TestClock implements FeedClock {
        long nanos = 1_000_000_000_000L;
        final List<Long> sleeps = new ArrayList<>();
        Runnable onSleep;

        @Override
        public long nanoTime() {
            return nanos;
        }

        @Override
        public void sleepNanos(long amount) {
            sleeps.add(amount / 1_000_000L);
            nanos += amount;
            if (onSleep != null) {
                onSleep.run();
            }
        }

        @Override
        public void wake(Thread thread) {
            // nobody sleeps for real
        }

        void advanceMillis(long millis) {
            nanos += millis * 1_000_000L;
        }
    }

    private static final class ManualScheduler implements RetryScheduler {
        final List<long[]> delays = new ArrayList<>();
        final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void schedule(Runnable task, long delayMillis) {
            delays.add(new long[]{delayMillis});
            tasks.add(task);
        }

        boolean runNext() {
            Runnable task = tasks.poll();
            if (task == null) {
                return false;
            }
            task.run();
            return true;
        }

        long lastDelay() {
            return delays.get(delays.size() - 1)[0];
        }
    }

    /** Keeps what is submitted until the test runs it. */
    private static final class QueueExecutor implements Executor {
        final Queue<Runnable> queue = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            queue.add(command);
        }

        void runAll() {
            Runnable task;
            while ((task = queue.poll()) != null) {
                task.run();
            }
        }
    }

    private static final class Events implements SnapcastFeedController.Listener {
        final List<FeedError> lost = new ArrayList<>();
        final List<String> log = new ArrayList<>();

        @Override
        public void onFeedLost(FeedError error) {
            lost.add(error);
        }

        @Override
        public void onLog(String line) {
            log.add(line);
        }
    }

    private static final class Answer implements SnapcastFeedController.StartCallback {
        int ready;
        final List<FeedError> failures = new ArrayList<>();

        @Override
        public void onReady() {
            ready++;
        }

        @Override
        public void onFailed(FeedError error) {
            failures.add(error);
        }
    }

    private final FakeStreams streams = new FakeStreams();
    private final FakeServer server = new FakeServer();
    private final FakeOutputs outputs = new FakeOutputs();
    private final FakeSlot slot = new FakeSlot();
    private final TestClock clock = new TestClock();
    private final ManualScheduler scheduler = new ManualScheduler();
    private final Events events = new Events();
    private final boolean[] released = new boolean[1];
    private Executor executor = Runnable::run;
    private SnapcastFeedController.Config config = new SnapcastFeedController.Config();

    private SnapcastFeedController controller() {
        return new SnapcastFeedController(streams, server, outputs, slot, GROUP, clock, scheduler, executor, events, config,
                () -> released[0] = true);
    }

    private SnapcastFeedController started(Answer answer) {
        SnapcastFeedController controller = controller();
        controller.start(answer);
        assertEquals("started", 1, answer.ready);
        return controller;
    }

    // ---- start

    @Test
    public void aStartEnsuresTheStreamMovesTheGroupAndThenReadsThePeerAddressRightBeforeConnecting() {
        Answer answer = new Answer();
        SnapcastFeedController controller = controller();

        controller.start(answer);

        assertEquals(Arrays.asList("awaitControl", "generation", "ensure", "useFor", "peer", "generation", "open"), calls);
        assertEquals(1, answer.ready);
        assertTrue(controller.isFeeding());
        assertEquals(SnapcastFeedController.State.READY, controller.getState());
    }

    @Test
    public void theFeedConnectsToThePeerOfTheControlConnectionAndThePortThatWasAskedForAndToNothingElse() {
        server.peer = OTHER;
        streams.ensureResult = ready(4961);

        started(new Answer());

        assertEquals(1, outputs.opened.size());
        assertSame(OTHER, outputs.last().address);
        assertEquals(4961, outputs.last().port);
    }

    @Test
    public void theAddressIsReadAgainForEveryStartSoAChangedControlConnectionIsFollowed() {
        SnapcastFeedController controller = started(new Answer());
        controller.stop();
        server.peer = OTHER;

        controller.start(new Answer());

        assertSame(PEER, outputs.opened.get(0).address);
        assertSame(OTHER, outputs.opened.get(1).address);
    }

    @Test
    public void aStartThatIsAlreadyFeedingAnswersAtOnceWithoutTouchingTheServer() {
        SnapcastFeedController controller = started(new Answer());
        calls.clear();
        Answer second = new Answer();

        controller.start(second);

        assertEquals(1, second.ready);
        assertTrue(calls.isEmpty());
        assertEquals(1, outputs.opened.size());
    }

    @Test
    public void aFeedThatDiedWithoutNoticeIsReplacedByTheNextStart() {
        SnapcastFeedController controller = started(new Answer());
        FakeOutput dead = outputs.last();
        dead.running = false;

        Answer second = new Answer();
        controller.start(second);

        assertEquals(1, second.ready);
        assertTrue(dead.closed);
        assertEquals(2, outputs.opened.size());
        assertTrue(controller.isFeeding());
    }

    @Test
    public void aStreamThatTheServerLostIsMadeAgainOnce() {
        final SnapcastStreams.UseResult[] answers = {SnapcastStreams.UseResult.NOT_READY, SnapcastStreams.UseResult.OK};
        FakeStreams flaky = new FakeStreams() {
            int index;

            @Override
            public SnapcastStreams.UseResult useFor(String groupId) {
                super.useFor(groupId);
                return answers[index++];
            }
        };
        SnapcastFeedController controller = new SnapcastFeedController(flaky, server, outputs, slot, GROUP, clock, scheduler,
                executor, events, config, null);
        Answer answer = new Answer();

        controller.start(answer);

        assertEquals(1, answer.ready);
        assertEquals(2, flaky.ensureCalls);
        assertEquals(2, flaky.useForCalls);
    }

    // ---- start failures: a typed result, and everything undone

    private void assertNothingLeftRunning(SnapcastFeedController controller) {
        assertFalse(controller.isFeeding());
        assertEquals(SnapcastFeedController.State.IDLE, controller.getState());
        assertFalse("the slot is free", slot.held);
        for (FakeOutput output : outputs.opened) {
            assertTrue("no feed stays open", output.closed);
        }
    }

    @Test
    public void withoutAControlConnectionNothingIsDoneOnTheServer() {
        server.controlUp = false;
        Answer answer = new Answer();
        SnapcastFeedController controller = controller();

        controller.start(answer);

        assertEquals(FeedError.Kind.NO_CONTROL, answer.failures.get(0).getKind());
        assertEquals(0, streams.ensureCalls);
        assertEquals(0, answer.ready);
        assertNothingLeftRunning(controller);
    }

    @Test
    public void everyAnswerOfEnsureHasItsOwnKindAndKeepsTheReasonAndTheHint() {
        Object[][] table = {
                {SnapcastStreams.Status.REFUSED, FeedError.Kind.REFUSED, "no way", SnapcastStreams.HINT_STATIC_SOURCE},
                {SnapcastStreams.Status.UNAUTHORIZED, FeedError.Kind.UNAUTHORIZED, "login", SnapcastStreams.HINT_AUTHORIZATION},
                {SnapcastStreams.Status.NO_FREE_PORT, FeedError.Kind.NO_FREE_PORT, "full", SnapcastStreams.HINT_STATIC_SOURCE},
                {SnapcastStreams.Status.CONFLICT, FeedError.Kind.CONFLICT, "busy", SnapcastStreams.HINT_STATIC_SOURCE},
                {SnapcastStreams.Status.FAILED, FeedError.Kind.SERVER_FAILED, "timeout", null},
        };
        for (Object[] row : table) {
            streams.ensureResult = failed((SnapcastStreams.Status) row[0], (String) row[2], (String) row[3]);
            Answer answer = new Answer();
            SnapcastFeedController controller = controller();

            controller.start(answer);

            assertEquals(row[0].toString(), row[1], answer.failures.get(0).getKind());
            assertEquals(row[2], answer.failures.get(0).getReason());
            assertEquals(row[3], answer.failures.get(0).getHintKey());
            assertEquals(0, streams.useForCalls);
            assertNothingLeftRunning(controller);
            slot.held = false;
        }
        assertTrue("no feed was ever opened", outputs.opened.isEmpty());
    }

    @Test
    public void aGroupThatIsGoneEndsTheStartAndTheSlotIsFree() {
        streams.useResult = SnapcastStreams.UseResult.GROUP_NOT_FOUND;
        Answer answer = new Answer();
        SnapcastFeedController controller = controller();

        controller.start(answer);

        assertEquals(FeedError.Kind.GROUP_GONE, answer.failures.get(0).getKind());
        assertNothingLeftRunning(controller);
        assertTrue(outputs.opened.isEmpty());
    }

    @Test
    public void aFailedMoveStillGivesTheGroupBackBecauseTheServerMayHaveAppliedIt() {
        streams.useResult = SnapcastStreams.UseResult.FAILED;
        Answer answer = new Answer();
        SnapcastFeedController controller = controller();

        controller.start(answer);

        assertEquals(FeedError.Kind.SERVER_FAILED, answer.failures.get(0).getKind());
        assertEquals("release is asked for: the record of SnapcastStreams decides if there is anything to undo", 1,
                streams.releaseCalls);
        assertNothingLeftRunning(controller);
    }

    @Test
    public void withoutAPeerAddressNothingConnectsAndTheGroupIsGivenBack() {
        server.peer = null;
        Answer answer = new Answer();
        SnapcastFeedController controller = controller();

        controller.start(answer);

        assertEquals(FeedError.Kind.NO_CONTROL, answer.failures.get(0).getKind());
        assertTrue(outputs.opened.isEmpty());
        assertEquals(1, streams.releaseCalls);
        assertNothingLeftRunning(controller);
    }

    @Test
    public void aFeedThatCanNotConnectGivesTheGroupBackAndFreesTheSlot() {
        outputs.failure = new IOException("feed connection failed");
        Answer answer = new Answer();
        SnapcastFeedController controller = controller();

        controller.start(answer);

        assertEquals(FeedError.Kind.CONNECT_FAILED, answer.failures.get(0).getKind());
        assertEquals(1, streams.releaseCalls);
        assertNothingLeftRunning(controller);
    }

    @Test
    public void anUnexpectedExceptionBecomesAFailureAndIsUndone() {
        streams.ensureFailure = new IllegalStateException("boom");
        Answer answer = new Answer();
        SnapcastFeedController controller = controller();

        controller.start(answer);

        assertEquals(FeedError.Kind.SERVER_FAILED, answer.failures.get(0).getKind());
        assertNothingLeftRunning(controller);
    }

    @Test
    public void aFailureIsReportedOnceAndTheNextStartTriesAgain() {
        streams.ensureResult = failed(SnapcastStreams.Status.FAILED, "timeout", null);
        Answer first = new Answer();
        SnapcastFeedController controller = controller();
        controller.start(first);
        streams.ensureResult = ready(4953);
        Answer second = new Answer();

        controller.start(second);

        assertEquals(1, first.failures.size());
        assertEquals(1, second.ready);
        assertTrue(controller.isFeeding());
    }

    @Test
    public void onlyOneFeedPerServerAtATimeTheOtherGroupHearsBusy() {
        slot.available = false;
        Answer answer = new Answer();
        SnapcastFeedController controller = controller();

        controller.start(answer);

        assertEquals(FeedError.Kind.BUSY, answer.failures.get(0).getKind());
        assertEquals("the server is not touched", 0, streams.ensureCalls);
        assertEquals(1, slot.acquires);
    }

    // ---- the audio

    @Test
    public void theSinkHandsAudioToTheOpenFeedAndRefusesWithoutOne() {
        SnapcastFeedController controller = controller();
        PcmSink sink = controller.sink();
        assertFalse("no feed yet", sink.write(new byte[8], 0, 8));

        controller.start(new Answer());
        byte[] audio = {1, 2, 3, 4, 5, 6, 7, 8};

        assertTrue(sink.write(audio, 2, 4));
        assertArrayEquals(new byte[]{3, 4, 5, 6}, outputs.last().written.get(0));
        assertSame("one sink for the whole life", sink, controller.sink());
    }

    @Test
    public void everyWrittenBufferRestartsTheIdleTimerAndARefusedOneDoesNot() {
        SnapcastFeedController controller = started(new Answer());
        int before = streams.noteAudioCalls;

        controller.sink().write(new byte[4], 0, 4);
        controller.sink().write(new byte[4], 0, 4);
        outputs.last().accept = false;
        controller.sink().write(new byte[4], 0, 4);

        assertEquals(before + 2, streams.noteAudioCalls);
    }

    @Test
    public void aPauseDiscardsWhatIsQueuedAndKeepsTheFeedOpen() {
        SnapcastFeedController controller = started(new Answer());

        controller.pause();

        assertEquals(1, outputs.last().discards);
        assertFalse(outputs.last().closed);
        assertTrue(controller.isFeeding());
    }

    @Test
    public void aFlushOfTheTapIsADiscardOfTheFeed() {
        SnapcastFeedController controller = started(new Answer());

        controller.sink().discard();

        assertEquals(1, outputs.last().discards);
    }

    @Test
    public void aPauseWithoutAFeedDoesNothing() {
        controller().pause();
        controller().sink().discard();
    }

    // ---- stop

    @Test
    public void aStopClosesTheFeedGivesTheGroupItsStreamBackAndFreesTheSlot() {
        SnapcastFeedController controller = started(new Answer());
        FakeOutput output = outputs.last();

        controller.stop();

        assertTrue(output.closed);
        assertEquals(1, streams.releaseCalls);
        assertEquals("the stream itself stays for a start soon after", 0, streams.shutdownCalls);
        assertNothingLeftRunning(controller);
        assertFalse("the sink refuses now", controller.sink().write(new byte[4], 0, 4));
    }

    @Test
    public void aStopWaitsForTheQueueAndThenForTheChunksThatTheWriterStillHoldsBeforeItCloses() {
        SnapcastFeedController controller = started(new Answer());
        final FakeOutput output = outputs.last();
        output.queued = 7_680;
        clock.onSleep = () -> {
            output.queued = Math.max(0, output.queued - 3_840);
            assertFalse("not closed while draining", output.closed);
        };

        controller.stop();

        assertEquals("two chunks drained in two steps, then the grace", Arrays.asList(5L, 5L, 60L), clock.sleeps);
        assertTrue(output.closed);
    }

    @Test
    public void theDrainIsBoundedWhenTheQueueNeverEmpties() {
        SnapcastFeedController controller = started(new Answer());
        FakeOutput output = outputs.last();
        output.queued = 3_000; // a partial chunk that waits for more audio

        controller.stop();

        long total = 0;
        for (long sleep : clock.sleeps) {
            total += sleep;
        }
        assertTrue("at most 250 ms + 60 ms, was " + total, total <= 250 + 60);
        assertTrue(output.closed);
    }

    @Test
    public void aFeedThatAlreadyFailedIsClosedWithoutADrain() {
        SnapcastFeedController controller = started(new Answer());
        outputs.last().running = false;
        outputs.last().queued = 10_000;

        controller.stop();

        assertTrue(clock.sleeps.isEmpty());
        assertTrue(outputs.last().closed);
    }

    @Test
    public void theFeedIsNeverClosedOnTheThreadThatCallsStop() {
        QueueExecutor queue = new QueueExecutor();
        executor = queue;
        SnapcastFeedController controller = controller();
        controller.start(new Answer());
        queue.runAll();
        FakeOutput output = outputs.last();

        controller.stop();

        assertFalse("close() may block for seconds: it waits for the worker", output.closed);
        assertEquals(1, queue.queue.size());
        queue.runAll();
        assertTrue(output.closed);
    }

    @Test
    public void aStopOfAControllerThatNeverStartedTouchesNothing() {
        SnapcastFeedController controller = controller();

        controller.stop();

        assertTrue(calls.isEmpty());
        assertEquals(0, streams.releaseCalls);
    }

    @Test
    public void aStopThatComesBeforeTheStartRunsCancelsItWithoutAnAnswer() {
        QueueExecutor queue = new QueueExecutor();
        executor = queue;
        Answer answer = new Answer();
        SnapcastFeedController controller = controller();

        controller.start(answer);
        controller.stop();
        queue.runAll();

        assertEquals(0, answer.ready);
        assertTrue(answer.failures.isEmpty());
        assertTrue(outputs.opened.isEmpty());
        assertEquals(0, streams.ensureCalls);
    }

    @Test
    public void aStopInTheMiddleOfAStartCancelsItAndTheStopCleansUp() {
        QueueExecutor queue = new QueueExecutor();
        executor = queue;
        final SnapcastFeedController controller = controller();
        Answer answer = new Answer();
        server.duringAwait = controller::stop; // the user taps stop while the start waits for the server

        controller.start(answer);
        queue.runAll();

        assertEquals("no answer: ExoPlayer must not start", 0, answer.ready);
        assertTrue(answer.failures.isEmpty());
        assertFalse(slot.held);
        assertEquals(SnapcastFeedController.State.IDLE, controller.getState());
        for (FakeOutput output : outputs.opened) {
            assertTrue(output.closed);
        }
    }

    @Test
    public void aStopAfterTheFeedWasOpenedButBeforeTheAnswerClosesItAndGivesTheGroupBack() {
        QueueExecutor queue = new QueueExecutor();
        executor = queue;
        final SnapcastFeedController controller = controller();
        Answer answer = new Answer();
        server.peer = PEER;
        controller.start(answer);
        // run the start task only, then stop before any later task
        queue.queue.poll().run();
        assertEquals(1, answer.ready);

        controller.stop();
        queue.runAll();

        assertTrue(outputs.last().closed);
        assertEquals(1, streams.releaseCalls);
    }

    // ---- losing the feed

    @Test
    public void aFeedThatBreaksWhilePlayingIsClosedAndTheListenerHearsOnce() {
        SnapcastFeedController controller = started(new Answer());
        FakeOutput output = outputs.last();

        output.onFailed.run();

        assertEquals(1, events.lost.size());
        assertEquals(FeedError.Kind.FEED_FAILED, events.lost.get(0).getKind());
        assertTrue(output.closed);
        assertEquals("the group gets its stream back", 1, streams.releaseCalls);
        assertNothingLeftRunning(controller);
        assertFalse(controller.sink().write(new byte[4], 0, 4));
    }

    @Test
    public void theFailureOfAnOlderFeedDoesNotTouchTheNewOne() {
        SnapcastFeedController controller = started(new Answer());
        FakeOutput old = outputs.last();
        controller.stop();
        controller.start(new Answer());

        old.onFailed.run();

        assertTrue(events.lost.isEmpty());
        assertTrue(controller.isFeeding());
    }

    @Test
    public void aLostControlConnectionClosesTheFeedAtOnceWithoutDrainOrServerCalls() {
        SnapcastFeedController controller = started(new Answer());
        FakeOutput output = outputs.last();
        output.queued = 10_000;
        int releases = streams.releaseCalls;

        controller.controlLost();

        assertTrue(output.closed);
        assertTrue("no drain", clock.sleeps.isEmpty());
        assertEquals("the server can not be reached to restore the group", releases, streams.releaseCalls);
        assertEquals(FeedError.Kind.CONTROL_LOST, events.lost.get(0).getKind());
        assertFalse(slot.held);
        assertFalse(controller.isFeeding());
    }

    @Test
    public void aLostControlConnectionWithoutAFeedIsNoNews() {
        SnapcastFeedController controller = controller();

        controller.controlLost();

        assertTrue(events.lost.isEmpty());
    }

    @Test
    public void afterALostControlConnectionTheNextStopStillTriesToGiveTheGroupBack() {
        SnapcastFeedController controller = started(new Answer());
        controller.controlLost();

        controller.stop();

        assertEquals(1, streams.releaseCalls);
    }

    // ---- release

    @Test
    public void aReleaseClosesTheFeedRemovesTheStreamFreesTheSlotAndTellsTheOwner() {
        SnapcastFeedController controller = started(new Answer());
        FakeOutput output = outputs.last();

        controller.release();

        assertTrue(output.closed);
        assertEquals(1, streams.shutdownCalls);
        assertFalse(slot.held);
        assertTrue(released[0]);
        assertEquals(SnapcastFeedController.State.RELEASED, controller.getState());
        assertTrue("shutdown restores the groups itself", calls.indexOf("close") < calls.indexOf("shutdown"));
    }

    @Test
    public void afterAReleaseNothingStartsAgain() {
        SnapcastFeedController controller = started(new Answer());
        controller.release();
        calls.clear();
        Answer late = new Answer();

        controller.start(late);
        controller.stop();
        controller.pause();

        assertEquals(0, late.ready);
        assertTrue(late.failures.isEmpty());
        assertTrue(calls.isEmpty());
    }

    @Test
    public void aReleaseDoesNotCloseOnTheCallersThread() {
        QueueExecutor queue = new QueueExecutor();
        executor = queue;
        SnapcastFeedController controller = controller();
        controller.start(new Answer());
        queue.runAll();
        FakeOutput output = outputs.last();

        controller.release();

        assertFalse(output.closed);
        assertFalse(released[0]);
        queue.runAll();
        assertTrue(output.closed);
        assertTrue(released[0]);
    }

    @Test
    public void aReleaseThatIsAlreadyDoneIsNotRepeated() {
        SnapcastFeedController controller = started(new Answer());
        controller.release();
        controller.release();

        assertEquals(1, streams.shutdownCalls);
    }

    // ---- the idle timer

    private SnapcastFeedController readyWithIdleTimeout() {
        config = new SnapcastFeedController.Config().idleTimeoutMillis(600_000).idleCheckMillis(60_000)
                .maxIdleCheckMillis(600_000);
        return started(new Answer());
    }

    @Test
    public void aStreamThatIsHeldHasAnIdleCheckEveryMinute() {
        readyWithIdleTimeout();

        assertEquals(1, scheduler.tasks.size());
        assertEquals(60_000L, scheduler.lastDelay());
    }

    @Test
    public void whileAudioFlowsTheIdleCheckDoesNothingButLookAgain() {
        SnapcastFeedController controller = readyWithIdleTimeout();
        clock.advanceMillis(120_000);
        controller.sink().write(new byte[4], 0, 4);

        scheduler.runNext();

        assertEquals(0, streams.removeIfIdleCalls);
        assertFalse(outputs.last().closed);
        assertEquals("again when it could be idle: ten minutes after the last audio (the cap of the interval)", 600_000L,
                scheduler.lastDelay());
        assertTrue(controller.isFeeding());
    }

    @Test
    public void aLongPauseClosesTheFeedGivesTheGroupBackAndRemovesTheStream() {
        SnapcastFeedController controller = readyWithIdleTimeout();
        clock.advanceMillis(600_000);
        FakeOutput output = outputs.last();

        scheduler.runNext();

        assertTrue(output.closed);
        assertEquals(1, streams.releaseCalls);
        assertEquals(1, streams.removeIfIdleCalls);
        assertFalse(slot.held);
        assertEquals(SnapcastFeedController.State.IDLE, controller.getState());
        assertEquals("nothing more to check", 0, scheduler.tasks.size());
    }

    @Test
    public void whenTheStreamCanNotBeRemovedTheNextTryComesLaterAndLaterUpToTenMinutes() {
        readyWithIdleTimeout();
        streams.removeIfIdleResult = false;
        clock.advanceMillis(600_000);

        List<Long> delays = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            scheduler.runNext();
            delays.add(scheduler.lastDelay());
        }

        assertEquals(Arrays.asList(60_000L, 120_000L, 240_000L, 480_000L, 600_000L, 600_000L), delays);
        assertEquals(6, streams.removeIfIdleCalls);
    }

    @Test
    public void theBackOffStartsAgainAfterTheStreamWasRemovedAndMadeAgain() {
        SnapcastFeedController controller = readyWithIdleTimeout();
        streams.removeIfIdleResult = false;
        clock.advanceMillis(600_000);
        scheduler.runNext();
        scheduler.runNext();
        streams.removeIfIdleResult = true;
        scheduler.runNext();
        assertEquals(0, scheduler.tasks.size());

        controller.start(new Answer());

        assertEquals(60_000L, scheduler.lastDelay());
        assertEquals(1, scheduler.tasks.size());
    }

    @Test
    public void aStreamLeftAfterAStopIsRemovedWhenItsTimeComesNotBefore() {
        SnapcastFeedController controller = readyWithIdleTimeout();
        clock.advanceMillis(30_000);
        controller.stop();
        scheduler.runNext(); // the check of the start: 60 s, idle for 30 s only
        assertEquals(0, streams.removeIfIdleCalls);

        clock.advanceMillis(700_000);
        scheduler.runNext();

        assertEquals(1, streams.removeIfIdleCalls);
        assertEquals(0, scheduler.tasks.size());
    }

    @Test
    public void aStartAfterTheIdleTeardownMakesTheStreamAgain() {
        SnapcastFeedController controller = readyWithIdleTimeout();
        clock.advanceMillis(600_000);
        scheduler.runNext();
        assertEquals(1, streams.ensureCalls);

        Answer again = new Answer();
        controller.start(again);

        assertEquals(1, again.ready);
        assertEquals(2, streams.ensureCalls);
        assertTrue(controller.isFeeding());
    }

    @Test
    public void noIdleCheckRunsAfterARelease() {
        SnapcastFeedController controller = readyWithIdleTimeout();
        controller.release();
        clock.advanceMillis(900_000);

        scheduler.runNext();

        assertEquals(0, streams.removeIfIdleCalls);
    }

    // ---- volume

    @Test
    public void theGroupVolumeIsTheAverageOfTheConnectedMembersAndMuteComesFromTheModel() {
        SnapcastFeedController controller = controller();
        assertEquals(70, controller.getVolume());
        assertFalse(controller.isMuted());

        server.group = group(10, 20, 30).withMuted(true);
        assertEquals(20, controller.getVolume());
        assertTrue(controller.isMuted());

        server.group = null;
        assertEquals(0, controller.getVolume());
        assertFalse(controller.isMuted());
    }

    @Test
    public void aDragIsRelativeToTheLevelsAtItsStart() {
        SnapcastFeedController controller = controller();
        Map<String, Integer> start = new LinkedHashMap<>();
        start.put("c0", 60);
        start.put("c1", 80);

        controller.setVolume(50);
        server.group = group(40, 60); // the server follows our first step
        clock.advanceMillis(500);
        controller.setVolume(30);

        assertEquals(2, server.volumeCalls.size());
        assertEquals(start, server.volumeCalls.get(0)[1]);
        assertEquals("the second step still counts from the start of the drag", start, server.volumeCalls.get(1)[1]);
        assertEquals(30, server.volumeCalls.get(1)[2]);
    }

    @Test
    public void aNewGestureAfterAPauseStartsFromTheCurrentLevels() {
        SnapcastFeedController controller = controller();
        controller.setVolume(50);
        server.group = group(40, 60);
        clock.advanceMillis(5_000);

        controller.setVolume(70);

        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("c0", 40);
        expected.put("c1", 60);
        assertEquals(expected, server.volumeCalls.get(1)[1]);
    }

    @Test
    public void ofAQuickSeriesOnlyTheLatestVolumeIsSent() {
        QueueExecutor queue = new QueueExecutor();
        executor = queue;
        SnapcastFeedController controller = controller();

        controller.setVolume(10);
        controller.setVolume(20);
        controller.setVolume(35);
        assertEquals("one task, not three", 1, queue.queue.size());
        queue.runAll();

        assertEquals(1, server.volumeCalls.size());
        assertEquals(35, server.volumeCalls.get(0)[2]);
    }

    @Test
    public void theVolumeIsClampedAndAGroupThatIsGoneIsLeftAlone() {
        SnapcastFeedController controller = controller();

        controller.setVolume(250);
        assertEquals(100, server.volumeCalls.get(0)[2]);
        controller.setVolume(-5);
        assertEquals(0, server.volumeCalls.get(1)[2]);

        server.group = null;
        controller.setVolume(40);
        assertEquals(2, server.volumeCalls.size());
    }

    @Test
    public void aFailedVolumeCallIsLoggedWithoutDetailsAndDoesNotEndTheFeed() {
        FakeServer failing = new FakeServer() {
            @Override
            public void setGroupVolume(SnapcastGroup current, Map<String, Integer> startLevels, int percent)
                    throws SnapcastException {
                throw new SnapcastException(SnapcastException.Kind.IO, 0, "io", null, null);
            }
        };
        SnapcastFeedController controller = new SnapcastFeedController(streams, failing, outputs, slot, GROUP, clock,
                scheduler, executor, events, config, null);
        controller.start(new Answer());

        controller.setVolume(40);

        assertTrue(events.log.contains("group volume failed"));
        assertTrue(controller.isFeeding());
    }

    @Test
    public void muteGoesToTheServer() {
        SnapcastFeedController controller = controller();

        controller.setMuted(true);
        controller.setMuted(false);

        assertEquals(Arrays.asList(true, false), server.muteCalls);
    }

    // ---- the log and the arguments

    @Test
    public void theLogLinesCarryStatesAndCountersButNoAddress() {
        SnapcastFeedController controller = started(new Answer());
        controller.sink().write(new byte[4], 0, 4);
        controller.stop();

        for (String line : events.log) {
            assertFalse(line, line.contains("127."));
            assertFalse(line, line.contains(PEER.getHostAddress()));
        }
        assertTrue(events.log.toString(), events.log.contains("feed ready"));
        boolean closedLine = false;
        for (String line : events.log) {
            closedLine |= line.startsWith("feed closed (stopped)") && line.contains("chunks=1");
        }
        assertTrue(events.log.toString(), closedLine);
    }

    @Test(expected = IllegalArgumentException.class)
    public void aMissingArgumentIsRefused() {
        new SnapcastFeedController(null, server, outputs, slot, GROUP, clock, scheduler, executor, events, config, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void anEmptyGroupIdIsRefused() {
        new SnapcastFeedController(streams, server, outputs, slot, "", clock, scheduler, executor, events, config, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void aStartWithoutACallbackIsRefused() {
        controller().start(null);
    }

    @Test
    public void aRejectingExecutorIsNotAProblem() {
        executor = command -> {
            throw new java.util.concurrent.RejectedExecutionException();
        };
        SnapcastFeedController controller = controller();

        controller.stop();
        controller.release();
        controller.setVolume(5);
        controller.setMuted(true);
        controller.controlLost();
        controller.start(new Answer());

        assertEquals(SnapcastFeedController.State.IDLE, controller.getState());
    }

    @Test
    public void theSlotsOfOneServerAreExclusiveAndAreFreedByTheHolder() {
        FeedSlots slots = new FeedSlots();
        FeedSlot a = slots.slotFor("server");
        FeedSlot b = slots.slotFor("server");
        FeedSlot other = slots.slotFor("another");

        assertTrue(a.tryAcquire(0));
        assertTrue("again for the holder", a.tryAcquire(0));
        assertFalse(b.tryAcquire(0));
        assertTrue("another server is independent", other.tryAcquire(0));

        b.release(); // not the holder: nothing happens
        assertFalse(b.tryAcquire(0));
        a.release();
        assertTrue(b.tryAcquire(0));
        a.release();
        b.release();
        other.release();
        assertEquals(0, slots.heldCount());
    }

    @Test
    public void aSlotWaitsForTheHolderThatIsJustEnding() throws Exception {
        final FeedSlots slots = new FeedSlots();
        final FeedSlot a = slots.slotFor("server");
        final FeedSlot b = slots.slotFor("server");
        assertTrue(a.tryAcquire(0));
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
                // test
            }
            a.release();
        });
        releaser.setDaemon(true);
        releaser.start();

        assertTrue("it waited for the release", b.tryAcquire(3_000));
        releaser.join(2_000);
    }

    // ---- S36: the cleanup always finishes, also when closing the feed throws

    @Test
    public void aFeedThatThrowsWhenItIsClosedStillLetsAStopFreeTheSlotAndGiveTheGroupBack() {
        SnapcastFeedController controller = started(new Answer());
        outputs.last().closeFailure = new IllegalStateException("boom");

        controller.stop();

        assertFalse("the slot is free", slot.held);
        assertEquals(1, streams.releaseCalls);
        assertEquals(SnapcastFeedController.State.IDLE, controller.getState());
        assertFalse(controller.sink().write(new byte[4], 0, 4));
    }

    @Test
    public void aFeedThatThrowsWhenItIsClosedStillLetsAReleaseRemoveTheStreamAndTellTheOwner() {
        SnapcastFeedController controller = started(new Answer());
        outputs.last().closeFailure = new IllegalStateException("boom");

        controller.release();

        assertEquals(1, streams.shutdownCalls);
        assertFalse(slot.held);
        assertTrue("the owner ended the worker and let go of the link", released[0]);
        assertEquals(SnapcastFeedController.State.RELEASED, controller.getState());
    }

    @Test
    public void aReleaseWhoseShutdownThrowsStillTellsTheOwner() {
        SnapcastFeedController controller = started(new Answer());
        streams.shutdownFailure = new IllegalStateException("boom");

        controller.release();

        assertTrue(released[0]);
        assertFalse(slot.held);
    }

    @Test
    public void aThrowingCloseDoesNotKeepTheSlotAfterAFeedFailureALostControlOrAnIdleTeardown() {
        SnapcastFeedController controller = started(new Answer());
        outputs.last().closeFailure = new IllegalStateException("boom");
        outputs.last().onFailed.run();
        assertFalse("feed failure", slot.held);
        assertEquals(1, events.lost.size());

        controller.start(new Answer());
        outputs.last().closeFailure = new IllegalStateException("boom");
        controller.controlLost();
        assertFalse("lost control", slot.held);
        assertEquals(2, events.lost.size());

        config = new SnapcastFeedController.Config().idleTimeoutMillis(600_000).idleCheckMillis(60_000);
        SnapcastFeedController idle = controller();
        idle.start(new Answer());
        outputs.last().closeFailure = new IllegalStateException("boom");
        clock.advanceMillis(600_000);
        scheduler.runNext();
        scheduler.runNext();
        assertFalse("idle teardown", slot.held);
    }

    // ---- S35: a volume gesture has its own executor and a budget, and never holds up stop or release

    @Test
    public void aVolumeGestureRunsOnItsOwnExecutorWhileTheWorkerIsBusy() {
        QueueExecutor busyWorker = new QueueExecutor();
        executor = busyWorker;
        config = new SnapcastFeedController.Config().volumeExecutor(Runnable::run);
        SnapcastFeedController controller = controller();
        controller.stop(); // the worker has work that has not run

        controller.setVolume(33);
        controller.setMuted(true);

        assertEquals("the volume was sent at once", 1, server.volumeCalls.size());
        assertEquals(33, server.volumeCalls.get(0)[2]);
        assertEquals(Arrays.asList(true), server.muteCalls);
        assertEquals("and the stop is still waiting on the worker", 1, busyWorker.queue.size());
    }

    @Test
    public void withoutAnExecutorOfItsOwnTheVolumeUsesTheWorkerAsBefore() {
        QueueExecutor worker = new QueueExecutor();
        executor = worker;
        SnapcastFeedController controller = controller();

        controller.setVolume(10);

        assertTrue(server.volumeCalls.isEmpty());
        worker.runAll();
        assertEquals(1, server.volumeCalls.size());
    }

    @Test
    public void aRejectingVolumeExecutorIsNotAProblem() {
        config = new SnapcastFeedController.Config().volumeExecutor(command -> {
            throw new java.util.concurrent.RejectedExecutionException();
        });
        SnapcastFeedController controller = controller();

        controller.setVolume(10);
        controller.setMuted(false);

        assertTrue(server.volumeCalls.isEmpty());
    }

    // ---- S38: the peer address belongs to the connection that was there before the stream was made

    @Test
    public void aControlConnectionThatWasReplacedBeforeThePeerWasReadEndsTheStartWithoutConnecting() {
        server.reconnectWhileReadingPeer = true;
        Answer answer = new Answer();
        SnapcastFeedController controller = controller();

        controller.start(answer);

        assertEquals(FeedError.Kind.NO_CONTROL, answer.failures.get(0).getKind());
        assertTrue("the feed never connected", outputs.opened.isEmpty());
        assertEquals("the group was given back", 1, streams.releaseCalls);
        assertNothingLeftRunning(controller);
    }

    @Test
    public void aGenerationThatDoesNotChangeLetsTheFeedConnect() {
        Answer answer = new Answer();

        controller().start(answer);

        assertEquals(1, answer.ready);
        assertEquals(1, outputs.opened.size());
    }
}
