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
    public void moveIsNotOfferedAndNothingIsLocked() {
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
}
