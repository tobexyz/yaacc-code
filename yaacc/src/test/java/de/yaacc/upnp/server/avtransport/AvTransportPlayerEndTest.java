/*
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
package de.yaacc.upnp.server.avtransport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import de.yaacc.player.PlayMode;
import de.yaacc.player.Player;

/**
 * Issue 296, Home Assistant could not start music right after a TTS announcement: the renderer's transport stayed
 * PLAYING after the player had played to its end (nothing told the state machine), so Home Assistant, which
 * relies on the evented {@code TransportState}, skipped its {@code Play}; and the STOPPED event of a {@code Stop}
 * was held back by the event pacing, long enough for Home Assistant to look at its cached state first.
 * <p>
 * Run through the real receiving path of the app ({@link RendererHarness}); the fake players tell their listeners
 * {@link Player#PROPERTY_STOPPED} from {@code stop()} like {@code AbstractPlayer} does. Plain JVM.
 */
public class AvTransportPlayerEndTest {

    private static final String TTS = "http://192.0.2.1/tts.mp3";
    private static final String MUSIC = "http://192.0.2.1/music.mp3";

    private final List<RendererHarness> harnesses = new ArrayList<>();

    @After
    public void tearDown() {
        for (RendererHarness harness : harnesses) {
            harness.close();
        }
    }

    private RendererHarness renderer() {
        RendererHarness harness = new RendererHarness(service -> new AvTransportServiceManager(service, 2000));
        harnesses.add(harness);
        return harness;
    }

    // --- helpers

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("not within 5 s: " + what);
            }
            Thread.sleep(5);
        }
    }

    private static String transportState(RendererHarness h) {
        String body = h.soap("GetTransportInfo", "InstanceID", "0").getBodyString();
        int from = body.indexOf("<CurrentTransportState>") + "<CurrentTransportState>".length();
        return body.substring(from, body.indexOf("</CurrentTransportState>"));
    }

    private static String unescape(String xml) {
        return xml.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&");
    }

    private static boolean carries(String event, String variable, String value) {
        return unescape(event).contains("<" + variable + " val=\"" + value + "\"/>");
    }

    private static int indexOfEventWith(RendererHarness h, String variable, String value) {
        List<String> events = new ArrayList<>(h.sentEvents);
        for (int i = 0; i < events.size(); i++) {
            if (carries(events.get(i), variable, value)) {
                return i;
            }
        }
        return -1;
    }

    /** What Home Assistant's cached {@code transport_state} says: the last evented TransportState. */
    private static String lastEventedTransportState(RendererHarness h) {
        List<String> events = new ArrayList<>(h.sentEvents);
        for (int i = events.size() - 1; i >= 0; i--) {
            String text = unescape(events.get(i));
            int at = text.lastIndexOf("<TransportState val=\"");
            if (at >= 0) {
                int from = at + "<TransportState val=\"".length();
                return text.substring(from, text.indexOf('"', from));
            }
        }
        return null;
    }

    private static void subscribe(RendererHarness h) throws Exception {
        assertEquals(200, h.subscribeExchange());
        await("the initial event", () -> !h.sentEvents.isEmpty());
    }

    /** Home Assistant's {@code play_media}: Stop, SetAVTransportURI, Play. */
    private static void homeAssistantPlays(RendererHarness h, String uri) {
        assertTrue(RendererHarness.succeeded(h.stop()));
        assertTrue(RendererHarness.succeeded(h.setAvTransportUri(uri)));
        assertTrue(RendererHarness.succeeded(h.play()));
    }

    private static void stayAs(String what, RendererHarness h, String state) throws InterruptedException {
        Thread.sleep(400);
        assertEquals(what, state, transportState(h));
    }

    // --- the player ends: the transport leaves PLAYING like it does for a Stop

    @Test
    public void whenThePlayerEndsWithRepeatOffTheTransportIsStoppedAndTheControlPointIsToldAtOnce() throws Exception {
        RendererHarness h = renderer();
        h.setPlayMode("NORMAL");
        homeAssistantPlays(h, TTS);
        assertEquals("PLAYING", transportState(h));
        subscribe(h);
        Player player = h.players.get(0);

        h.playerEnds(player); // "End of the playlist reached, repeat is off: stopping"

        await("TransportState=STOPPED", () -> transportState(h).equals("STOPPED"));
        await("the STOPPED event", () -> indexOfEventWith(h, "TransportState", "STOPPED") >= 0);
        assertEquals("STOPPED", lastEventedTransportState(h));
        // the position stays readable, and Play is offered
        String position = h.soap("GetPositionInfo", "InstanceID", "0").getBodyString();
        assertTrue(position, position.contains(TTS));
        String actions = h.soap("GetCurrentTransportActions", "InstanceID", "0").getBodyString();
        assertTrue(actions, actions.contains("Play"));
    }

    @Test
    public void thePlayerIsNotStoppedAgainByTheTransportThatFollowsItsEnd() throws Exception {
        RendererHarness h = renderer();
        homeAssistantPlays(h, TTS);
        Player player = h.players.get(0);
        org.mockito.Mockito.clearInvocations(player);

        h.playerEnds(player);
        await("STOPPED", () -> transportState(h).equals("STOPPED"));
        Thread.sleep(200);

        // the one call is the end itself: the transport has no reason to stop a player that has stopped
        org.mockito.Mockito.verify(player, org.mockito.Mockito.times(1)).stop();
    }

    @Test
    public void whenThePlayerIsStoppedInTheAppWhilePlayingOrPausedTheTransportIsStopped() throws Exception {
        for (String before : new String[]{"PLAYING", "PAUSED_PLAYBACK"}) {
            RendererHarness h = renderer();
            homeAssistantPlays(h, TTS);
            if (before.equals("PAUSED_PLAYBACK")) {
                assertTrue(RendererHarness.succeeded(h.pause()));
            }
            assertEquals(before, transportState(h));

            h.players.get(0).stop(); // the stop button of the app

            await("STOPPED after " + before, () -> transportState(h).equals("STOPPED"));
        }
    }

    @Test
    public void whenThePlayerIsClosedInTheAppTheTransportIsStopped() throws Exception {
        for (String before : new String[]{"PLAYING", "PAUSED_PLAYBACK"}) {
            RendererHarness h = renderer();
            homeAssistantPlays(h, TTS);
            if (before.equals("PAUSED_PLAYBACK")) {
                assertTrue(RendererHarness.succeeded(h.pause()));
            }
            subscribe(h);

            h.closePlayer(h.players.get(0));

            await("STOPPED after " + before, () -> transportState(h).equals("STOPPED"));
            await("the STOPPED event", () -> "STOPPED".equals(lastEventedTransportState(h)));
        }
    }

    @Test
    public void aPausedTransportStaysPausedAndAPlayerThatDoesNotEndKeepsPlaying() throws Exception {
        RendererHarness h = renderer();
        homeAssistantPlays(h, TTS);
        assertTrue(RendererHarness.succeeded(h.pause()));

        stayAs("pause is not an end", h, "PAUSED_PLAYBACK");

        assertTrue(RendererHarness.succeeded(h.play()));
        stayAs("repeat one or all: the item ends, the player goes on and never stops", h, "PLAYING");
    }

    @Test
    public void theEndOfOneOfTwoPlayersDoesNotStopTheTransportWhileTheOtherPlays() throws Exception {
        RendererHarness h = renderer();
        homeAssistantPlays(h, TTS);
        Player first = h.players.get(0);
        Player second = h.addPlayer(PlayMode.NORMAL);
        second.play();
        h.transport().attachPlayers(List.of(second));

        h.playerEnds(first);

        stayAs("the second player plays", h, "PLAYING");
        h.playerEnds(second);
        await("both ended", () -> transportState(h).equals("STOPPED"));
    }

    // --- a stop that the transport caused, and a player that is replaced, change nothing

    @Test
    public void stopWhilePlayingStopsTheTransportOnceAndTheLateEndOfThePlayerDoesNotTouchTheNextPlayback() throws Exception {
        RendererHarness h = renderer();
        homeAssistantPlays(h, TTS);
        Player first = h.players.get(0);

        // Stop (the player's stop() tells its listeners, on another thread in the app), SetAVTransportURI, Play: the next item
        homeAssistantPlays(h, MUSIC);
        first.stop(); // the end of the old player's stop() that was still on its way

        stayAs("the new player plays", h, "PLAYING");
        assertFalse(first == h.players.get(0));
    }

    @Test
    public void aPlayerReplacedByTheNextItemDoesNotStopTheTransportWhenItEndsLate() throws Exception {
        RendererHarness h = renderer();
        h.removalNoticeDuringStart = false;
        homeAssistantPlays(h, TTS);
        Player old = h.players.get(0);

        assertTrue(RendererHarness.succeeded(h.setAvTransportUri(MUSIC))); // while playing: no Stop first
        assertTrue(RendererHarness.succeeded(h.play()));
        old.stop();
        h.closePlayer(old); // the main thread delivers the notice that it was removed late

        stayAs("the new player plays", h, "PLAYING");
    }

    @Test
    public void aRequestThatWasQueuedForAPlayerThatIsNotTheCurrentOneIsIgnoredByTheStateMachine() throws Exception {
        RendererHarness h = renderer();
        homeAssistantPlays(h, TTS);
        Player old = h.players.get(0);
        homeAssistantPlays(h, MUSIC); // the request for `old` is already queued when this happens
        Player current = h.players.get(0);
        AvTransportStateMachine machine = (AvTransportStateMachine) h.service()
                .findStateMachine(YaaccAVTransportService.getDefaultInstanceID());

        machine.playerStopped(old);                                  // replaced, not attached any more
        machine.playerStopped(RendererHarness.fakePlayer(PlayMode.NORMAL)); // never ours
        assertEquals("PLAYING", transportState(h));

        current.stop();                                              // the one that plays has stopped
        await("STOPPED", () -> transportState(h).equals("STOPPED"));
        machine.playerStopped(current);                              // in Stopped a request changes nothing
        assertEquals("STOPPED", transportState(h));
    }

    @Test
    public void aNewPlayerThatCannotBeCreatedLeavesTheTransportStopped() throws Exception {
        RendererHarness h = renderer();
        homeAssistantPlays(h, TTS);
        h.newPlayerCannotBeCreated = true;

        homeAssistantPlays(h, MUSIC);

        assertTrue(h.players.isEmpty());
        await("STOPPED: nothing plays", () -> transportState(h).equals("STOPPED"));
    }

    // --- the flow of the owner (issue 296)

    @Test
    public void announcementThenMusicLikeTheOwnersHomeAssistantFlow() throws Exception {
        RendererHarness h = renderer();
        subscribe(h);
        // repeat off -> tts.speak
        assertTrue(RendererHarness.succeeded(h.setPlayMode("NORMAL")));
        homeAssistantPlays(h, TTS);
        await("PLAYING reaches Home Assistant", () -> "PLAYING".equals(lastEventedTransportState(h)));
        Player tts = h.players.get(0);

        h.playerEnds(tts); // the 2 s announcement is over; nothing else happens on the wire

        // shuffle on -> delay -> repeat one: Home Assistant already knows that nothing plays any more
        await("STOPPED reaches Home Assistant", () -> "STOPPED".equals(lastEventedTransportState(h)));
        assertEquals("STOPPED", transportState(h));
        assertTrue(RendererHarness.succeeded(h.setPlayMode("SHUFFLE")));
        assertTrue(RendererHarness.succeeded(h.setPlayMode("REPEAT_ONE")));
        // play_media (music): Stop (answered in Stopped, no 701), SetAVTransportURI, then the check of its cached state
        assertTrue(RendererHarness.describe(h.stop()), RendererHarness.succeeded(h.stop()));
        assertTrue(RendererHarness.succeeded(h.setAvTransportUri(MUSIC)));
        assertEquals("Home Assistant does not skip Play", "STOPPED", lastEventedTransportState(h));
        assertTrue(RendererHarness.describe(h.play()), RendererHarness.succeeded(h.play()));

        assertEquals("PLAYING", transportState(h));
        await("PLAYING reaches Home Assistant", () -> "PLAYING".equals(lastEventedTransportState(h)));
        assertEquals(PlayMode.fromUpnpPlayMode("REPEAT_ONE"), h.players.get(0).getPlayMode());
        assertTrue(h.players.get(0).isPlaying());
    }

    @Test
    public void theSameFlowWithThePlayerEndingAtOnceAfterEachStart() throws Exception {
        RendererHarness h = renderer();
        for (int round = 0; round < 5; round++) {
            homeAssistantPlays(h, round % 2 == 0 ? TTS : MUSIC);
            assertEquals("round " + round, "PLAYING", transportState(h));

            h.playerEnds(h.players.get(0));

            final int current = round;
            await("STOPPED in round " + current, () -> transportState(h).equals("STOPPED"));
        }
    }

    // --- events: a state change is not paced

    @Test
    public void theStoppedEventOfAStopIsNotHeldBackWhenTheLastEventJustWentOut() throws Exception {
        RendererHarness h = renderer();
        subscribe(h);
        Thread.sleep(400); // nothing for a while: the next event goes out at once
        // Home Assistant sets the item while the transport is stopped, then Play: PLAYING goes out at once ...
        assertTrue(RendererHarness.succeeded(h.setAvTransportUri(TTS)));
        await("the PLAYING event", () -> indexOfEventWith(h, "TransportState", "PLAYING") >= 0);
        int playing = indexOfEventWith(h, "TransportState", "PLAYING");

        // ... and its Stop right behind: its STOPPED event used to wait for the 250 ms of the pacing
        assertTrue(RendererHarness.succeeded(h.stop()));

        await("the STOPPED event", () -> indexOfEventWith(h, "TransportState", "STOPPED") >= 0);
        int stopped = indexOfEventWith(h, "TransportState", "STOPPED");
        long gapMillis = TimeUnit.NANOSECONDS.toMillis(h.sentEventNanos.get(stopped) - h.sentEventNanos.get(playing));
        assertTrue("PLAYING and STOPPED " + gapMillis + " ms apart, the pacing would make it >= "
                + AvTransportServiceManager.MIN_EVENT_INTERVAL_MILLIS, gapMillis < 150);
    }

    @Test
    public void aPendingChangeOfAnotherVariableGoesOutWithTheStateChangeAtOnce() throws Exception {
        RendererHarness h = renderer();
        assertTrue(RendererHarness.succeeded(h.setAvTransportUri(TTS)));
        subscribe(h);
        Thread.sleep(400);
        h.setPlayMode("SHUFFLE");               // goes out at once
        await("SHUFFLE", () -> indexOfEventWith(h, "CurrentPlayMode", "SHUFFLE") >= 0);
        int shuffle = indexOfEventWith(h, "CurrentPlayMode", "SHUFFLE");
        h.setPlayMode("REPEAT_ONE");            // waits for the pace ...

        assertTrue(RendererHarness.succeeded(h.stop())); // ... a state change takes it along

        await("STOPPED", () -> indexOfEventWith(h, "TransportState", "STOPPED") >= 0);
        int stopped = indexOfEventWith(h, "TransportState", "STOPPED");
        assertTrue(carries(h.sentEvents.get(stopped), "CurrentPlayMode", "REPEAT_ONE"));
        long gapMillis = TimeUnit.NANOSECONDS.toMillis(h.sentEventNanos.get(stopped) - h.sentEventNanos.get(shuffle));
        assertTrue("STOPPED " + gapMillis + " ms after the last event", gapMillis < 150);
    }

    @Test
    public void changesOfOtherVariablesKeepTheirPace() throws Exception {
        RendererHarness h = renderer();
        assertTrue(RendererHarness.succeeded(h.setAvTransportUri(TTS)));
        subscribe(h);
        Thread.sleep(400);
        h.setPlayMode("SHUFFLE");
        await("SHUFFLE", () -> indexOfEventWith(h, "CurrentPlayMode", "SHUFFLE") >= 0);
        int shuffle = indexOfEventWith(h, "CurrentPlayMode", "SHUFFLE");

        h.setPlayMode("REPEAT_ALL");

        await("REPEAT_ALL", () -> indexOfEventWith(h, "CurrentPlayMode", "REPEAT_ALL") >= 0);
        int repeat = indexOfEventWith(h, "CurrentPlayMode", "REPEAT_ALL");
        long gapMillis = TimeUnit.NANOSECONDS.toMillis(h.sentEventNanos.get(repeat) - h.sentEventNanos.get(shuffle));
        assertTrue("the play mode is paced: " + gapMillis + " ms", gapMillis >= AvTransportServiceManager.MIN_EVENT_INTERVAL_MILLIS - 40);
    }

    // --- locks: the end of a player and the commands of a control point at the same time

    @Test
    public void theEndOfPlayersAndCommandsInParallelNeverBlockOrFail() throws Exception {
        RendererHarness h = renderer();
        homeAssistantPlays(h, TTS);
        subscribe(h);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> answers = new ArrayList<>();
            List<Future<?>> playerSide = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                answers.add(pool.submit(() -> h.stop().getOperation().getStatusCode()));
                answers.add(pool.submit(() -> h.setAvTransportUri(MUSIC).getOperation().getStatusCode()));
                answers.add(pool.submit(() -> h.play().getOperation().getStatusCode()));
                playerSide.add(pool.submit(() -> {
                    for (Player player : new ArrayList<>(h.players)) {
                        h.playerEnds(player);
                    }
                }));
                if (i % 7 == 0) {
                    playerSide.add(pool.submit(() -> {
                        for (Player player : new ArrayList<>(h.players)) {
                            h.closePlayer(player);
                        }
                    }));
                }
            }
            for (Future<Integer> answer : answers) {
                assertEquals(Integer.valueOf(200), answer.get(30, TimeUnit.SECONDS));
            }
            for (Future<?> done : playerSide) {
                done.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        Thread.sleep(300); // the requests that the ends and removals queued are done

        // whatever the order was, the transport is in a state the next command works from
        assertTrue(RendererHarness.succeeded(h.stop()));
        assertTrue(RendererHarness.succeeded(h.setAvTransportUri(MUSIC)));
        assertTrue(RendererHarness.succeeded(h.play()));
        assertEquals("PLAYING", transportState(h));
        stayAs("the new player plays on", h, "PLAYING");
    }
}
