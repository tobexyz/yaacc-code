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
package de.yaacc.upnp.server.openhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.fourthline.cling.model.message.StreamResponseMessage;
import org.fourthline.cling.model.meta.LocalService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.yaacc.openhome.OhIds;
import de.yaacc.openhome.OhQueue;
import de.yaacc.openhome.OhTransportState;
import de.yaacc.upnp.server.ServiceHarness;

/**
 * The OpenHome services of YAACC's renderer as a control point sees them: SOAP actions and events through the real receiving path
 * (service harness), against a fake player. Plain JVM.
 */
public class OhRendererCoreTest {

    /** Records what the services ask of the player. */
    private static final class FakePlayback implements OhPlayback {
        final List<String> calls = new ArrayList<>();

        @Override
        public void play() {
            calls.add("play");
        }

        @Override
        public void pause() {
            calls.add("pause");
        }

        @Override
        public void stop() {
            calls.add("stop");
        }

        @Override
        public void next() {
            calls.add("next");
        }

        @Override
        public void previous() {
            calls.add("previous");
        }

        @Override
        public void setRepeat(boolean repeat) {
            calls.add("repeat " + repeat);
        }

        @Override
        public void setShuffle(boolean shuffle) {
            calls.add("shuffle " + shuffle);
        }

        @Override
        public void seekSecondAbsolute(long second) {
            calls.add("abs " + second);
        }

        @Override
        public void seekSecondRelative(int seconds) {
            calls.add("rel " + seconds);
        }

        @Override
        public void seekToTrack(long id) {
            calls.add("seek " + id);
        }

        @Override
        public void trackInserted(int index, OhQueue.Entry entry) {
            calls.add("inserted " + index + " " + entry.getId());
        }

        @Override
        public void trackDeleted(int index, long id) {
            calls.add("deleted " + index + " " + id);
        }

        @Override
        public void queueCleared() {
            calls.add("cleared");
        }

        @Override
        public void setVolume(int percent) {
            calls.add("volume " + percent);
        }

        @Override
        public void setMute(boolean mute) {
            calls.add("mute " + mute);
        }
    }

    private FakePlayback playback;
    private final List<Runnable> delayed = new ArrayList<>();
    private OhRendererCore core;
    private ServiceHarness<OhServiceState> playlist;
    private ServiceHarness<OhServiceState> product;
    private ServiceHarness<OhServiceState> volume;
    private ServiceHarness<OhServiceState> info;
    private ServiceHarness<OhServiceState> time;
    private ServiceHarness<OhServiceState> radio;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() throws Exception {
        playback = new FakePlayback();
        core = new OhRendererCore(playback, (runnable, millis) -> delayed.add(runnable),
                new OhRendererCore.Details("yaacc.de", "https://www.yaacc.de", "YAACC", "Room", "Test renderer"),
                "http-get:*:audio/mpeg:*");
        List<LocalService<?>> services = core.buildServices(2000);
        product = new ServiceHarness<>((LocalService<OhServiceState>) services.get(0), OhServiceDefs.product().serviceType(), "Product");
        playlist = new ServiceHarness<>((LocalService<OhServiceState>) services.get(1), OhServiceDefs.playlist().serviceType(), "Playlist");
        info = new ServiceHarness<>((LocalService<OhServiceState>) services.get(2), OhServiceDefs.info().serviceType(), "Info");
        time = new ServiceHarness<>((LocalService<OhServiceState>) services.get(3), OhServiceDefs.time().serviceType(), "Time");
        volume = new ServiceHarness<>((LocalService<OhServiceState>) services.get(4), OhServiceDefs.volume().serviceType(), "Volume");
        radio = new ServiceHarness<>((LocalService<OhServiceState>) services.get(5), OhServiceDefs.radio().serviceType(), "Radio");
    }

    @After
    public void tearDown() {
        for (ServiceHarness<OhServiceState> harness : java.util.Arrays.asList(product, playlist, info, time, volume, radio)) {
            harness.close();
        }
    }

    // ---- helpers

    private static String value(StreamResponseMessage answer, String name) {
        assertTrue(ServiceHarness.describe(answer), ServiceHarness.succeeded(answer));
        Matcher m = Pattern.compile("<" + name + ">(.*?)</" + name + ">", Pattern.DOTALL).matcher(answer.getBodyString());
        assertTrue(name + " in " + answer.getBodyString(), m.find());
        return m.group(1);
    }

    private static void assertFault(int code, StreamResponseMessage answer) {
        assertEquals(500, answer.getOperation().getStatusCode());
        assertTrue(answer.getBodyString(), answer.getBodyString().contains("<errorCode>" + code + "</errorCode>"));
    }

    private long insert(long after, String uri) {
        return Long.parseLong(value(playlist.soap("Insert", "AfterId", String.valueOf(after), "Uri", uri, "Metadata", ""), "NewId"));
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("not within 5 s: " + what);
            }
            Thread.sleep(5);
        }
    }

    private void runDelayed() {
        List<Runnable> now = new ArrayList<>(delayed);
        delayed.clear();
        for (Runnable runnable : now) {
            runnable.run();
        }
    }

    // ---- the queue

    @Test
    public void tracksAreInsertedReadAndDeleted() {
        long a = insert(0, "http://192.0.2.1/a.mp3");
        long c = insert(a, "http://192.0.2.1/c.mp3");
        long b = insert(a, "http://192.0.2.1/b.mp3?x=1&y=2");

        StreamResponseMessage idArray = playlist.soap("IdArray");
        assertEquals(OhIds.encodeIdArray(java.util.Arrays.asList(a, b, c)), value(idArray, "Array"));
        assertEquals(String.valueOf(core.queue().token()), value(idArray, "Token"));
        StreamResponseMessage read = playlist.soap("Read", "Id", String.valueOf(b));
        assertEquals("http://192.0.2.1/b.mp3?x=1&amp;y=2", value(read, "Uri"));
        String list = value(playlist.soap("ReadList", "IdList", a + " 99 " + c), "TrackList");
        assertTrue(list, list.contains("&lt;Id&gt;" + a + "&lt;/Id&gt;") && list.contains("&lt;Id&gt;" + c + "&lt;/Id&gt;"));
        assertFalse(list, list.contains("&lt;Id&gt;99&lt;"));

        assertTrue(ServiceHarness.succeeded(playlist.soap("DeleteId", "Value", String.valueOf(b))));
        assertTrue(ServiceHarness.succeeded(playlist.soap("DeleteAll")));
        assertEquals(OhIds.encodeIdArray(new ArrayList<Long>()), value(playlist.soap("IdArray"), "Array"));

        assertEquals(java.util.Arrays.asList("inserted 0 " + a, "inserted 1 " + c, "inserted 1 " + b, "deleted 1 " + b, "cleared"),
                playback.calls);
    }

    @Test
    public void errorsCarryTheCodesOfTheOpenHomePlaylist() {
        long a = insert(0, "http://192.0.2.1/a.mp3");

        assertFault(800, playlist.soap("Insert", "AfterId", "99", "Uri", "http://192.0.2.1/b.mp3", "Metadata", ""));
        assertFault(800, playlist.soap("DeleteId", "Value", "99"));
        assertFault(800, playlist.soap("Read", "Id", "99"));
        assertFault(800, playlist.soap("SeekId", "Value", "99"));
        assertFault(802, playlist.soap("SeekIndex", "Value", "5"));
        assertEquals(1, core.queue().size());
        assertTrue(ServiceHarness.succeeded(playlist.soap("SeekId", "Value", String.valueOf(a))));
        assertTrue(ServiceHarness.succeeded(playlist.soap("SeekIndex", "Value", "0")));
        assertTrue(playback.calls.contains("seek " + a));
    }

    @Test
    public void theThousandAndFirstTrackIsRefusedWithError801() {
        long after = 0;
        for (int i = 0; i < OhQueue.DEFAULT_TRACKS_MAX; i++) {
            after = core.queue().insert(after, "http://192.0.2.1/" + i + ".mp3", "");
        }

        assertFault(801, playlist.soap("Insert", "AfterId", "0", "Uri", "http://192.0.2.1/x.mp3", "Metadata", ""));

        assertEquals(OhQueue.DEFAULT_TRACKS_MAX, core.queue().size());
        // a ReadList of many ids answers at most the tracks of the queue and does not run away
        StringBuilder ids = new StringBuilder();
        for (int i = 1; i <= 5000; i++) {
            ids.append(i).append(' ');
        }
        assertTrue(ServiceHarness.succeeded(playlist.soap("ReadList", "IdList", ids.toString())));
    }

    @Test
    public void hostileInputIsRefusedWithError402AndChangesNothing() {
        String big = new String(new char[OhQueue.MAX_METADATA_LENGTH + 1]).replace('\0', 'm');
        assertFault(402, playlist.soap("Insert", "AfterId", "0", "Uri", "file:///sdcard/a.mp3", "Metadata", ""));
        assertFault(402, playlist.soap("Insert", "AfterId", "0", "Uri", "content://media/external/audio/1", "Metadata", ""));
        assertFault(402, playlist.soap("Insert", "AfterId", "0", "Uri", "http://user:pw@192.0.2.1/a.mp3", "Metadata", ""));
        assertFault(402, playlist.soap("Insert", "AfterId", "0", "Uri", "http://192.0.2.1/a.mp3", "Metadata", big));
        assertEquals(0, core.queue().size());
        assertTrue(playback.calls.isEmpty());
    }

    @Test
    public void aBurstOfEditsIsOneDelayedIdArrayEvent() throws Exception {
        assertEquals(200, playlist.subscribeExchange());
        await("initial event", () -> !playlist.sentEvents.isEmpty());
        int before = playlist.sentEvents.size();

        insert(0, "http://192.0.2.1/a.mp3");
        insert(0, "http://192.0.2.1/b.mp3");
        insert(0, "http://192.0.2.1/c.mp3");
        Thread.sleep(100);
        assertEquals("nothing is sent before the delay is over", before, playlist.sentEvents.size());
        assertEquals(1, delayed.size());

        runDelayed();

        await("IdArray event", () -> playlist.sentEvents.size() > before);
        String event = playlist.sentEvents.get(playlist.sentEvents.size() - 1);
        assertTrue(event, event.contains("IdArray"));
        assertTrue(delayed.isEmpty());
    }

    // ---- transport and modes

    @Test
    public void theTransportActionsGoToThePlayer() {
        for (String action : new String[]{"Play", "Pause", "Stop", "Next", "Previous"}) {
            assertTrue(action, ServiceHarness.succeeded(playlist.soap(action)));
        }
        assertTrue(ServiceHarness.succeeded(playlist.soap("SeekSecondAbsolute", "Value", "42")));
        assertTrue(ServiceHarness.succeeded(playlist.soap("SeekSecondRelative", "Value", "-5")));

        assertEquals(java.util.Arrays.asList("play", "pause", "stop", "next", "previous", "abs 42", "rel -5"), playback.calls);
    }

    @Test
    public void repeatAndShuffleAreSetAndReadBack() {
        assertEquals("0", value(playlist.soap("Repeat"), "Value")); // UPnP booleans are 0 and 1

        assertTrue(ServiceHarness.succeeded(playlist.soap("SetRepeat", "Value", "1")));
        assertTrue(ServiceHarness.succeeded(playlist.soap("SetShuffle", "Value", "1")));

        assertEquals("1", value(playlist.soap("Repeat"), "Value"));
        assertEquals("1", value(playlist.soap("Shuffle"), "Value"));
        assertEquals(java.util.Arrays.asList("repeat true", "shuffle true"), playback.calls);
    }

    @Test
    public void whatThePlayerReportsIsReadAndEvented() throws Exception {
        assertEquals(200, playlist.subscribeExchange());
        await("initial event", () -> !playlist.sentEvents.isEmpty());
        int before = playlist.sentEvents.size();

        core.setTransportState(OhTransportState.PLAYING);
        core.setCurrentId(7);

        assertEquals("Playing", value(playlist.soap("TransportState"), "Value"));
        assertEquals("7", value(playlist.soap("Id"), "Value"));
        await("TransportState event", () -> playlist.sentEvents.size() > before);
        String events = String.join("", playlist.sentEvents);
        assertTrue(events, events.contains("TransportState") && events.contains("Playing"));
        assertEquals("1000", value(playlist.soap("TracksMax"), "Value"));
        assertEquals("http-get:*:audio/mpeg:*", value(playlist.soap("ProtocolInfo"), "Value"));
    }

    // ---- product, info, time, volume

    @Test
    public void theProductHasOneSourceTheStartedPlaylist() {
        String xml = value(product.soap("SourceXml"), "Value");
        assertTrue(xml, xml.contains("&lt;SystemName&gt;Playlist&lt;/SystemName&gt;"));
        assertEquals("1", value(product.soap("SourceCount"), "Value"));
        assertEquals("0", value(product.soap("Standby"), "Value"));

        assertTrue(ServiceHarness.succeeded(product.soap("SetSourceIndex", "Value", "0")));
        assertTrue(ServiceHarness.succeeded(product.soap("SetSourceBySystemName", "Value", "Playlist")));
        assertTrue(ServiceHarness.succeeded(product.soap("SetSourceIndexByName", "Value", "Playlist")));
        assertFault(402, product.soap("SetSourceIndex", "Value", "1"));
        assertFault(402, product.soap("SetSourceBySystemName", "Value", "Radio"));
        StreamResponseMessage source = product.soap("Source", "Index", "0");
        assertEquals("Playlist", value(source, "SystemName"));
        assertEquals("Playlist", value(source, "Type"));
        assertFault(402, product.soap("Source", "Index", "1"));
        assertEquals("Test renderer", value(product.soap("Product"), "Name"));
        assertEquals("yaacc.de", value(product.soap("Manufacturer"), "Name"));
    }

    @Test
    public void theVolumeIsSetClampedAndReadBack() {
        assertTrue(ServiceHarness.succeeded(volume.soap("SetVolume", "Value", "40")));
        assertEquals("40", value(volume.soap("Volume"), "Value"));
        assertTrue(ServiceHarness.succeeded(volume.soap("VolumeInc")));
        assertEquals("41", value(volume.soap("Volume"), "Value"));
        assertTrue(ServiceHarness.succeeded(volume.soap("VolumeDec")));
        assertTrue(ServiceHarness.succeeded(volume.soap("SetVolume", "Value", "4000")));
        assertEquals("100", value(volume.soap("Volume"), "Value"));
        assertTrue(ServiceHarness.succeeded(volume.soap("SetMute", "Value", "1")));
        assertEquals("1", value(volume.soap("Mute"), "Value"));
        StreamResponseMessage characteristics = volume.soap("Characteristics");
        assertEquals("100", value(characteristics, "VolumeMax"));
        assertEquals("100", value(characteristics, "VolumeSteps"));

        assertEquals(java.util.Arrays.asList("volume 40", "volume 41", "volume 40", "volume 100", "mute true"), playback.calls);
    }

    @Test
    public void balanceAndFadeAreAcceptedAndStayZero() {
        assertTrue(ServiceHarness.succeeded(volume.soap("SetBalance", "Value", "5")));
        assertTrue(ServiceHarness.succeeded(volume.soap("FadeInc")));
        assertEquals("0", value(volume.soap("Balance"), "Value"));
        assertEquals("0", value(volume.soap("Fade"), "Value"));
    }

    @Test
    public void theTrackAndTheTimeAreReported() {
        core.setTrack("http://192.0.2.1/a.mp3", "<DIDL-Lite/>", 215);
        core.setSeconds(12);

        StreamResponseMessage track = info.soap("Track");
        assertEquals("http://192.0.2.1/a.mp3", value(track, "Uri"));
        assertEquals("&lt;DIDL-Lite/&gt;", value(track, "Metadata"));
        assertEquals("1", value(info.soap("Counters"), "TrackCount"));
        StreamResponseMessage times = time.soap("Time");
        assertEquals("215", value(times, "Duration"));
        assertEquals("12", value(times, "Seconds"));
        assertEquals("1", value(times, "TrackCount"));
        assertEquals("215", value(info.soap("Details"), "Duration"));
    }

    @Test
    public void aReadListOfTheSameIdManyTimesDoesNotMultiplyTheAnswer() throws Exception {
        String big = "&".repeat(16384);
        StreamResponseMessage inserted = playlist.soap("Insert", "AfterId", "0", "Uri", "http://h/a.mp3", "Metadata", big);
        assertTrue(ServiceHarness.succeeded(inserted));
        long id = core.queue().snapshot().get(0).getId();
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            ids.append(id).append(' ');
        }

        StreamResponseMessage answer = playlist.soap("ReadList", "IdList", ids.toString());

        assertTrue(ServiceHarness.succeeded(answer));
        assertTrue("answer " + answer.getBodyString().length(), answer.getBodyString().length() < 400_000);
    }

    @Test
    public void standbyStopsThePlaybackKeepsTheQueueAndIsReported() {
        assertTrue(ServiceHarness.succeeded(playlist.soap("Insert", "AfterId", "0", "Uri", "http://h/a.mp3", "Metadata", "")));
        playback.calls.clear();

        assertTrue(ServiceHarness.succeeded(product.soap("SetStandby", "Value", "1")));

        assertEquals("1", value(product.soap("Standby"), "Value"));
        assertTrue(playback.calls.contains("stop"));
        assertEquals(1, core.queue().size());
    }

    @Test
    public void awakeStaysAwakeWithoutStoppingAnything() {
        playback.calls.clear();

        assertTrue(ServiceHarness.succeeded(product.soap("SetStandby", "Value", "0")));

        assertEquals("0", value(product.soap("Standby"), "Value"));
        assertFalse(playback.calls.contains("stop"));
    }

    @Test
    public void playAndSeekingWakeADeviceInStandby() {
        assertTrue(ServiceHarness.succeeded(playlist.soap("Insert", "AfterId", "0", "Uri", "http://h/a.mp3", "Metadata", "")));
        long id = core.queue().snapshot().get(0).getId();

        product.soap("SetStandby", "Value", "1");
        assertTrue(ServiceHarness.succeeded(playlist.soap("Play")));
        assertEquals("0", value(product.soap("Standby"), "Value"));

        product.soap("SetStandby", "Value", "1");
        assertTrue(ServiceHarness.succeeded(playlist.soap("SeekId", "Value", String.valueOf(id))));
        assertEquals("0", value(product.soap("Standby"), "Value"));

        product.soap("SetStandby", "Value", "1");
        assertTrue(ServiceHarness.succeeded(playlist.soap("SeekIndex", "Value", "0")));
        assertEquals("0", value(product.soap("Standby"), "Value"));
    }

    @Test
    public void aFailedSeekDoesNotWakeTheDevice() {
        product.soap("SetStandby", "Value", "1");

        assertFault(800, playlist.soap("SeekId", "Value", "4711"));

        assertEquals("1", value(product.soap("Standby"), "Value"));
    }

    // ---- radio: what Home Assistant's play_media does

    @Test
    public void setChannelReplacesTheQueueWithThatTrackAndPlayPlaysIt() {
        insert(0, "http://h/old1.mp3");
        insert(0, "http://h/old2.mp3");
        playback.calls.clear();

        assertTrue(ServiceHarness.succeeded(radio.soap("SetChannel", "Uri", "http://h/new.mp3", "Metadata", "<meta/>")));
        assertTrue(ServiceHarness.succeeded(radio.soap("Play")));

        assertEquals(1, core.queue().size());
        assertEquals("http://h/new.mp3", core.queue().snapshot().get(0).getUri());
        assertEquals("<meta/>", core.queue().snapshot().get(0).getMetadata());
        assertEquals(java.util.Arrays.asList("cleared", "inserted 0 " + core.queue().snapshot().get(0).getId(), "play"), playback.calls);
        assertEquals("http://h/new.mp3", value(radio.soap("Channel"), "Uri"));
        assertEquals(String.valueOf(core.queue().snapshot().get(0).getId()), value(radio.soap("Id"), "Value"));
    }

    @Test
    public void radioPlayWakesADeviceInStandby() {
        product.soap("SetStandby", "Value", "1");

        assertTrue(ServiceHarness.succeeded(radio.soap("Play")));

        assertEquals("0", value(product.soap("Standby"), "Value"));
        assertTrue(playback.calls.contains("play"));
    }

    @Test
    public void aChannelThatIsNotAnHttpAddressChangesNothing() {
        insert(0, "http://h/keep.mp3");
        playback.calls.clear();

        assertFault(402, radio.soap("SetChannel", "Uri", "file:///data/secret.mp3", "Metadata", ""));
        assertFault(402, radio.soap("SetChannel", "Uri", "", "Metadata", ""));
        assertFault(402, radio.soap("SetChannel", "Uri", "http://h/a.mp3", "Metadata", "x".repeat(OhQueue.MAX_METADATA_LENGTH + 1)));

        assertEquals(1, core.queue().size());
        assertEquals("http://h/keep.mp3", core.queue().snapshot().get(0).getUri());
        assertTrue(playback.calls.isEmpty());
    }

    @Test
    public void theRadioReportsTheTransportStateOfThePlayer() {
        core.setTransportState(OhTransportState.PLAYING);

        assertEquals("Playing", value(radio.soap("TransportState"), "Value"));
    }

    @Test
    public void theChannelCanBeReadByItsId() {
        radio.soap("SetChannel", "Uri", "http://h/a.mp3", "Metadata", "<m/>");
        String id = value(radio.soap("Id"), "Value");

        assertEquals("&lt;m/&gt;", value(radio.soap("Read", "Id", id), "Metadata"));
        assertTrue(value(radio.soap("ReadList", "IdList", id + " 4711"), "ChannelList").contains("&lt;Id&gt;" + id + "&lt;/Id&gt;"));
        assertFault(800, radio.soap("Read", "Id", "4711"));
        assertEquals("1", value(radio.soap("ChannelsMax"), "Value"));
        assertEquals("0", value(radio.soap("IdArrayChanged", "Token", value(radio.soap("IdArray"), "Token")), "Value"));
    }
}
