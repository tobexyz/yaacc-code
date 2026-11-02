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
package de.yaacc.upnp.server.renderingcontrol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;

import org.fourthline.cling.model.ServiceManager;
import org.fourthline.cling.model.gena.CancelReason;
import org.fourthline.cling.model.message.StreamResponseMessage;
import org.fourthline.cling.model.meta.StateVariable;
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;
import org.fourthline.cling.support.lastchange.LastChange;
import org.fourthline.cling.support.model.Channel;
import org.fourthline.cling.support.renderingcontrol.AbstractAudioRenderingControl;
import org.fourthline.cling.support.renderingcontrol.lastchange.RenderingControlLastChangeParser;
import org.fourthline.cling.support.renderingcontrol.lastchange.RenderingControlVariable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.yaacc.upnp.server.ServiceHarness;
import de.yaacc.upnp.server.events.AsyncLastChangeServiceManager;

/**
 * Volume and mute of the media renderer's RenderingControl service as a control point such as Home Assistant
 * (dlna_dmr, async_upnp_client) sees them: the SOAP actions with {@code Channel=Master}, the initial event of a
 * new subscriber and the {@code LastChange} event after every change, whether it comes from the control point,
 * from the app or from outside (hardware keys), all through the real receiving path ({@link ServiceHarness}).
 * The `AudioManager` is a fake that keeps a volume index and a mute flag. Plain JVM.
 */
public class RenderingControlEventsTest {

    private static final int LOCK_TIMEOUT_MILLIS = 600;
    private static final UnsignedIntegerFourBytes ID = new UnsignedIntegerFourBytes(0);
    private static final String TYPE = "urn:schemas-upnp-org:service:RenderingControl:1";

    /** The `AudioManager` of the device: 15 steps, index 7, not muted. */
    private static final class FakeAudio {
        final AtomicInteger max = new AtomicInteger(15);
        final AtomicInteger index = new AtomicInteger(7);
        final AtomicBoolean muted = new AtomicBoolean();
        final AtomicBoolean failing = new AtomicBoolean();
        final AudioManager manager = mock(AudioManager.class);
        final Context context = mock(Context.class);

        FakeAudio() {
            when(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(manager);
            when(manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).thenAnswer(i -> max.get());
            when(manager.getStreamVolume(AudioManager.STREAM_MUSIC)).thenAnswer(i -> index.get());
            when(manager.isStreamMute(AudioManager.STREAM_MUSIC)).thenAnswer(i -> muted.get());
            doAnswer(i -> {
                failIfAsked();
                muted.set(i.<Integer>getArgument(1) == AudioManager.ADJUST_MUTE);
                return null;
            }).when(manager).adjustStreamVolume(anyInt(), anyInt(), anyInt());
            doAnswer(i -> {
                failIfAsked();
                index.set(i.<Integer>getArgument(1));
                return null;
            }).when(manager).setStreamVolume(anyInt(), anyInt(), anyInt());
        }

        private void failIfAsked() {
            if (failing.get()) {
                throw new SecurityException("not allowed");
            }
        }
    }

    /** What the system's volume broadcast does: tells that something may have changed. */
    private static final class FakeWatcher implements VolumeWatcher {
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger stops = new AtomicInteger();
        volatile Runnable onChange;

        @Override
        public void start(Runnable onChange) {
            starts.incrementAndGet();
            this.onChange = onChange;
        }

        @Override
        public void stop() {
            stops.incrementAndGet();
            onChange = null;
        }

        /** The AudioManager calls back: the volume or mute state changed outside of this app. */
        void systemVolumeChanged() {
            Runnable callback = onChange;
            assertNotNull("the system volume is not watched", callback);
            callback.run();
        }
    }

    private FakeAudio audio;
    private FakeWatcher watcher;
    private ServiceHarness<AbstractAudioRenderingControl> h;
    private final List<ServiceHarness<?>> harnesses = new ArrayList<>();

    @Before
    public void setUp() {
        audio = new FakeAudio();
        watcher = new FakeWatcher();
        h = harness();
    }

    @After
    public void tearDown() {
        for (ServiceHarness<?> harness : harnesses) {
            harness.close();
        }
    }

    private ServiceHarness<AbstractAudioRenderingControl> harness() {
        ServiceHarness<AbstractAudioRenderingControl> harness = new ServiceHarness<>(AbstractAudioRenderingControl.class, TYPE,
                "RenderingControl", service -> new RenderingControlServiceManager(service, LOCK_TIMEOUT_MILLIS,
                () -> new YaaccAudioRenderingControlService(new SystemVolume(audio.context), watcher)));
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
            Thread.sleep(10);
        }
    }

    private static String unescape(String xml) {
        return xml.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&");
    }

    /** The `LastChange` XML an event carries, as a control point reads it. */
    private static String lastChangeOf(String event) {
        Matcher matcher = Pattern.compile("<LastChange>(.*)</LastChange>", Pattern.DOTALL).matcher(event);
        assertTrue("a LastChange in " + event, matcher.find());
        return unescape(matcher.group(1));
    }

    /** True if the event has the element {`<variable channel="Master" val="value"/>`} (attribute order does not matter). */
    private static boolean carries(String event, String variable, String value) {
        Matcher element = Pattern.compile("<" + variable + " ([^>]*)/>").matcher(unescape(event));
        while (element.find()) {
            if (element.group(1).contains("channel=\"Master\"") && element.group(1).contains("val=\"" + value + "\"")) {
                return true;
            }
        }
        return false;
    }

    private static boolean has(String event, String variable) {
        return unescape(event).contains("<" + variable + " ");
    }

    private boolean anyCarries(String variable, String value) {
        for (String event : h.sentEvents) {
            if (carries(event, variable, value)) {
                return true;
            }
        }
        return false;
    }

    private static String element(StreamResponseMessage answer, String name) {
        String body = answer.getBodyString();
        int from = body.indexOf("<" + name + ">");
        assertTrue(name + " in " + body, from >= 0);
        return body.substring(from + name.length() + 2, body.indexOf("</" + name + ">"));
    }

    private StreamResponseMessage setMute(boolean mute) {
        return h.soap("SetMute", "InstanceID", "0", "Channel", "Master", "DesiredMute", mute ? "1" : "0");
    }

    private StreamResponseMessage setVolume(int volume) {
        return h.soap("SetVolume", "InstanceID", "0", "Channel", "Master", "DesiredVolume", String.valueOf(volume));
    }

    private StreamResponseMessage getVolume() {
        return h.soap("GetVolume", "InstanceID", "0", "Channel", "Master");
    }

    private StreamResponseMessage getMute() {
        return h.soap("GetMute", "InstanceID", "0", "Channel", "Master");
    }

    private void subscribeAndAwaitInitialEvent() throws Exception {
        int before = h.sentEvents.size();
        assertEquals(200, h.subscribeExchange());
        await("the initial event", () -> h.sentEvents.size() > before);
    }

    // --- the service description

    @Test
    public void lastChangeIsTheEventedVariableAndVolumeAndMuteAreNotEventedOnTheirOwn() {
        StateVariable<?> lastChange = h.localService.getStateVariable("LastChange");
        assertTrue(lastChange.getEventDetails().isSendEvents());
        assertEquals("a dropped event could not be sent again", 0, lastChange.getEventDetails().getEventMaximumRateMilliseconds());
        for (String name : new String[]{"Volume", "Mute", "Loudness", "VolumeDB", "PresetNameList"}) {
            assertFalse(name, h.localService.getStateVariable(name).getEventDetails().isSendEvents());
        }
    }

    @Test
    public void volumeIsAPercentageAndChannelAllowsMaster() {
        StateVariable<?> volume = h.localService.getStateVariable("Volume");
        assertEquals(0, volume.getTypeDetails().getAllowedValueRange().getMinimum());
        assertEquals(100, volume.getTypeDetails().getAllowedValueRange().getMaximum());
        assertTrue(Arrays.asList(h.localService.getStateVariable("A_ARG_TYPE_Channel").getTypeDetails().getAllowedValues())
                .contains("Master"));
    }

    // --- the actions with Channel=Master

    @Test
    public void getVolumeAndGetMuteAnswerForTheMasterChannel() {
        StreamResponseMessage volume = getVolume();
        StreamResponseMessage mute = getMute();

        assertTrue(ServiceHarness.describe(volume), ServiceHarness.succeeded(volume));
        assertEquals("7 of 15 steps", "47", element(volume, "CurrentVolume"));
        assertTrue(ServiceHarness.describe(mute), ServiceHarness.succeeded(mute));
        assertEquals("0", element(mute, "CurrentMute"));
    }

    @Test
    public void setMuteAndSetVolumeReachTheAudioManagerAndReadBack() {
        assertTrue(ServiceHarness.succeeded(setMute(true)));
        assertTrue(audio.muted.get());
        assertEquals("1", element(getMute(), "CurrentMute"));
        assertTrue(ServiceHarness.succeeded(setMute(false)));
        assertFalse(audio.muted.get());

        assertTrue(ServiceHarness.succeeded(setVolume(40)));
        assertEquals(6, audio.index.get());
        assertEquals("40", element(getVolume(), "CurrentVolume"));
        assertTrue(ServiceHarness.succeeded(setVolume(100)));
        assertEquals(15, audio.index.get());
        assertTrue(ServiceHarness.succeeded(setVolume(0)));
        assertEquals(0, audio.index.get());
    }

    @Test
    public void volumeOutsideOfTheRangeIsCutAndNoStreamRangeDoesNotDivideByZero() {
        assertTrue(ServiceHarness.succeeded(setVolume(250)));
        assertEquals(15, audio.index.get());

        audio.max.set(0);
        StreamResponseMessage volume = getVolume();

        assertTrue(ServiceHarness.describe(volume), ServiceHarness.succeeded(volume));
        assertEquals("0", element(volume, "CurrentVolume"));
    }

    @Test
    public void anAudioManagerThatRefusesIsAnUpnpErrorNotACrash() {
        audio.failing.set(true);

        StreamResponseMessage answer = setMute(true);

        assertFalse(ServiceHarness.describe(answer), ServiceHarness.succeeded(answer));
        assertEquals(500, answer.getOperation().getStatusCode());
        assertTrue(answer.getBodyString(), answer.getBodyString().contains("<errorCode>501</errorCode>"));
        assertTrue(ServiceHarness.succeeded(getVolume()));
    }

    // --- the initial event

    @Test
    public void theInitialEventHasVolumeAndMuteOfTheMasterChannel() throws Exception {
        audio.muted.set(true);

        subscribeAndAwaitInitialEvent();

        String initial = h.sentEvents.get(0);
        assertTrue(initial, carries(initial, "Mute", "1"));
        assertTrue(initial, carries(initial, "Volume", "47"));
        // and a control point parses it back
        LastChange received = new LastChange(new RenderingControlLastChangeParser(), lastChangeOf(initial));
        assertEquals(Channel.Master, received.getEventedValue(ID, RenderingControlVariable.Mute.class).getValue().getChannel());
        assertTrue(received.getEventedValue(ID, RenderingControlVariable.Mute.class).getValue().getMute());
        assertEquals(47, (int) received.getEventedValue(ID, RenderingControlVariable.Volume.class).getValue().getVolume());
        assertTrue(lastChangeOf(initial), lastChangeOf(initial).contains("<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/RCS/\""));
        assertTrue(lastChangeOf(initial), lastChangeOf(initial).contains("<InstanceID val=\"0\">"));
    }

    @Test
    public void theInitialEventDoesNotNeedAnythingToHaveHappenedBefore() throws Exception {
        // a subscriber that comes before the first action: the service instance does not exist yet
        subscribeAndAwaitInitialEvent();

        assertTrue(h.sentEvents.get(0), has(h.sentEvents.get(0), "Mute"));
        assertEquals(1, h.sentEvents.size());
    }

    // --- events after a change

    @Test
    public void setMuteFromTheControlPointIsEvented() throws Exception {
        subscribeAndAwaitInitialEvent();

        assertTrue(ServiceHarness.succeeded(setMute(true)));

        await("the Mute event", () -> anyCarries("Mute", "1"));
        String event = h.sentEvents.get(h.sentEvents.size() - 1);
        assertFalse("only what changed: " + event, has(event, "Volume"));
    }

    @Test
    public void setVolumeFromTheControlPointIsEvented() throws Exception {
        subscribeAndAwaitInitialEvent();

        assertTrue(ServiceHarness.succeeded(setVolume(40)));

        await("the Volume event", () -> anyCarries("Volume", "40"));
        String event = h.sentEvents.get(h.sentEvents.size() - 1);
        assertFalse("only what changed: " + event, has(event, "Mute"));
    }

    @Test
    public void aChangeMadeInTheAppIsEvented() throws Exception {
        subscribeAndAwaitInitialEvent();
        SystemVolume appSide = new SystemVolume(audio.context); // what UpnpClient.setVolume / setMute (the app's controls) call

        appSide.setPercent(60);
        await("the Volume event", () -> anyCarries("Volume", "60"));
        appSide.setMuted(true);
        await("the Mute event", () -> anyCarries("Mute", "1"));
    }

    @Test
    public void aChangeFromOutsideLikeTheHardwareKeysIsEvented() throws Exception {
        subscribeAndAwaitInitialEvent();

        audio.index.set(3); // volume key; the system tells it with its volume broadcast
        watcher.systemVolumeChanged();
        await("the Volume event", () -> anyCarries("Volume", "20"));

        audio.muted.set(true);
        watcher.systemVolumeChanged();
        await("the Mute event", () -> anyCarries("Mute", "1"));
    }

    @Test
    public void whatChangedWhileNobodyWasSubscribedIsInTheInitialEventNotAnEventOfItsOwn() throws Exception {
        audio.index.set(3); // before the subscription

        subscribeAndAwaitInitialEvent();
        watcher.systemVolumeChanged();
        Thread.sleep(AsyncLastChangeServiceManager.MIN_EVENT_INTERVAL_MILLIS + 150);

        assertTrue(carries(h.sentEvents.get(0), "Volume", "20"));
        assertEquals("nothing changed since: " + h.sentEvents, 1, h.sentEvents.size());
    }

    @Test
    public void noEventWhenNothingChanged() throws Exception {
        subscribeAndAwaitInitialEvent();

        watcher.systemVolumeChanged();
        watcher.systemVolumeChanged();
        assertTrue(ServiceHarness.succeeded(setMute(false))); // already unmuted
        assertTrue(ServiceHarness.succeeded(setVolume(47)));  // already 7 of 15
        Thread.sleep(AsyncLastChangeServiceManager.MIN_EVENT_INTERVAL_MILLIS + 150);

        assertEquals(h.sentEvents.toString(), 1, h.sentEvents.size());
    }

    @Test
    public void quickChangesInARowAreSentTogetherAndTheLastOneWins() throws Exception {
        subscribeAndAwaitInitialEvent();

        setVolume(20);
        setVolume(60);
        setVolume(80);

        await("the last volume", () -> anyCarries("Volume", "80"));
        Thread.sleep(AsyncLastChangeServiceManager.MIN_EVENT_INTERVAL_MILLIS + 150);
        String last = h.sentEvents.get(h.sentEvents.size() - 1);
        assertTrue("the last event has the last volume: " + last, carries(last, "Volume", "80"));
        // the first change may go out alone (nothing was sent for a while), the ones right behind it wait for the pace
        assertTrue("at most initial + the first + the rest: " + h.sentEvents, h.sentEvents.size() <= 3);
    }

    // --- the system volume is only watched while somebody listens

    @Test
    public void theSystemVolumeIsWatchedFromTheFirstSubscriberToTheLastOne() throws Exception {
        assertEquals(0, watcher.starts.get());
        assertTrue("no subscriber, no event, no failure", ServiceHarness.succeeded(setVolume(40)));
        assertEquals(0, watcher.starts.get());

        subscribeAndAwaitInitialEvent();
        assertEquals(1, watcher.starts.get());
        subscribeAndAwaitInitialEvent();
        assertEquals("a second subscriber does not start it again", 1, watcher.starts.get());

        h.subscriptions.get(0).end(CancelReason.EXPIRED);
        assertEquals("one subscriber is left", 0, watcher.stops.get());
        h.subscriptions.get(1).end(CancelReason.EXPIRED);
        assertEquals(1, watcher.stops.get());
        assertEquals(1, watcher.starts.get());
    }

    @Test
    public void aSubscriberThatCameAgainWatchesAgain() throws Exception {
        subscribeAndAwaitInitialEvent();
        h.subscriptions.get(0).end(CancelReason.EXPIRED);
        assertEquals(1, watcher.stops.get());

        subscribeAndAwaitInitialEvent();

        assertEquals(2, watcher.starts.get());
        audio.index.set(3);
        watcher.systemVolumeChanged();
        await("the Volume event", () -> anyCarries("Volume", "20"));
    }

    @Test
    public void aChangeAfterTheLastSubscriberLeftIsNoEvent() throws Exception {
        subscribeAndAwaitInitialEvent();
        h.subscriptions.get(0).end(CancelReason.EXPIRED);
        int events = h.sentEvents.size();

        assertTrue(ServiceHarness.succeeded(setVolume(80)));
        new SystemVolume(audio.context).setPercent(20);
        Thread.sleep(AsyncLastChangeServiceManager.MIN_EVENT_INTERVAL_MILLIS + 150);

        assertEquals(events, h.sentEvents.size());
    }

    @Test
    public void aBroadcastWatcherThatCannotStartDoesNotBreakTheSubscription() throws Exception {
        VolumeWatcher broken = new VolumeWatcher() {
            @Override
            public void start(Runnable onChange) {
                throw new SecurityException("receiver not allowed");
            }

            @Override
            public void stop() {
                throw new IllegalStateException("never started");
            }
        };
        ServiceHarness<AbstractAudioRenderingControl> other = new ServiceHarness<>(AbstractAudioRenderingControl.class, TYPE,
                "RenderingControl", service -> new RenderingControlServiceManager(service, LOCK_TIMEOUT_MILLIS,
                () -> new YaaccAudioRenderingControlService(new SystemVolume(audio.context), broken)));
        harnesses.add(other);

        assertEquals(200, other.subscribeExchange());
        await("the initial event", () -> other.sentEvents.size() == 1);
        assertTrue(ServiceHarness.succeeded(other.soap("SetMute", "InstanceID", "0", "Channel", "Master", "DesiredMute", "1")));

        // the changes made through this app are still seen
        await("the Mute event", () -> other.sentEvents.size() == 2);
        other.subscriptions.get(0).end(CancelReason.EXPIRED);
    }

    // --- events never hold up a command, a bad subscriber hurts nobody

    @Test
    public void aCommandDuringAnEventThatIsBeingSentIsNotHeldUp() throws Exception {
        subscribeAndAwaitInitialEvent();
        CountDownLatch inEvent = new CountDownLatch(1);
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
        try {
            setMute(true); // fires an event, and a subscriber does not come back
            assertTrue("the event is being sent", inEvent.await(5, TimeUnit.SECONDS));

            long start = System.nanoTime();
            StreamResponseMessage answer = setVolume(40);
            StreamResponseMessage read = getVolume();
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertTrue(ServiceHarness.describe(answer), ServiceHarness.succeeded(answer));
            assertTrue(ServiceHarness.describe(read), ServiceHarness.succeeded(read));
            assertEquals("40", element(read, "CurrentVolume"));
            assertTrue("answered in " + millis + " ms", millis < LOCK_TIMEOUT_MILLIS / 2);
        } finally {
            release.countDown();
        }
    }

    @Test
    public void aSubscriberThatThrowsDoesNotHurtTheCommandOrTheOtherSubscribers() throws Exception {
        h.localService.getManager().getPropertyChangeSupport().addPropertyChangeListener(event -> {
            if (ServiceManager.EVENTED_STATE_VARIABLES.equals(event.getPropertyName())) {
                throw new IllegalStateException("subscriber is gone");
            }
        });
        subscribeAndAwaitInitialEvent();

        assertTrue(ServiceHarness.succeeded(setMute(true)));

        await("the event reaches the other subscriber", () -> anyCarries("Mute", "1"));
    }

    // --- the system's broadcast

    @Test
    public void onlyTheMusicStreamOfTheSystemBroadcastCounts() {
        Intent music = mock(Intent.class);
        when(music.getIntExtra(SystemVolumeBroadcastWatcher.EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC))
                .thenReturn(AudioManager.STREAM_MUSIC);
        Intent ring = mock(Intent.class);
        when(ring.getIntExtra(SystemVolumeBroadcastWatcher.EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC))
                .thenReturn(AudioManager.STREAM_RING);

        assertTrue(SystemVolumeBroadcastWatcher.streamIsMusic(music));
        assertFalse(SystemVolumeBroadcastWatcher.streamIsMusic(ring));
    }

    // the renderer has one master volume: a control point that asks for another channel or instance gets that one
    @Test
    public void everyChannelNameAndInstanceAnswersWithTheOneMasterVolume() {
        for (String channel : new String[]{"Master", "LF", "RF"}) {
            StreamResponseMessage answer = h.soap("GetVolume", "InstanceID", "0", "Channel", channel);
            assertTrue(channel + ": " + ServiceHarness.describe(answer), ServiceHarness.succeeded(answer));
            assertEquals(channel, "47", element(answer, "CurrentVolume"));
        }
    }
}
