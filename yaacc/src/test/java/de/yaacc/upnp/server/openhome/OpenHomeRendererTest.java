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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.yaacc.openhome.OhQueue;
import de.yaacc.player.AbstractPlayer;
import de.yaacc.player.PlayMode;
import de.yaacc.player.PlayableItem;
import de.yaacc.upnp.UpnpClient;

/**
 * The OpenHome renderer coordinator against a player double: queue and player stay in step, the player's state reaches the
 * OpenHome services. Plain JVM.
 */
public class OpenHomeRendererTest {

    private static final class FakePlayer extends AbstractPlayer {
        final List<String> calls = new ArrayList<>();
        long position;
        boolean alive = true;

        FakePlayer(UpnpClient client) {
            super(client);
        }

        @Override
        protected PlayMode readDefaultPlayMode() {
            return PlayMode.of(PlayMode.RepeatMode.OFF, false);
        }

        @Override
        protected void showNotificationInternal() {
        }

        @Override
        protected int getNotificationId() {
            return 4713;
        }

        @Override
        protected void stopItem(PlayableItem playableItem) {
        }

        @Override
        protected Object loadItem(PlayableItem playableItem) {
            return playableItem;
        }

        @Override
        protected void startItem(PlayableItem playableItem, Object loadedItem, int index) {
        }

        @Override
        public long getCurrentPosition() {
            return position;
        }

        @Override
        public void seekTo(long millisecondsFromStart) {
            calls.add("seek " + millisecondsFromStart);
            position = millisecondsFromStart;
        }

        @Override
        public void play() {
            calls.add("play");
            setPaused(false);
            setPlaying(true);
        }

        @Override
        public void pause() {
            calls.add("pause");
            setPaused(true);
        }

        @Override
        public void stop() {
            calls.add("stop");
            setPaused(false);
            setPlaying(false);
        }

        @Override
        public void exit() {
            calls.add("exit");
            alive = false;
        }

        @Override
        public void next() {
            calls.add("next");
            setCurrentIndex(Math.min(getCurrentItemIndex() + 1, getItems().size() - 1));
        }

        @Override
        public void previous() {
            calls.add("previous");
            setCurrentIndex(Math.max(getCurrentItemIndex() - 1, 0));
        }
    }

    private final List<FakePlayer> created = new ArrayList<>();
    private int volumePercent = 40;
    private boolean muted;
    private OpenHomeRenderer renderer;
    private Context context;

    @Before
    public void setUp() {
        context = mock(Context.class);
        OpenHomeRenderer.Host host = new OpenHomeRenderer.Host() {
            @Override
            public AbstractPlayer createPlayer(List<PlayableItem> items) {
                UpnpClient client = mock(UpnpClient.class);
                when(client.getContext()).thenReturn(context);
                FakePlayer player = new FakePlayer(client);
                player.setItems(items.toArray(new PlayableItem[0]));
                created.add(player);
                return player;
            }

            @Override
            public boolean isAlive(AbstractPlayer player) {
                return ((FakePlayer) player).alive;
            }

            @Override
            public PlayableItem toPlayableItem(String uri, String metadata) {
                PlayableItem item = new PlayableItem();
                item.setTitle(uri);
                return item;
            }
        };
        OpenHomeRenderer.Volume volume = new OpenHomeRenderer.Volume() {
            @Override
            public int percent() {
                return volumePercent;
            }

            @Override
            public boolean muted() {
                return muted;
            }

            @Override
            public void setPercent(int percent) {
                volumePercent = percent;
            }

            @Override
            public void setMuted(boolean mute) {
                muted = mute;
            }
        };
        renderer = new OpenHomeRenderer(host, volume, (runnable, delay) -> runnable.run(),
                new OhRendererCore.Details("m", "https://example.org", "model", "room", "name"), "http-get:*:audio/mpeg:*");
    }

    private long add(long afterId, String uri) {
        OhQueue queue = renderer.core().queue();
        long id = queue.insert(afterId, uri, "<meta " + uri + "/>");
        renderer.trackInserted(queue.indexOf(id), queue.read(id));
        return id;
    }

    private String playlist(String variable) {
        return renderer.core().stateValue("playlist", variable);
    }

    private FakePlayer player() {
        assertFalse(created.isEmpty());
        return created.get(created.size() - 1);
    }

    private static List<String> titles(FakePlayer player) {
        List<String> titles = new ArrayList<>();
        for (PlayableItem item : player.getItems()) {
            titles.add(item.getTitle());
        }
        return titles;
    }

    // ---- start

    @Test
    public void playWithAnEmptyQueueDoesNothing() {
        renderer.play();

        assertEquals(0, created.size());
        assertEquals("Stopped", playlist("TransportState"));
        assertEquals("0", playlist("Id"));
    }

    @Test
    public void playCreatesThePlayerFromTheQueueAndReportsTheFirstTrack() {
        long a = add(0, "http://h/a.mp3");
        add(a, "http://h/b.mp3");

        renderer.play();

        assertEquals(1, created.size());
        assertEquals(Arrays.asList("http://h/a.mp3", "http://h/b.mp3"), titles(player()));
        assertEquals("Playing", playlist("TransportState"));
        assertEquals(String.valueOf(a), playlist("Id"));
        assertEquals("http://h/a.mp3", renderer.core().stateValue("info", "Uri"));
        assertEquals("<meta http://h/a.mp3/>", renderer.core().stateValue("info", "Metadata"));
    }

    @Test
    public void seekToATrackStartsThePlayerThere() {
        long a = add(0, "http://h/a.mp3");
        long b = add(a, "http://h/b.mp3");

        renderer.seekToTrack(b);

        assertEquals(1, player().getCurrentItemIndex());
        assertEquals(String.valueOf(b), playlist("Id"));
        assertEquals("Playing", playlist("TransportState"));
    }

    @Test
    public void seekToAnUnknownTrackDoesNothing() {
        add(0, "http://h/a.mp3");

        renderer.seekToTrack(4711);

        assertEquals(0, created.size());
    }

    // ---- transport

    @Test
    public void pauseAndPlayAgainResume() {
        add(0, "http://h/a.mp3");
        renderer.play();

        renderer.pause();
        assertEquals("Paused", playlist("TransportState"));

        renderer.play();
        assertEquals("Playing", playlist("TransportState"));
        assertEquals(1, created.size());
    }

    @Test
    public void stopReportsStoppedAndKeepsThePlayerForTheNextPlay() {
        long a = add(0, "http://h/a.mp3");
        renderer.play();

        renderer.stop();

        assertEquals("Stopped", playlist("TransportState"));
        renderer.play();
        assertEquals(1, created.size());
        assertEquals("Playing", playlist("TransportState"));
        assertEquals(String.valueOf(a), playlist("Id"));
    }

    @Test
    public void aPlayerThatWasShutDownIsReportedAsStoppedAndPlayCreatesANewOne() {
        add(0, "http://h/a.mp3");
        renderer.play();

        player().alive = false;
        renderer.refresh();
        assertEquals("Stopped", playlist("TransportState"));

        renderer.play();
        assertEquals(2, created.size());
        assertEquals("Playing", playlist("TransportState"));
    }

    @Test
    public void nextMovesToTheNextTrackAndTheIdFollows() {
        long a = add(0, "http://h/a.mp3");
        long b = add(a, "http://h/b.mp3");
        renderer.play();

        renderer.next();

        assertEquals(String.valueOf(b), playlist("Id"));
        renderer.previous();
        assertEquals(String.valueOf(a), playlist("Id"));
    }

    @Test
    public void whenTheTrackChangesByItselfTheNextRefreshReportsIt() {
        long a = add(0, "http://h/a.mp3");
        long b = add(a, "http://h/b.mp3");
        renderer.play();

        player().next(); // the player went on, nobody asked the renderer
        renderer.refresh();

        assertEquals(String.valueOf(b), playlist("Id"));
        assertEquals("http://h/b.mp3", renderer.core().stateValue("info", "Uri"));
    }

    @Test
    public void seekMovesThePositionInSeconds() {
        add(0, "http://h/a.mp3");
        renderer.play();

        renderer.seekSecondAbsolute(90);
        assertEquals(90_000L, player().position);
        renderer.seekSecondRelative(-30);
        assertEquals(60_000L, player().position);
        renderer.seekSecondRelative(-600);
        assertEquals(0L, player().position);
    }

    @Test
    public void theSecondsComeFromThePlayer() {
        add(0, "http://h/a.mp3");
        renderer.play();

        player().position = 12_500;
        renderer.refresh();

        assertEquals("12", renderer.core().stateValue("time", "Seconds"));
    }

    // ---- edits while the player exists

    @Test
    public void aTrackInsertedLaterReachesThePlayerAtItsPlace() {
        long a = add(0, "http://h/a.mp3");
        long b = add(a, "http://h/b.mp3");
        renderer.play();

        add(a, "http://h/x.mp3");

        assertEquals(Arrays.asList("http://h/a.mp3", "http://h/x.mp3", "http://h/b.mp3"), titles(player()));
        assertEquals(0, player().getCurrentItemIndex());
        assertEquals("Playing", playlist("TransportState"));
        assertEquals(String.valueOf(a), playlist("Id"));
        assertEquals(1, created.size());
        assertNotNull(b);
    }

    @Test
    public void aTrackInsertedAtTheStartKeepsTheCurrentTrack() {
        long a = add(0, "http://h/a.mp3");
        renderer.play();

        add(0, "http://h/first.mp3");
        renderer.refresh();

        assertEquals(Arrays.asList("http://h/first.mp3", "http://h/a.mp3"), titles(player()));
        assertEquals(String.valueOf(a), playlist("Id"));
    }

    @Test
    public void deletingATrackAheadRemovesItFromThePlayer() {
        long a = add(0, "http://h/a.mp3");
        long b = add(a, "http://h/b.mp3");
        add(b, "http://h/c.mp3");
        renderer.play();

        renderer.core().queue().delete(b);
        renderer.trackDeleted(1, b);

        assertEquals(Arrays.asList("http://h/a.mp3", "http://h/c.mp3"), titles(player()));
        assertEquals("Playing", playlist("TransportState"));
    }

    @Test
    public void deletingAPlayedTrackKeepsThePlayerPlaying() {
        long a = add(0, "http://h/a.mp3");
        long b = add(a, "http://h/b.mp3");
        renderer.seekToTrack(b);

        renderer.core().queue().delete(a);
        renderer.trackDeleted(0, a);

        assertEquals(Arrays.asList("http://h/b.mp3"), titles(player()));
        assertEquals(0, player().getCurrentItemIndex());
        assertEquals(String.valueOf(b), playlist("Id"));
        assertEquals("Playing", playlist("TransportState"));
    }

    @Test
    public void deletingTheTrackThatPlaysStopsAndTheNextPlayStartsWithTheFollowingTrack() {
        long a = add(0, "http://h/a.mp3");
        long b = add(a, "http://h/b.mp3");
        renderer.play();

        renderer.core().queue().delete(a);
        renderer.trackDeleted(0, a);

        assertEquals("Stopped", playlist("TransportState"));
        assertEquals(String.valueOf(b), playlist("Id"));
        assertFalse(player().alive);

        renderer.play();
        assertEquals(2, created.size());
        assertEquals(Arrays.asList("http://h/b.mp3"), titles(player()));
        assertEquals(String.valueOf(b), playlist("Id"));
    }

    @Test
    public void clearingTheQueueStopsAndForgetsThePlayer() {
        add(0, "http://h/a.mp3");
        renderer.play();

        renderer.core().queue().deleteAll();
        renderer.queueCleared();

        assertFalse(player().alive);
        assertEquals("Stopped", playlist("TransportState"));
        assertEquals("0", playlist("Id"));
        assertNull(renderer.playerForTest());
    }

    // ---- modes and volume

    @Test
    public void repeatAndShuffleReachThePlayerAndSurviveItsCreation() {
        add(0, "http://h/a.mp3");
        renderer.setRepeat(true);
        renderer.setShuffle(true);

        renderer.play();

        assertEquals(PlayMode.of(PlayMode.RepeatMode.ALL, true), player().getPlayMode());

        renderer.setRepeat(false);
        assertEquals(PlayMode.of(PlayMode.RepeatMode.OFF, true), player().getPlayMode());
    }

    @Test
    public void aModeChangedAtThePlayerIsReportedToTheControllers() {
        add(0, "http://h/a.mp3");
        renderer.play();

        player().setPlayMode(PlayMode.of(PlayMode.RepeatMode.ALL, true));
        renderer.refresh();

        assertEquals("true", playlist("Repeat"));
        assertEquals("true", playlist("Shuffle"));
    }

    @Test
    public void repeatOneCountsAsRepeat() {
        add(0, "http://h/a.mp3");
        renderer.play();

        player().setPlayMode(PlayMode.of(PlayMode.RepeatMode.ONE, false));
        renderer.refresh();

        assertEquals("true", playlist("Repeat"));
    }

    @Test
    public void volumeAndMuteGoToTheSystemAndBack() {
        renderer.setVolume(75);
        renderer.setMute(true);

        assertEquals(75, volumePercent);
        assertEquals(true, muted);
        assertEquals("75", renderer.core().stateValue("volume", "Volume"));
        assertEquals("true", renderer.core().stateValue("volume", "Mute"));

        volumePercent = 20; // the hardware keys
        renderer.refresh();
        assertEquals("20", renderer.core().stateValue("volume", "Volume"));
    }

    @Test
    public void aVolumeOutsideTheRangeIsClamped() {
        renderer.setVolume(250);
        assertEquals(100, volumePercent);
        renderer.setVolume(-3);
        assertEquals(0, volumePercent);
    }

    // ---- helpers

    @Test
    public void theDurationTextOfThePlayerBecomesSeconds() {
        assertEquals(3723, OpenHomeRenderer.parseSeconds("1:02:03"));
        assertEquals(125, OpenHomeRenderer.parseSeconds("02:05"));
        assertEquals(0, OpenHomeRenderer.parseSeconds(""));
        assertEquals(0, OpenHomeRenderer.parseSeconds(null));
        assertEquals(0, OpenHomeRenderer.parseSeconds("abc"));
        assertEquals(0, OpenHomeRenderer.parseSeconds("1:2:3:4"));
        assertEquals(0, OpenHomeRenderer.parseSeconds("-1:00"));
    }
}
