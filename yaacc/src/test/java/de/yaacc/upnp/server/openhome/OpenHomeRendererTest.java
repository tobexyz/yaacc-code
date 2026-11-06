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

    /** Everything runs at once on the calling thread, so a test sees the result of a command right away. */
    private static final class InlineThreads implements OpenHomeRenderer.Threads {
        @Override
        public void work(Runnable work) {
            work.run();
        }

        @Override
        public void runLater(Runnable runnable, long delayMillis) {
            runnable.run();
        }

        @Override
        public Runnable every(Runnable work, long periodMillis) {
            return () -> {
            };
        }

        @Override
        public <T> T onPlayer(java.util.concurrent.Callable<T> call) {
            try {
                return call.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void close() {
        }
    }

    private final List<FakePlayer> created = new ArrayList<>();
    private int volumePercent = 40;
    private boolean muted;
    private OpenHomeRenderer renderer;
    private Context context;

    private OpenHomeRenderer.Host host;
    private OpenHomeRenderer.Volume volume;

    @Before
    public void setUp() {
        context = mock(Context.class);
        host = new OpenHomeRenderer.Host() {
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
        volume = new OpenHomeRenderer.Volume() {
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
        renderer = newRenderer(new InlineThreads());
    }

    private OpenHomeRenderer newRenderer(OpenHomeRenderer.Threads threads) {
        return new OpenHomeRenderer(host, volume, threads,
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

    // ---- queue edits that arrive in any order, twice, or while a play starts

    @Test
    public void notificationsInTheWrongOrderStillGiveThePlayerTheOrderOfTheQueue() {
        long a = add(0, "http://h/a.mp3");
        renderer.play();
        OhQueue queue = renderer.core().queue();
        long x = queue.insert(a, "http://h/x.mp3", "");
        long y = queue.insert(a, "http://h/y.mp3", "");

        renderer.trackInserted(queue.indexOf(x), queue.read(x)); // the later edit is reported first, and the earlier one after it
        renderer.trackInserted(queue.indexOf(y), queue.read(y));

        assertEquals(Arrays.asList("http://h/a.mp3", "http://h/y.mp3", "http://h/x.mp3"), titles(player()));
    }

    @Test
    public void aTrackThatIsAlreadyInThePlayerIsNotInsertedASecondTime() {
        OhQueue queue = renderer.core().queue();
        long a = queue.insert(0, "http://h/a.mp3", "");
        renderer.play(); // creates the player from the queue, which already has a

        renderer.trackInserted(0, queue.read(a)); // the notification of the same insert arrives now
        renderer.trackInserted(0, queue.read(a));

        assertEquals(Arrays.asList("http://h/a.mp3"), titles(player()));
    }

    @Test
    public void parallelInsertsLeaveThePlayerInTheOrderOfTheQueue() throws Exception {
        java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        OpenHomeRenderer threaded = newRenderer(new OpenHomeRenderer.Threads() {
            @Override
            public void work(Runnable work) {
                worker.execute(work);
            }

            @Override
            public void runLater(Runnable runnable, long delayMillis) {
                worker.execute(runnable);
            }

            @Override
            public Runnable every(Runnable work, long periodMillis) {
                return () -> {
                };
            }

            @Override
            public <T> T onPlayer(java.util.concurrent.Callable<T> call) {
                try {
                    return call.call();
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public void close() {
                worker.shutdown();
            }
        });
        OhQueue queue = threaded.core().queue();
        long first = queue.insert(0, "http://h/0.mp3", "");
        threaded.trackInserted(0, queue.read(first));
        threaded.play();

        java.util.concurrent.ExecutorService clients = java.util.concurrent.Executors.newFixedThreadPool(8);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<?>> done = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            final String uri = "http://h/" + i + ".mp3";
            final long after = i % 3 == 0 ? 0 : first;
            done.add(clients.submit(() -> {
                go.await();
                long id = queue.insert(after, uri, "");
                threaded.trackInserted(queue.indexOf(id), queue.read(id));
                return null;
            }));
        }
        go.countDown();
        for (java.util.concurrent.Future<?> f : done) {
            f.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        clients.shutdown();
        java.util.concurrent.CountDownLatch drained = new java.util.concurrent.CountDownLatch(1);
        worker.execute(drained::countDown);
        assertEquals(true, drained.await(10, java.util.concurrent.TimeUnit.SECONDS));

        List<String> expected = new ArrayList<>();
        for (OhQueue.Entry entry : queue.snapshot()) {
            expected.add(entry.getUri());
        }
        assertEquals(41, expected.size());
        assertEquals(expected, titles((FakePlayer) threaded.playerForTest()));
        worker.shutdown();
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
