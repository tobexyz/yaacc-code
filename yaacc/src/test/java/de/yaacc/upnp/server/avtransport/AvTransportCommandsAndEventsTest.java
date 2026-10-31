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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.fourthline.cling.model.DefaultServiceManager;
import org.fourthline.cling.model.ServiceManager;
import org.fourthline.cling.model.gena.LocalGENASubscription;
import org.fourthline.cling.model.message.StreamResponseMessage;
import org.junit.After;
import org.junit.Test;

import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import de.yaacc.player.PlayMode;
import de.yaacc.player.Player;

/**
 * Commands (SOAP actions) of a control point such as Home Assistant and the events (GENA) of the media renderer's
 * AVTransport service, run through the real receiving path of the app ({@link RendererHarness}): a command must
 * reach the player whatever the events do, and the events must reach a subscriber. Plain JVM.
 */
public class AvTransportCommandsAndEventsTest {

    /** Short, so a command that is blocked by an event fails within the test time. */
    private static final int LOCK_TIMEOUT_MILLIS = 600;
    private static final String[] ALL_MODES = {"NORMAL", "SHUFFLE", "REPEAT_ONE", "REPEAT_ALL", "RANDOM", "DIRECT_1", "INTRO"};

    private final List<RendererHarness> harnesses = new ArrayList<>();

    @After
    public void tearDown() {
        for (RendererHarness harness : harnesses) {
            harness.close();
        }
    }

    private RendererHarness plain() {
        return remember(new RendererHarness(service -> new DefaultServiceManager<YaaccAVTransportService>(service, null) {
            @Override
            protected int getLockTimeoutMillis() {
                return LOCK_TIMEOUT_MILLIS;
            }

            @Override
            protected YaaccAVTransportService createServiceInstance() {
                return new YaaccAVTransportService();
            }
        }));
    }

    private RendererHarness events() {
        return remember(new RendererHarness(service -> new AvTransportServiceManager(service, LOCK_TIMEOUT_MILLIS)));
    }

    private RendererHarness remember(RendererHarness harness) {
        harnesses.add(harness);
        return harness;
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("not within 5 s: " + what);
            }
            Thread.sleep(10);
        }
    }

    private static String unescape(String xml) {
        return xml.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&");
    }

    private static boolean carries(String event, String variable, String value) {
        return unescape(event).contains("<" + variable + " val=\"" + value + "\"/>");
    }

    private static boolean anyCarries(List<String> events, String variable, String value) {
        for (String event : events) {
            if (carries(event, variable, value)) {
                return true;
            }
        }
        return false;
    }

    private static PlayMode expected(String upnpMode) {
        return PlayMode.fromUpnpPlayMode(upnpMode);
    }

    // --- the state to be in

    private static final String[] STATES = {"NO_MEDIA_PRESENT", "STOPPED", "PLAYING", "PAUSED_PLAYBACK"};

    private static void bringTo(RendererHarness h, String state) throws Exception {
        switch (state) {
            case "NO_MEDIA_PRESENT":
                break;
            case "PLAYING":
                assertTrue(RendererHarness.succeeded(h.setAvTransportUri("http://192.0.2.1/a.mp3")));
                break;
            case "STOPPED":
                assertTrue(RendererHarness.succeeded(h.setAvTransportUri("http://192.0.2.1/a.mp3")));
                assertTrue(RendererHarness.succeeded(h.stop()));
                break;
            case "PAUSED_PLAYBACK":
                assertTrue(RendererHarness.succeeded(h.setAvTransportUri("http://192.0.2.1/a.mp3")));
                assertTrue(RendererHarness.succeeded(h.pause()));
                break;
            default:
                throw new IllegalArgumentException(state);
        }
        assertEquals(state, transportState(h));
    }

    private static String transportState(RendererHarness h) {
        String body = h.soap("GetTransportInfo", "InstanceID", "0").getBodyString();
        int from = body.indexOf("<CurrentTransportState>") + "<CurrentTransportState>".length();
        return body.substring(from, body.indexOf("</CurrentTransportState>"));
    }

    private static String reportedPlayMode(RendererHarness h) {
        String body = h.soap("GetTransportSettings", "InstanceID", "0").getBodyString();
        int from = body.indexOf("<PlayMode>") + "<PlayMode>".length();
        return body.substring(from, body.indexOf("</PlayMode>"));
    }

    // --- 1. SetPlayMode is a valid SOAP answer, reaches the player and is no state transition, in every state

    @Test
    public void setPlayModeIsAnsweredAndAppliedForEveryModeInEveryState() throws Exception {
        for (String state : STATES) {
            for (String mode : ALL_MODES) {
                RendererHarness h = events();
                bringTo(h, state);
                Player player = h.players.isEmpty() ? null : h.players.get(0);

                StreamResponseMessage answer = h.setPlayMode(mode);

                String where = mode + " in " + state + ": " + RendererHarness.describe(answer);
                assertTrue(where, RendererHarness.succeeded(answer));
                assertTrue(where, answer.getBodyString().contains("<u:SetPlayModeResponse xmlns:u=\"" + RendererHarness.SERVICE_TYPE + "\""));
                assertFalse(where, answer.getBodyString().contains("Fault"));
                assertEquals(where, state, transportState(h));
                assertEquals(where, mode, reportedPlayMode(h));
                if (player != null) {
                    assertEquals(where, expected(mode), player.getPlayMode());
                }
            }
        }
    }

    @Test
    public void setPlayModeWorksThroughTheHttpExchangeToo() throws Exception {
        RendererHarness h = events();
        bringTo(h, "PLAYING");

        int status = h.soapExchange("SetPlayMode", "InstanceID", "0", "NewPlayMode", "REPEAT_ONE");

        assertEquals(200, status);
        assertEquals(expected("REPEAT_ONE"), h.players.get(0).getPlayMode());
    }

    @Test
    public void setPlayModeReachesThePlayerThisTransportStartedEvenWhenTheLookupByKindFindsNone() throws Exception {
        RendererHarness h = events();
        bringTo(h, "PLAYING");
        Player started = h.players.get(0);
        h.lookupByKindFindsNothing = true; // the mime type of the track maps to another kind of player

        assertTrue(RendererHarness.succeeded(h.setPlayMode("REPEAT_ONE")));

        assertEquals(expected("REPEAT_ONE"), started.getPlayMode());
    }

    @Test
    public void anUnknownModeIsAnUpnpError712AndKeepsTheMode() throws Exception {
        RendererHarness h = events();
        bringTo(h, "PLAYING");
        h.setPlayMode("SHUFFLE");

        StreamResponseMessage answer = h.setPlayMode("BACKWARDS");

        assertEquals(500, answer.getOperation().getStatusCode());
        assertTrue(answer.getBodyString(), answer.getBodyString().contains("<errorCode>712</errorCode>"));
        assertEquals("SHUFFLE", reportedPlayMode(h));
    }

    @Test
    public void modeNamesInLowerCaseWithSpacesAreForgiven() throws Exception {
        RendererHarness h = events();
        bringTo(h, "PLAYING");

        assertTrue(RendererHarness.succeeded(h.setPlayMode(" repeat_all ")));

        assertEquals("REPEAT_ALL", reportedPlayMode(h));
    }

    // --- 2. what Home Assistant sees on the wire does not depend on the event manager

    private static List<String> homeAssistantSession(RendererHarness h) throws Exception {
        List<String> out = new ArrayList<>();
        String[][] session = {
                {"GetTransportInfo", "InstanceID", "0"}, {"GetTransportSettings", "InstanceID", "0"},
                {"GetMediaInfo", "InstanceID", "0"}, {"GetPositionInfo", "InstanceID", "0"},
                {"GetCurrentTransportActions", "InstanceID", "0"}, {"GetDeviceCapabilities", "InstanceID", "0"},
                {"Stop", "InstanceID", "0"}, {"SetPlayMode", "InstanceID", "0", "NewPlayMode", "SHUFFLE"},
                {"SetAVTransportURI", "InstanceID", "0", "CurrentURI", "http://192.0.2.1/a.mp3", "CurrentURIMetaData", ""},
                {"Play", "InstanceID", "0", "Speed", "1"}, {"GetTransportInfo", "InstanceID", "0"},
                {"SetPlayMode", "InstanceID", "0", "NewPlayMode", "REPEAT_ALL"}, {"GetTransportSettings", "InstanceID", "0"},
                {"Pause", "InstanceID", "0"}, {"SetPlayMode", "InstanceID", "0", "NewPlayMode", "NORMAL"},
                {"Play", "InstanceID", "0", "Speed", "1"}, {"Stop", "InstanceID", "0"},
                {"SetPlayMode", "InstanceID", "0", "NewPlayMode", "RANDOM"}, {"GetTransportSettings", "InstanceID", "0"},
        };
        for (String[] step : session) {
            String[] arguments = Arrays.copyOfRange(step, 1, step.length);
            out.add(step[0] + " " + RendererHarness.describe(h.soap(step[0], arguments)));
            Thread.sleep(30); // the event thread gets its turn between the commands
        }
        out.add("player " + (h.players.isEmpty() ? "none" : h.players.get(0).getPlayMode()));
        return out;
    }

    @Test
    public void everyAnswerAndThePlayerEffectAreTheSameWithAndWithoutTheEventManager() throws Exception {
        List<String> withoutEvents = homeAssistantSession(plain());
        List<String> withEvents = homeAssistantSession(events());

        assertEquals(withoutEvents, withEvents);
    }

    // --- 3. the subscriber (Home Assistant subscribes when its entity is set up)

    @Test
    public void aSubscriberIsAnsweredAndGetsTheFullStateAsInitialEvent() throws Exception {
        RendererHarness h = events();
        h.setPlayMode("REPEAT_ALL");

        int status = h.subscribeExchange();

        assertEquals(200, status);
        await("the initial event", () -> !h.sentEvents.isEmpty());
        String initial = unescape(h.sentEvents.get(0));
        assertTrue(initial, carries(initial, "CurrentPlayMode", "REPEAT_ALL"));
        for (String variable : new String[]{"TransportState", "TransportStatus", "CurrentTransportActions", "AVTransportURI", "NumberOfTracks"}) {
            assertTrue(variable + " in " + initial, initial.contains("<" + variable + " val="));
        }
    }

    @Test
    public void aSubscriberGetsAnEventForEveryChange() throws Exception {
        RendererHarness h = events();
        bringTo(h, "PLAYING");
        assertEquals(200, h.subscribeExchange());
        await("the initial event", () -> h.sentEvents.size() == 1);

        h.setPlayMode("SHUFFLE");
        await("SHUFFLE", () -> anyCarries(h.sentEvents, "CurrentPlayMode", "SHUFFLE"));
        h.pause();
        await("PAUSED_PLAYBACK", () -> anyCarries(h.sentEvents, "TransportState", "PAUSED_PLAYBACK"));
        h.setPlayMode("REPEAT_ONE");
        await("REPEAT_ONE", () -> anyCarries(h.sentEvents, "CurrentPlayMode", "REPEAT_ONE"));
    }

    @Test
    public void aChangeAtThePlayerReachesTheSubscriber() throws Exception {
        RendererHarness h = events();
        bringTo(h, "PLAYING");
        assertEquals(200, h.subscribeExchange());
        await("the initial event", () -> h.sentEvents.size() == 1);

        // the repeat button at the player screen
        h.players.get(0).setPlayMode(PlayMode.of(PlayMode.RepeatMode.ONE, false));

        await("REPEAT_ONE", () -> anyCarries(h.sentEvents, "CurrentPlayMode", "REPEAT_ONE"));
    }

    @Test
    public void noInitialEventWhenTheAnswerToTheSubscriberDidNotArrive() throws Exception {
        RendererHarness h = events();
        RendererHarness.Exchange subscribe = h.startSubscribe();

        subscribe.complete(true);
        h.setPlayMode("SHUFFLE");

        Thread.sleep(700);
        assertTrue("nothing is sent to a subscriber that never got its answer: " + h.sentEvents, h.sentEvents.isEmpty());
    }

    @Test
    public void aSubscriberThatComesWhileSetPlayModeRunsMissesNoChange() throws Exception {
        // Home Assistant: shuffle, then repeat, as two calls in a row - and its SUBSCRIBE is in the middle
        RendererHarness h = events();
        bringTo(h, "PLAYING");
        RendererHarness.Exchange subscribe = h.startSubscribe(); // state read, answer not yet written

        h.setPlayMode("SHUFFLE");
        h.setPlayMode("REPEAT_ALL");
        subscribe.complete(false);

        await("the last mode reached the subscriber", () -> anyCarries(h.sentEvents, "CurrentPlayMode", "REPEAT_ALL"));
        assertEquals("REPEAT_ALL", reportedPlayMode(h));
        assertEquals(expected("REPEAT_ALL"), h.players.get(0).getPlayMode());
    }

    @Test
    public void shuffleThenRepeatInQuickSuccessionBothReachThePlayerAndTheSubscriber() throws Exception {
        RendererHarness h = events();
        bringTo(h, "PLAYING");
        assertEquals(200, h.subscribeExchange());
        await("the initial event", () -> h.sentEvents.size() == 1);

        for (int round = 0; round < 5; round++) {
            assertTrue(RendererHarness.succeeded(h.setPlayMode("SHUFFLE")));
            assertTrue(RendererHarness.succeeded(h.setPlayMode("RANDOM")));
            assertEquals(expected("RANDOM"), h.players.get(0).getPlayMode());
            assertTrue(RendererHarness.succeeded(h.setPlayMode("NORMAL")));
            assertEquals(expected("NORMAL"), h.players.get(0).getPlayMode());
        }
        await("the last change", () -> carries(h.sentEvents.get(h.sentEvents.size() - 1), "CurrentPlayMode", "NORMAL"));
    }

    @Test
    public void theHandlerKeepsTheProtocolOfEachRequestApartWhenRequestsOverlap() throws Exception {
        RendererHarness h = events();
        RendererHarness.Exchange subscribe = h.startSubscribe();

        // another request is handled completely while the subscriber's answer is still to be written
        assertEquals(200, h.soapExchange("GetTransportInfo", "InstanceID", "0"));
        subscribe.complete(false);

        await("the initial event", () -> !h.sentEvents.isEmpty());
    }

    // --- 4. commands are never held up by an event

    /** A subscriber that does not return from the event: a slow network stack, a lock inside a listener. */
    private static CountDownLatch blockEveryEventUntilReleased(RendererHarness h, CountDownLatch inEvent) {
        CountDownLatch release = new CountDownLatch(1);
        h.localService.getManager().getPropertyChangeSupport().addPropertyChangeListener(
                (PropertyChangeListener) event -> {
                    if (ServiceManager.EVENTED_STATE_VARIABLES.equals(event.getPropertyName())) {
                        inEvent.countDown();
                        try {
                            release.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
        return release;
    }

    @Test
    public void aCommandDuringAnEventThatIsBeingSentIsNotHeldUp() throws Exception {
        RendererHarness h = events();
        bringTo(h, "PLAYING");
        CountDownLatch inEvent = new CountDownLatch(1);
        CountDownLatch release = blockEveryEventUntilReleased(h, inEvent);
        try {
            h.setPlayMode("SHUFFLE"); // fires an event, and the subscriber does not come back
            assertTrue("the event is being sent", inEvent.await(5, TimeUnit.SECONDS));

            long start = System.nanoTime();
            StreamResponseMessage answer = h.setPlayMode("REPEAT_ALL");
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertTrue(RendererHarness.describe(answer), RendererHarness.succeeded(answer));
            assertTrue("answered in " + millis + " ms", millis < LOCK_TIMEOUT_MILLIS / 2);
            assertEquals(expected("REPEAT_ALL"), h.players.get(0).getPlayMode());
            assertEquals("REPEAT_ALL", reportedPlayMode(h));
        } finally {
            release.countDown();
        }
    }

    @Test
    public void everyCommandStillWorksWhileTheSubscriberIsStuck() throws Exception {
        RendererHarness h = events();
        CountDownLatch inEvent = new CountDownLatch(1);
        CountDownLatch release = blockEveryEventUntilReleased(h, inEvent);
        try {
            h.setAvTransportUri("http://192.0.2.1/a.mp3");
            assertTrue("the event is being sent", inEvent.await(5, TimeUnit.SECONDS));

            assertTrue(RendererHarness.succeeded(h.play()));
            assertTrue(RendererHarness.succeeded(h.setPlayMode("SHUFFLE")));
            assertTrue(RendererHarness.succeeded(h.pause()));
            assertTrue(RendererHarness.succeeded(h.stop()));
            assertTrue(RendererHarness.succeeded(h.soap("GetTransportInfo", "InstanceID", "0")));
        } finally {
            release.countDown();
        }
    }

    @Test
    public void aSubscriberThatThrowsDoesNotHurtTheCommandOrTheOtherSubscribers() throws Exception {
        RendererHarness h = events();
        bringTo(h, "PLAYING");
        h.localService.getManager().getPropertyChangeSupport().addPropertyChangeListener(event -> {
            if (ServiceManager.EVENTED_STATE_VARIABLES.equals(event.getPropertyName())) {
                throw new IllegalStateException("subscriber is gone");
            }
        });
        assertEquals(200, h.subscribeExchange());
        await("the initial event", () -> h.sentEvents.size() == 1);

        assertTrue(RendererHarness.succeeded(h.setPlayMode("SHUFFLE")));

        await("the event reaches the other subscriber", () -> anyCarries(h.sentEvents, "CurrentPlayMode", "SHUFFLE"));
    }

    /**
     * A subscription's methods are {@code synchronized} and some of them ({@code registerOnService}, {@code end})
     * call the service manager and so need its lock; the event thread used to hold that lock while it delivered
     * to the subscription, which needs the same monitor: each waiting for the other until the lock timed out, with
     * every command queued behind.
     */
    @Test
    public void aSubscriptionThatIsBusyDoesNotStallTheCommandsBehindTheEventThread() throws Exception {
        RendererHarness h = events();
        bringTo(h, "PLAYING");
        assertEquals(200, h.subscribeExchange());
        await("the initial event", () -> h.sentEvents.size() == 1);
        LocalGENASubscription subscription = h.subscriptions.get(0);
        CountDownLatch holding = new CountDownLatch(1);
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            Future<?> busy = other.submit(() -> {
                synchronized (subscription) { // e.g. end() / registerOnService() on their way to the manager lock
                    holding.countDown();
                    try {
                        Thread.sleep(LOCK_TIMEOUT_MILLIS + 300);
                        h.localService.getManager().getPropertyChangeSupport();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (RuntimeException e) {
                        // the manager lock was not free in time: only the deadlock gives this
                    }
                }
            });
            assertTrue(holding.await(5, TimeUnit.SECONDS));

            h.setPlayMode("SHUFFLE"); // an event is due
            Thread.sleep(AvTransportServiceManager.MIN_EVENT_INTERVAL_MILLIS + 150);
            StreamResponseMessage answer = h.setPlayMode("REPEAT_ALL");

            assertTrue(RendererHarness.describe(answer), RendererHarness.succeeded(answer));
            assertEquals(expected("REPEAT_ALL"), h.players.get(0).getPlayMode());
            busy.get(10, TimeUnit.SECONDS);
        } finally {
            other.shutdownNow();
        }
    }

    @Test
    public void parallelCommandsAndChangesAtThePlayerLoseNothing() throws Exception {
        RendererHarness h = events();
        bringTo(h, "PLAYING");
        assertEquals(200, h.subscribeExchange());
        await("the initial event", () -> h.sentEvents.size() == 1);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<Integer>> answers = new ArrayList<>();
            for (int i = 0; i < 150; i++) {
                final String mode = ALL_MODES[i % ALL_MODES.length];
                answers.add(pool.submit(() -> h.soap("SetPlayMode", "InstanceID", "0", "NewPlayMode", mode).getOperation().getStatusCode()));
                if (i % 10 == 0) {
                    pool.submit(() -> h.players.get(0).setPlayMode(PlayMode.of(PlayMode.RepeatMode.ONE, true)));
                }
            }
            for (Future<Integer> answer : answers) {
                assertEquals(Integer.valueOf(200), answer.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        h.setPlayMode("SHUFFLE");
        assertEquals(expected("SHUFFLE"), h.players.get(0).getPlayMode());
        await("the last change", () -> carries(h.sentEvents.get(h.sentEvents.size() - 1), "CurrentPlayMode", "SHUFFLE"));
        assertNotNull(h.sentEvents);
    }
}
