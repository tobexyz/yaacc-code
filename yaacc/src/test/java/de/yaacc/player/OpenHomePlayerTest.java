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
package de.yaacc.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;

import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.DeviceIdentity;
import org.fourthline.cling.model.types.UDN;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import de.yaacc.openhome.OhControl;
import de.yaacc.openhome.OhQueue;
import de.yaacc.openhome.OhTransportState;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.server.openhome.CoreControl;
import de.yaacc.upnp.server.openhome.OhPlayback;
import de.yaacc.upnp.server.openhome.OhRendererCore;

/**
 * The OpenHome player against the OpenHome services of YAACC's own renderer (in the same process, through the real Cling
 * services): the playlist the player shows is the queue of the device, the buttons act on the device. Plain JVM.
 */
public class OpenHomePlayerTest {

    private static final class Recorder implements OhPlayback {
        final List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override public void play() { calls.add("play"); }
        @Override public void pause() { calls.add("pause"); }
        @Override public void stop() { calls.add("stop"); }
        @Override public void next() { calls.add("next"); }
        @Override public void previous() { calls.add("previous"); }
        @Override public void setRepeat(boolean repeat) { calls.add("repeat " + repeat); }
        @Override public void setShuffle(boolean shuffle) { calls.add("shuffle " + shuffle); }
        @Override public void seekSecondAbsolute(long second) { calls.add("abs " + second); }
        @Override public void seekSecondRelative(int seconds) { calls.add("rel " + seconds); }
        @Override public void seekToTrack(long id) { calls.add("seek " + id); }
        @Override public void trackInserted(int index, OhQueue.Entry entry) { calls.add("inserted"); }
        @Override public void trackDeleted(int index, long id) { calls.add("deleted " + id); }
        @Override public void queueCleared() { calls.add("cleared"); }
        @Override public void setVolume(int percent) { calls.add("volume " + percent); }
        @Override public void setMute(boolean mute) { calls.add("mute " + mute); }
    }

    private Recorder device;
    private OhRendererCore core;
    private OpenHomePlayer player;

    @Before
    public void setUp() throws Exception {
        device = new Recorder();
        core = new OhRendererCore(device, (runnable, delay) -> runnable.run(),
                new OhRendererCore.Details("m", "https://example.org", "model", "room", "name"), "http-get:*:audio/mpeg:*");
        CoreControl control = new CoreControl(core);
        Context context = mock(Context.class);
        UpnpClient client = mock(UpnpClient.class);
        when(client.getContext()).thenReturn(context);
        Device<?, ?, ?> receiver = mock(Device.class);
        DeviceIdentity identity = new DeviceIdentity(new UDN("receiver-1"));
        when(receiver.getIdentity()).thenReturn(identity);
        player = new OpenHomePlayer(client, receiver, "name", "short") {
            @Override
            protected PlayMode readDefaultPlayMode() {
                return PlayMode.of(PlayMode.RepeatMode.OFF, false);
            }

            @Override
            protected OhControl createControl() {
                return control;
            }
        };
    }

    @After
    public void tearDown() {
        player.stopWorkers();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long end = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < end) {
            Thread.sleep(10);
        }
        assertTrue("condition not reached in time", condition.getAsBoolean());
    }

    private static List<String> titles(Player player) {
        List<String> titles = new ArrayList<>();
        for (PlayableItem item : player.getItems()) {
            titles.add(item.getTitle());
        }
        return titles;
    }

    @Test
    public void thePlaylistIsTheQueueOfTheDevice() throws Exception {
        long a = core.queue().insert(0, "http://h/a.mp3", "");
        core.queue().insert(a, "http://h/b.mp3", "");

        player.play(); // any command makes the player look at the device

        await(() -> player.getItems().size() == 2);
        assertEquals(java.util.Arrays.asList("http://h/a.mp3", "http://h/b.mp3"), titles(player));
        await(() -> device.calls.contains("play"));
    }

    @Test
    public void theCurrentTrackAndTheStateComeFromTheDevice() throws Exception {
        long a = core.queue().insert(0, "http://h/a.mp3", "");
        long b = core.queue().insert(a, "http://h/b.mp3", "");
        core.setTransportState(OhTransportState.PLAYING);
        core.setCurrentId(b);
        core.setSeconds(30);

        player.pause();

        await(() -> player.getItems().size() == 2 && player.getCurrentItemIndex() == 1);
        assertTrue(player.isPlaying());
        assertFalse(player.isPaused());
        assertTrue(player.getCurrentPosition() >= 30_000);
    }

    @Test
    public void pausedAndStoppedAreShown() throws Exception {
        core.queue().insert(0, "http://h/a.mp3", "");
        core.setTransportState(OhTransportState.PAUSED);
        player.play();
        await(() -> player.isPaused());
        assertFalse(player.isPlaying());

        core.setTransportState(OhTransportState.STOPPED);
        player.stop();
        await(() -> player.isStopped());
    }

    @Test
    public void theButtonsActOnTheDevice() throws Exception {
        core.queue().insert(0, "http://h/a.mp3", "");

        player.play();
        player.pause();
        player.next();
        player.previous();
        player.stop();
        player.seekTo(42_000);

        await(() -> device.calls.containsAll(java.util.Arrays.asList("play", "pause", "next", "previous", "stop", "abs 42")));
    }

    @Test
    public void deletingATrackInThePlaylistDeletesItOnTheDevice() throws Exception {
        long a = core.queue().insert(0, "http://h/a.mp3", "");
        core.queue().insert(a, "http://h/b.mp3", "");
        player.pause();
        await(() -> player.getItems().size() == 2);

        assertTrue(player.removeItem(0));

        await(() -> core.queue().size() == 1);
        assertEquals("http://h/b.mp3", core.queue().snapshot().get(0).getUri());
        await(() -> player.getItems().size() == 1);
    }

    @Test
    public void aTrackAddedOnTheDeviceAppearsInThePlayer() throws Exception {
        long a = core.queue().insert(0, "http://h/a.mp3", "");
        player.pause();
        await(() -> player.getItems().size() == 1);

        core.queue().insert(a, "http://h/b.mp3", ""); // somebody else adds it
        player.pause();

        await(() -> player.getItems().size() == 2);
    }

    @Test
    public void thePlayModeIsRepeatAndShuffleOfTheDevice() throws Exception {
        core.setRepeat(true);
        core.setShuffle(true);

        player.pause();

        await(() -> player.getPlayMode().isShuffle() && player.getPlayMode().getRepeat() != PlayMode.RepeatMode.OFF);
    }

    @Test
    public void aModeChosenInTheAppReachesTheDevice() throws Exception {
        player.setPlayMode(PlayMode.of(PlayMode.RepeatMode.ALL, true));

        await(() -> device.calls.contains("repeat true") && device.calls.contains("shuffle true"));
    }

    @Test
    public void volumeAndMuteGoToTheDeviceAndComeBack() throws Exception {
        player.setVolume(65);
        player.setMute(true);

        await(() -> device.calls.contains("volume 65") && device.calls.contains("mute true"));
        await(() -> player.getVolume() == 65 && player.getMute());
        assertTrue(player.hasActionGetVolume());
    }

    @Test
    public void nothingIsLockedAndAMoveNeedsAtLeastTwoTracks() {
        assertFalse(player.moveInPlayOrder(0, 1));
        assertFalse(player.isPlaylistPositionLocked(0));
    }

    @Test
    public void theDurationIsShownAsTime() throws Exception {
        core.queue().insert(0, "http://h/a.mp3", "");
        core.setTransportState(OhTransportState.PLAYING);
        core.setTrack("http://h/a.mp3", "", 3723);

        player.pause();

        await(() -> "01:02:03".equals(player.getDuration()));
    }

    private static String didl(String artUri) {
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "
                + "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"1\" parentID=\"0\" restricted=\"1\">"
                + "<dc:title>Title</dc:title><upnp:class>object.item.audioItem.musicTrack</upnp:class>"
                + "<upnp:albumArtURI>" + artUri + "</upnp:albumArtURI></item></DIDL-Lite>";
    }

    @Test
    public void theAlbumArtIsTheOneOfTheMetadataOfTheCurrentTrack() throws Exception {
        long a = core.queue().insert(0, "http://h/a.mp3", didl("http://h/art-a.jpg"));
        long b = core.queue().insert(a, "http://h/b.mp3", didl("https://h/art-b.jpg"));
        core.setTransportState(OhTransportState.PLAYING);
        core.setCurrentId(a);
        player.pause();
        await(() -> player.getItems().size() == 2 && player.getAlbumArt() != null);
        assertEquals("http://h/art-a.jpg", player.getAlbumArt().toString());

        core.setCurrentId(b);
        player.pause();

        await(() -> player.getAlbumArt() != null && player.getAlbumArt().toString().equals("https://h/art-b.jpg"));
    }

    @Test
    public void anAlbumArtAddressThatIsNotHttpIsNotUsed() throws Exception {
        long a = core.queue().insert(0, "http://h/a.mp3", didl("file:///data/secret.jpg"));
        core.setCurrentId(a);

        player.pause();

        await(() -> player.getItems().size() == 1);
        assertNull(player.getAlbumArt());
    }

    @Test
    public void aTrackWithoutMetadataHasNoAlbumArt() throws Exception {
        core.queue().insert(0, "http://h/a.mp3", "");

        player.pause();

        await(() -> player.getItems().size() == 1);
        assertNull(player.getAlbumArt());
    }

    // ---- files picked on this phone: shared just in time, the current file and the next one

    private static android.net.Uri uri(String text) {
        android.net.Uri uri = mock(android.net.Uri.class);
        when(uri.toString()).thenReturn(text);
        return uri;
    }

    private static PlayableItem placeholder(String name) {
        PlayableItem item = new PlayableItem();
        item.setTitle(name);
        item.setUri(uri("pending:" + name));
        return item;
    }

    private final List<Integer> bound = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<Integer> released = new java.util.concurrent.CopyOnWriteArrayList<>();

    private PlayableItem[] pickFiles(int count, boolean bindWorks) {
        return pickFiles(player, count, bindWorks);
    }

    private PlayableItem[] pickFiles(OpenHomePlayer target, int count, boolean bindWorks) {
        PlayableItem[] items = new PlayableItem[count];
        for (int i = 0; i < count; i++) {
            items[i] = placeholder("file" + i);
        }
        List<PlayableItem> list = java.util.Arrays.asList(items);
        de.yaacc.share.QueueShareWindow window = new de.yaacc.share.QueueShareWindow(list, index -> {
            if (!bindWorks) {
                return null;
            }
            list.get(index).setUri(uri("http://share/" + index));
            bound.add(index);
            return () -> released.add(index);
        });
        target.setShareLease(window.newLease());
        return items;
    }

    @Test
    public void aSinglePickedFileIsAPlaylistOfOneSharedFile() throws Exception {
        player.setItems(pickFiles(1, true));

        await(() -> core.queue().size() == 1);
        assertEquals("http://share/0", core.queue().snapshot().get(0).getUri());
        assertEquals(java.util.Arrays.asList(0), bound);
    }

    @Test
    public void severalPickedFilesAreSharedAndSentTwoAtATimeAsTheDeviceMovesOn() throws Exception {
        player.setItems(pickFiles(4, true));

        await(() -> core.queue().size() == 2);
        assertEquals("http://share/0", core.queue().snapshot().get(0).getUri());
        assertEquals("http://share/1", core.queue().snapshot().get(1).getUri());

        // the device moves on to the second file: the third is shared and given to the device, the first is released
        core.setCurrentId(core.queue().snapshot().get(1).getId());
        player.pause();
        await(() -> core.queue().size() == 3);
        assertEquals("http://share/2", core.queue().snapshot().get(2).getUri());
        await(() -> released.contains(0));

        core.setCurrentId(core.queue().snapshot().get(2).getId());
        player.pause();
        await(() -> core.queue().size() == 4);
        assertEquals("http://share/3", core.queue().snapshot().get(3).getUri());
    }

    @Test
    public void aFileThatCanNotBeSharedSendsNothingAndDoesNotCrash() throws Exception {
        core.queue().insert(0, "http://h/old.mp3", "");

        player.setItems(pickFiles(2, false));
        player.pause(); // runs after the files

        await(() -> player.getItems().size() == 1);
        assertEquals(1, core.queue().size()); // the queue of the device stays as it was
        assertTrue(bound.isEmpty());
    }

    @Test
    public void theScreenShowsAllPickedFilesAlthoughTheDeviceOnlyHasTwo() throws Exception {
        player.setItems(pickFiles(5, true));

        await(() -> core.queue().size() == 2);
        await(() -> player.getItems().size() == 5);
        assertEquals("file4", player.getItems().get(4).getTitle());
        assertEquals(2, core.queue().size());
    }

    @Test
    public void aFileThatWasNotSentYetCanBePlayedByPickingIt() throws Exception {
        player.setItems(pickFiles(5, true));
        await(() -> player.getItems().size() == 5);
        device.calls.clear();

        player.playItemAt(3);

        await(() -> core.queue().size() == 2 && core.queue().snapshot().get(0).getUri().equals("http://share/3"));
        assertEquals("http://share/4", core.queue().snapshot().get(1).getUri());
        await(() -> device.calls.contains("play"));
        assertTrue(released.contains(0) || released.contains(1)); // what was shared before is released
    }

    @Test
    public void previousSharesTheFileBeforeAgainAndStartsIt() throws Exception {
        player.setItems(pickFiles(4, true));
        await(() -> core.queue().size() == 2);
        core.setCurrentId(core.queue().snapshot().get(1).getId()); // the device is at the second file
        player.pause();
        await(() -> core.queue().size() == 3);
        device.calls.clear();

        player.previous();

        await(() -> core.queue().size() == 2 && core.queue().snapshot().get(0).getUri().equals("http://share/0"));
        await(() -> device.calls.contains("play"));
    }

    @Test
    public void filesTheDeviceHasAreLockedTheOthersCanBeTakenOutOfTheList() throws Exception {
        player.setItems(pickFiles(5, true));
        await(() -> player.getItems().size() == 5);

        assertTrue(player.isPlaylistPositionLocked(0));
        assertTrue(player.isPlaylistPositionLocked(1));
        assertFalse(player.isPlaylistPositionLocked(2));

        assertTrue(player.removeItem(3));
        await(() -> player.getItems().size() == 4);
        assertEquals("file4", player.getItems().get(3).getTitle());
        assertFalse(player.removeItem(0)); // the device has this one
        assertEquals(4, player.getItems().size());
    }

    @Test
    public void aTrackCanBeDraggedToAnotherPlaceOfTheDevicesPlaylist() throws Exception {
        long a = core.queue().insert(0, "http://h/a.mp3", "");
        long b = core.queue().insert(a, "http://h/b.mp3", "");
        core.queue().insert(b, "http://h/c.mp3", "");
        player.pause();
        await(() -> player.getItems().size() == 3);

        assertTrue(player.moveInPlayOrder(2, 0));

        assertEquals("http://h/c.mp3", player.getItems().get(0).getTitle()); // shown at once
        await(() -> core.queue().snapshot().get(0).getUri().equals("http://h/c.mp3"));
        assertEquals("http://h/a.mp3", core.queue().snapshot().get(1).getUri());
        await(() -> player.getItems().size() == 3 && player.getItems().get(0).getTitle().equals("http://h/c.mp3"));
    }

    @Test
    public void theTrackThatPlaysIsNotMoved() throws Exception {
        long a = core.queue().insert(0, "http://h/a.mp3", "");
        core.queue().insert(a, "http://h/b.mp3", "");
        core.setTransportState(OhTransportState.PLAYING);
        core.setCurrentId(a);
        player.pause();
        await(() -> player.getItems().size() == 2 && player.isPlaying());

        assertFalse(player.moveInPlayOrder(0, 1));
    }

    @Test
    public void positionsOutsideTheListAreNotMoved() throws Exception {
        core.queue().insert(0, "http://h/a.mp3", "");
        player.pause();
        await(() -> player.getItems().size() == 1);

        assertFalse(player.moveInPlayOrder(0, 5));
        assertFalse(player.moveInPlayOrder(-1, 0));
        assertFalse(player.moveInPlayOrder(0, 0));
    }

    @Test
    public void pickedFilesThatTheDeviceDoesNotHaveYetCanBeReordered() throws Exception {
        player.setItems(pickFiles(5, true));
        await(() -> player.getItems().size() == 5);

        assertFalse(player.moveInPlayOrder(3, 0)); // the device has the first two
        assertTrue(player.moveInPlayOrder(4, 2));

        assertEquals("file4", player.getItems().get(2).getTitle());
        await(() -> player.getItems().size() == 5 && player.getItems().get(2).getTitle().equals("file4"));
    }

    // ---- a device that does not answer, a failing append

    /** Lets calls to the device fail while {@code failing} is set. */
    private final java.util.concurrent.atomic.AtomicBoolean failing = new java.util.concurrent.atomic.AtomicBoolean();
    /** While set, the Insert of a track waits (the first job of a drag is held back). */
    private volatile java.util.concurrent.CountDownLatch gate;
    private final java.util.concurrent.atomic.AtomicBoolean failingAppend = new java.util.concurrent.atomic.AtomicBoolean();

    private OpenHomePlayer playerWithFailingDevice() throws Exception {
        CoreControl inner = new CoreControl(core);
        Context context = mock(Context.class);
        UpnpClient client = mock(UpnpClient.class);
        when(client.getContext()).thenReturn(context);
        Device<?, ?, ?> receiver = mock(Device.class);
        when(receiver.getIdentity()).thenReturn(new DeviceIdentity(new UDN("receiver-2")));
        OpenHomePlayer other = new OpenHomePlayer(client, receiver, "name", "short") {
            @Override
            protected PlayMode readDefaultPlayMode() {
                return PlayMode.of(PlayMode.RepeatMode.OFF, false);
            }

            @Override
            protected OhControl createControl() {
                return (service, action, arguments) -> {
                    java.util.concurrent.CountDownLatch held = gate;
                    if (held != null && "Insert".equals(action)) {
                        try {
                            held.await(5, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    if (failing.get() || (failingAppend.get() && "Insert".equals(action))) {
                        throw new de.yaacc.openhome.OhControlException(de.yaacc.openhome.OhControlException.NO_ANSWER, "no answer");
                    }
                    return inner.invoke(service, action, arguments);
                };
            }
        };
        return other;
    }

    @Test
    public void aDragWhoseCommandCouldNotRunDoesNotFreezeThePlayer() throws Exception {
        long a = core.queue().insert(0, "http://h/a.mp3", "");
        long b = core.queue().insert(a, "http://h/b.mp3", "");
        core.queue().insert(b, "http://h/c.mp3", "");
        OpenHomePlayer other = playerWithFailingDevice();
        try {
            other.pause();
            await(() -> other.getItems().size() == 3);

            failing.set(true); // the device does not answer: after some failures the player prepares the device again first
            for (int i = 0; i < 4; i++) {
                other.pause();
            }
            Thread.sleep(300);
            assertTrue(other.moveInPlayOrder(2, 0)); // its job ends in the failed preparation, before it can move anything
            Thread.sleep(300);
            failing.set(false);

            core.queue().insert(0, "http://h/d.mp3", ""); // the poll must look at the device again and show this
            other.pause();
            await(() -> other.getItems().size() == 4);
        } finally {
            other.stopWorkers();
        }
    }

    @Test
    public void twoQuickDragsEachCountFromTheOrderTheFirstOneMade() throws Exception {
        long a = core.queue().insert(0, "http://h/a.mp3", "");
        long b = core.queue().insert(a, "http://h/b.mp3", "");
        long c = core.queue().insert(b, "http://h/c.mp3", "");
        core.queue().insert(c, "http://h/d.mp3", "");
        OpenHomePlayer other = playerWithFailingDevice();
        try {
            other.pause();
            await(() -> other.getItems().size() == 4);

            gate = new java.util.concurrent.CountDownLatch(1); // the first drag is still on its way when the second one is posted
            assertTrue(other.moveInPlayOrder(3, 0)); // d a b c
            assertTrue(other.moveInPlayOrder(3, 1)); // d c a b (the screen's order after the first drag)
            gate.countDown();

            await(() -> core.queue().snapshot().get(0).getUri().equals("http://h/d.mp3")
                    && core.queue().snapshot().get(1).getUri().equals("http://h/c.mp3"));
            assertEquals("http://h/a.mp3", core.queue().snapshot().get(2).getUri());
            assertEquals("http://h/b.mp3", core.queue().snapshot().get(3).getUri());
        } finally {
            gate = null;
            other.stopWorkers();
        }
    }

    @Test
    public void aFailedAppendOfTheNextPickedFileIsTriedAgainOnTheNextPoll() throws Exception {
        OpenHomePlayer other = playerWithFailingDevice();
        try {
            other.setItems(pickFiles(other, 4, true));
            await(() -> core.queue().size() == 2);

            failingAppend.set(true);
            core.setCurrentId(core.queue().snapshot().get(1).getId()); // the device moves on: the third file has to be given to it
            other.pause();
            Thread.sleep(300);
            assertEquals(2, core.queue().size());

            failingAppend.set(false);
            other.pause();
            await(() -> core.queue().size() == 3);
            assertEquals("http://share/2", core.queue().snapshot().get(2).getUri());
        } finally {
            other.stopWorkers();
        }
    }

    @Test
    public void anAlbumArtAddressIsNotFetchedAgainAndAgainWhenItDoesNotAnswer() {
        assertTrue(player.artMayBeFetched("http://h/a.jpg"));
        assertFalse(player.artMayBeFetched("http://h/a.jpg")); // the screen asks for the icon again at once
        assertTrue(player.artMayBeFetched("http://h/b.jpg"));
    }

    @Test
    public void onlyAFewAlbumArtAttemptsAreRemembered() {
        for (int i = 0; i < 100; i++) {
            assertTrue(player.artMayBeFetched("http://h/" + i + ".jpg"));
        }
        assertTrue(player.artMayBeFetched("http://h/0.jpg")); // forgotten long ago
        assertFalse(player.artMayBeFetched("http://h/99.jpg"));
    }
}
