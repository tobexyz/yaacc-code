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
import de.yaacc.player.LocalMediaSessionPlayer;
import de.yaacc.player.PlayMode;
import de.yaacc.player.PlayableItem;
import de.yaacc.upnp.UpnpClient;

/**
 * Review W1 of Group 11A: the OpenHome renderer keeps one id per entry of the queue, in the same order as the list of the
 * player, and plays a track with {@code playItemAt(playerIds.indexOf(id))}. A playlist file in the queue (a control point
 * queues an {@code .m3u} that a server lists as audio) must therefore stay in the list of the music player (the skip of an
 * unreadable track copes with it), or every index behind it points at the wrong track. The player here is the real local
 * music player, which is the one that filters playlist files.
 */
public class OpenHomeRendererPlaylistFileTest {

    /** The real local music player without ExoPlayer: what a start does is recorded. */
    private static final class LocalPlayer extends LocalMediaSessionPlayer {
        boolean alive = true;

        LocalPlayer(UpnpClient client) {
            super(client, "Music", "M");
        }

        @Override
        protected PlayMode readDefaultPlayMode() {
            return PlayMode.of(PlayMode.RepeatMode.OFF, false);
        }

        @Override
        public void play() {
            setPaused(false);
            setPlaying(true);
        }

        @Override
        public void exit() {
            alive = false;
        }
    }

    private final List<LocalPlayer> created = new ArrayList<>();
    private OpenHomeRenderer renderer;

    @Before
    public void setUp() {
        final Context context = mock(Context.class);
        when(context.getSystemService(Context.NOTIFICATION_SERVICE)).thenReturn(mock(android.app.NotificationManager.class));
        when(context.getApplicationContext()).thenReturn(mock(de.yaacc.Yaacc.class));
        OpenHomeRenderer.Host host = new OpenHomeRenderer.Host() {
            @Override
            public AbstractPlayer createPlayer(List<PlayableItem> items) {
                UpnpClient client = mock(UpnpClient.class);
                when(client.getContext()).thenReturn(context);
                LocalPlayer player = new LocalPlayer(client);
                player.setItems(items.toArray(new PlayableItem[0]));
                created.add(player);
                return player;
            }

            @Override
            public boolean isAlive(AbstractPlayer player) {
                return ((LocalPlayer) player).alive;
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
        OpenHomeRenderer.Volume volume = new OpenHomeRenderer.Volume() {
            @Override
            public int percent() {
                return 40;
            }

            @Override
            public boolean muted() {
                return false;
            }

            @Override
            public void setPercent(int percent) {
            }

            @Override
            public void setMuted(boolean mute) {
            }
        };
        renderer = new OpenHomeRenderer(host, volume, new OpenHomeRenderer.Threads() {
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
        }, new OhRendererCore.Details("m", "https://example.org", "model", "room", "name"), "http-get:*:audio/mpeg:*");
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

    private LocalPlayer player() {
        assertFalse(created.isEmpty());
        return created.get(created.size() - 1);
    }

    private List<String> titles() {
        List<String> titles = new ArrayList<>();
        for (PlayableItem item : player().getItems()) {
            titles.add(item.getTitle());
        }
        return titles;
    }

    @Test
    public void aPlaylistFileInTheQueueStaysInTheListOfThePlayerSoTheIndexesMatchTheIds() {
        long a = add(0, "http://h/a.mp3");
        long list = add(a, "http://h/x.m3u");
        long b = add(list, "http://h/b.mp3");

        renderer.seekToTrack(b);

        assertEquals(Arrays.asList("http://h/a.mp3", "http://h/x.m3u", "http://h/b.mp3"), titles());
        assertEquals("b is the third entry of the player", 2, player().getCurrentItemIndex());
        assertEquals("and is reported with its own id", String.valueOf(b), playlist("Id"));
    }

    @Test
    public void everyTrackBehindAPlaylistFileIsPlayedAtItsOwnIndex() {
        long a = add(0, "http://h/a.mp3");
        long list = add(a, "http://h/x.m3u");
        long b = add(list, "http://h/b.mp3");
        long c = add(b, "http://h/c.mp3");
        renderer.play();

        renderer.seekToTrack(c);
        assertEquals(3, player().getCurrentItemIndex());
        assertEquals(String.valueOf(c), playlist("Id"));

        renderer.seekToTrack(list);
        assertEquals(1, player().getCurrentItemIndex());
        assertEquals(String.valueOf(list), playlist("Id"));

        renderer.seekToTrack(a);
        assertEquals(0, player().getCurrentItemIndex());
        assertEquals(String.valueOf(a), playlist("Id"));
    }

    @Test
    public void aPlaylistFileQueuedWhilePlayingEntersTheListAtItsPlaceAndKeepsTheLaterIndexes() {
        long a = add(0, "http://h/a.mp3");
        long b = add(a, "http://h/b.mp3");
        renderer.play();

        long list = add(a, "http://h/x.m3u");
        renderer.refresh();
        renderer.seekToTrack(b);

        assertEquals(Arrays.asList("http://h/a.mp3", "http://h/x.m3u", "http://h/b.mp3"), titles());
        assertEquals(2, player().getCurrentItemIndex());
        assertEquals(String.valueOf(b), playlist("Id"));
        renderer.seekToTrack(list);
        assertEquals(1, player().getCurrentItemIndex());
    }

    @Test
    public void aQueueOfPlaylistFilesOnlyStillMakesAPlayer() {
        add(0, "http://h/x.m3u");

        renderer.play();

        assertEquals("the unreadable track is skipped by the player, not hidden from the renderer", 1, created.size());
    }
}
