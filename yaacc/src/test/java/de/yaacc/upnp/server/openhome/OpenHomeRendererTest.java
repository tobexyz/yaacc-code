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
import java.util.Collections;
import java.util.List;

import de.yaacc.openhome.OhIds;
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
        /** When set, every call must come from this thread (the player's thread). */
        volatile Thread requiredThread;

        private void check() {
            Thread required = requiredThread;
            if (required != null && Thread.currentThread() != required) {
                throw new IllegalStateException("player used on " + Thread.currentThread().getName());
            }
        }

        @Override
        public boolean isPlaying() {
            check();
            return super.isPlaying();
        }

        @Override
        public boolean isPaused() {
            check();
            return super.isPaused();
        }

        @Override
        public int getCurrentItemIndex() {
            check();
            return super.getCurrentItemIndex();
        }

        @Override
        public List<PlayableItem> getItems() {
            check();
            return super.getItems();
        }

        @Override
        public PlayMode getPlayMode() {
            check();
            return super.getPlayMode();
        }

        @Override
        public void setPlayMode(PlayMode mode) {
            check();
            super.setPlayMode(mode);
        }

        @Override
        public void insertItems(int index, List<PlayableItem> playableItems) {
            check();
            super.insertItems(index, playableItems);
        }

        @Override
        public boolean removeItem(int itemIndex, boolean alsoPlayed) {
            check();
            return super.removeItem(itemIndex, alsoPlayed);
        }

        @Override
        public void playItemAt(int index) {
            check();
            super.playItemAt(index);
        }

        FakePlayer(UpnpClient client) {
            super(client);
        }

        @Override
        protected PlayMode readDefaultPlayMode() {
            return PlayMode.of(PlayMode.RepeatMode.OFF, false);
        }

        @Override
        public String getDuration() {
            check();
            return "";
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
            check();
            return position;
        }

        @Override
        public void seekTo(long millisecondsFromStart) {
            check();
            calls.add("seek " + millisecondsFromStart);
            position = millisecondsFromStart;
        }

        @Override
        public void play() {
            check();
            calls.add("play");
            setPaused(false);
            setPlaying(true);
        }

        @Override
        public void pause() {
            check();
            calls.add("pause");
            setPaused(true);
        }

        @Override
        public void stop() {
            check();
            calls.add("stop");
            setPaused(false);
            setPlaying(false);
        }

        @Override
        public void exit() {
            check();
            calls.add("exit");
            alive = false;
        }

        @Override
        public void next() {
            check();
            calls.add("next");
            setCurrentIndex(Math.min(getCurrentItemIndex() + 1, getItems().size() - 1));
        }

        @Override
        public void previous() {
            check();
            calls.add("previous");
            setCurrentIndex(Math.max(getCurrentItemIndex() - 1, 0));
        }
    }

    /** Everything runs at once on the calling thread, so a test sees the result of a command right away. */
    private static class InlineThreads implements OpenHomeRenderer.Threads {
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

            @Override
            public String queueUriOf(PlayableItem item) {
                String title = item.getTitle();
                return title != null && title.startsWith("http") ? title : null;
            }

            @Override
            public String queueMetadataOf(PlayableItem item) {
                return "<meta " + item.getTitle() + "/>";
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


    // ---- edits at the player of this device (the playlist of the local music player)

    private static PlayableItem trackTitled(String title) {
        PlayableItem item = new PlayableItem();
        item.setTitle(title);
        return item;
    }

    private List<String> queueUris() {
        List<String> uris = new ArrayList<>();
        for (OhQueue.Entry entry : renderer.core().queue().snapshot()) {
            uris.add(entry.getUri());
        }
        return uris;
    }

    @Test
    public void aTrackDeletedAtThePlayerLeavesTheQueueAndTheControlPointsAreTold() {
        long a = add(0, "http://h/a.mp3");
        long b = add(a, "http://h/b.mp3");
        add(b, "http://h/c.mp3");
        renderer.play();

        assertEquals(true, player().removeItem(2));
        renderer.refresh();

        assertEquals(Arrays.asList("http://h/a.mp3", "http://h/b.mp3"), queueUris());
        assertEquals(OhIds.encodeIdArray(Arrays.asList(a, b)), playlist("IdArray"));
        // a later sync does not bring the track back
        renderer.seekToTrack(b);
        assertEquals(Arrays.asList("http://h/a.mp3", "http://h/b.mp3"), titles(player()));
        assertEquals(Arrays.asList("http://h/a.mp3", "http://h/b.mp3"), queueUris());
    }

    @Test
    public void aTrackMovedAtThePlayerMovesInTheQueueAndKeepsItsId() {
        long a = add(0, "http://h/a.mp3");
        long b = add(a, "http://h/b.mp3");
        long c = add(b, "http://h/c.mp3");
        renderer.play();

        assertEquals(true, player().moveInPlayOrder(2, 1));
        renderer.refresh();

        assertEquals(Arrays.asList(a, c, b), renderer.core().queue().idArray());
        assertEquals(OhIds.encodeIdArray(Arrays.asList(a, c, b)), playlist("IdArray"));
        assertEquals(Arrays.asList("http://h/a.mp3", "http://h/c.mp3", "http://h/b.mp3"), titles(player()));
    }

    @Test
    public void aTrackAddedAtThePlayerEntersTheQueueBehindItsPredecessor() {
        long a = add(0, "http://h/a.mp3");
        add(a, "http://h/b.mp3");
        renderer.play();

        player().insertItems(1, Collections.singletonList(trackTitled("http://h/x.mp3")));
        renderer.refresh();

        assertEquals(Arrays.asList("http://h/a.mp3", "http://h/x.mp3", "http://h/b.mp3"), queueUris());
        assertEquals(OhIds.encodeIdArray(renderer.core().queue().idArray()), playlist("IdArray"));
        assertEquals(Arrays.asList("http://h/a.mp3", "http://h/x.mp3", "http://h/b.mp3"), titles(player()));
        assertEquals("<meta http://h/x.mp3/>", renderer.core().queue().read(renderer.core().queue().idAt(1)).getMetadata());
    }

    @Test
    public void aTrackTheQueueCanNotHoldIsTakenOutOfThePlayerAgain() {
        long a = add(0, "http://h/a.mp3");
        add(a, "http://h/b.mp3");
        renderer.play();

        player().addItems(Collections.singletonList(trackTitled("content://local/file")));
        renderer.refresh();

        assertEquals(Arrays.asList("http://h/a.mp3", "http://h/b.mp3"), queueUris());
        assertEquals(Arrays.asList("http://h/a.mp3", "http://h/b.mp3"), titles(player()));
    }

    @Test
    public void aTrackAControlPointAddedIsNotTakenForAnEditOfThePlayer() {
        long a = add(0, "http://h/a.mp3");
        renderer.play();

        // inserted in the queue, the worker has not synced yet
        renderer.core().queue().insert(a, "http://h/b.mp3", "");
        renderer.refresh();

        assertEquals(Arrays.asList("http://h/a.mp3", "http://h/b.mp3"), queueUris());
    }

    @Test
    public void anEditAtThePlayerIsTakenOverBeforeAControlPointEditIsSynced() {
        long a = add(0, "http://h/a.mp3");
        long b = add(a, "http://h/b.mp3");
        long c = add(b, "http://h/c.mp3");
        renderer.seekToTrack(c);

        assertEquals(true, player().removeItem(0, true)); // the user deletes a at the phone: the player holds b, c ...
        renderer.core().queue().delete(b);                // ... and a control point deletes b before the poll looked
        renderer.trackDeleted(1, b);

        // by place the control point's delete would have taken the wrong track (index 1 of the player is c)
        assertEquals(Collections.singletonList("http://h/c.mp3"), queueUris());
        assertEquals(Collections.singletonList("http://h/c.mp3"), titles(player()));
    }

    @Test
    public void aTrackAddedAtThePlayerBehindATrackAControlPointDeletedKeepsItsPlace() {
        long a = add(0, "http://h/a.mp3");
        long k = add(a, "http://h/k.mp3");
        renderer.play();

        player().addItems(Collections.singletonList(trackTitled("http://h/x.mp3"))); // the user adds x behind k ...
        renderer.core().queue().delete(k);                                           // ... and a control point deletes k
        renderer.trackDeleted(1, k);

        assertEquals(Arrays.asList("http://h/a.mp3", "http://h/x.mp3"), queueUris());
        assertEquals(Arrays.asList("http://h/a.mp3", "http://h/x.mp3"), titles(player()));
    }

    @Test
    public void theRenderersKnowledgeOfTheItemsFollowsTheQueueAlsoForIdsAbove127() {
        long last = 0;
        for (int i = 0; i < 130; i++) {
            last = add(last, "http://h/" + i + ".mp3");
        }
        renderer.play();
        assertEquals(130, renderer.trackedItemsForTest());

        renderer.core().queue().delete(last); // id 130
        renderer.trackDeleted(129, last);

        assertEquals(129, renderer.trackedItemsForTest());
        assertEquals(129, player().getItems().size());
    }

    @Test
    public void theSameItemTwiceInThePlayerLeavesTheQueueOnceAndTheRepeatIsTakenOut() {
        long a = add(0, "http://h/a.mp3");
        add(a, "http://h/b.mp3");
        renderer.play();

        PlayableItem x = trackTitled("http://h/x.mp3");
        player().addItems(Collections.singletonList(x));
        player().addItems(Collections.singletonList(x)); // the very same object again
        renderer.refresh();

        assertEquals(Arrays.asList("http://h/a.mp3", "http://h/b.mp3", "http://h/x.mp3"), queueUris());
        assertEquals(Arrays.asList("http://h/a.mp3", "http://h/b.mp3", "http://h/x.mp3"), titles(player()));
    }

    @Test
    public void aKnownItemTwiceInThePlayerCountsOnceAndTheRepeatIsTakenOut() {
        long a = add(0, "http://h/a.mp3");
        add(a, "http://h/b.mp3");
        renderer.play();

        PlayableItem known = player().getItems().get(0);
        player().addItems(Collections.singletonList(known)); // a again, the very same object
        renderer.refresh();

        assertEquals(Arrays.asList("http://h/a.mp3", "http://h/b.mp3"), queueUris());
        assertEquals(Arrays.asList("http://h/a.mp3", "http://h/b.mp3"), titles(player()));
    }

    @Test
    public void theLifeCycleOfTheRendererRunsEvenWhenThreadsDropWork() {
        List<String> ran = new ArrayList<>();
        OpenHomeRenderer plain = newRenderer(new InlineThreads() {
            @Override
            public void work(Runnable work) {
                // flooded: dropped
            }

            @Override
            public void lifecycle(Runnable work) {
                ran.add("lifecycle");
                work.run();
            }
        });

        plain.start();
        plain.shutdown();

        assertEquals(Arrays.asList("lifecycle", "lifecycle"), ran);
    }

    @Test
    public void aTrackAControlPointDeletedIsNotBroughtBackByTheEditCheck() {
        long a = add(0, "http://h/a.mp3");
        long b = add(a, "http://h/b.mp3");
        renderer.play();

        renderer.core().queue().delete(b); // not synced yet
        renderer.refresh();

        assertEquals(Collections.singletonList("http://h/a.mp3"), queueUris());
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

    @Test
    public void aDeleteAllOfADeletedPlayingTrackLeavesNoStaleStartTrack() {
        long a = add(0, "http://h/a.mp3");
        renderer.play();
        renderer.core().queue().delete(a);
        renderer.trackDeleted(0, a); // no player any more, nothing left to start with

        renderer.core().queue().deleteAll();
        renderer.queueCleared();
        long b = add(0, "http://h/b.mp3");

        assertEquals("Stopped", playlist("TransportState"));
        renderer.play();
        assertEquals(String.valueOf(b), playlist("Id"));
    }

    @Test
    public void thePlayerIsOnlyTouchedOnItsOwnThread() throws Exception {
        java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "test-worker"));
        java.util.concurrent.ExecutorService playerThread = java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "test-player"));
        Thread playerOwner = playerThread.submit(Thread::currentThread).get();
        List<Throwable> failures = new java.util.concurrent.CopyOnWriteArrayList<>();
        OpenHomeRenderer.Host plain = host;
        host = new OpenHomeRenderer.Host() {
            @Override
            public AbstractPlayer createPlayer(List<PlayableItem> items) {
                FakePlayer fake = (FakePlayer) plain.createPlayer(items);
                fake.requiredThread = playerOwner; // from now on only the player's thread may use it
                return fake;
            }

            @Override
            public boolean isAlive(AbstractPlayer p) {
                return plain.isAlive(p);
            }

            @Override
            public PlayableItem toPlayableItem(String uri, String metadata) {
                return plain.toPlayableItem(uri, metadata);
            }

            @Override
            public String queueUriOf(PlayableItem item) {
                return plain.queueUriOf(item);
            }

            @Override
            public String queueMetadataOf(PlayableItem item) {
                return plain.queueMetadataOf(item);
            }
        };
        OpenHomeRenderer checked = newRenderer(new OpenHomeRenderer.Threads() {
            @Override
            public void work(Runnable work) {
                worker.execute(() -> {
                    try {
                        work.run();
                    } catch (Throwable t) {
                        failures.add(t);
                    }
                });
            }

            @Override
            public void runLater(Runnable runnable, long delayMillis) {
                work(runnable);
            }

            @Override
            public Runnable every(Runnable work, long periodMillis) {
                return () -> {
                };
            }

            @Override
            public <T> T onPlayer(java.util.concurrent.Callable<T> call) {
                return OhCall.runAndWait(playerThread, call, 5);
            }

            @Override
            public void close() {
            }
        });
        OhQueue q = checked.core().queue();
        long first = q.insert(0, "http://h/1.mp3", "");
        checked.trackInserted(0, q.read(first));
        checked.play();
        long second = q.insert(first, "http://h/2.mp3", "");
        checked.trackInserted(1, q.read(second));
        checked.setRepeat(true);
        checked.setShuffle(true);
        checked.seekSecondAbsolute(10);
        checked.seekSecondRelative(5);
        checked.next();
        checked.pause();
        checked.refresh();
        q.delete(first);
        checked.trackDeleted(0, first);
        q.deleteAll();
        checked.queueCleared();
        checked.refresh();
        java.util.concurrent.CountDownLatch drained = new java.util.concurrent.CountDownLatch(1);
        worker.execute(drained::countDown);
        assertEquals(true, drained.await(10, java.util.concurrent.TimeUnit.SECONDS));
        worker.shutdown();
        playerThread.shutdown();

        assertEquals(new ArrayList<Throwable>(), failures);
        assertEquals(1, created.size());
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
