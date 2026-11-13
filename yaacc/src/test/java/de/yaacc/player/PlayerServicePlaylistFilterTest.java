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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.fourthline.cling.model.meta.Device;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import de.yaacc.multiroom.SnapcastGroupDevice;
import de.yaacc.multiroom.control.SnapcastClient;
import de.yaacc.multiroom.control.SnapcastGroup;
import de.yaacc.multiroom.control.SnapcastVolume;
import de.yaacc.multiroom.discovery.SnapcastEndpoint;
import de.yaacc.share.PickedQueue;
import de.yaacc.share.QueueItems;
import de.yaacc.upnp.UpnpClient;

/**
 * 11.11: playlist files (.m3u and the like) are left out when the queue of a player that reads and decodes on this phone
 * is built, so an album with one plays the other tracks; when nothing is left the user hears "nothing playable" and no
 * player is made.
 */
public class PlayerServicePlaylistFilterTest {

    private PlayerService service;
    private UpnpClient client;
    private SnapcastPlayer created;
    private int madePlayers;

    private static SnapcastGroupDevice group(String id) {
        SnapcastClient member = new SnapcastClient("m-" + id, "member", "", true, new SnapcastVolume(50, false), 0);
        SnapcastGroup group = new SnapcastGroup(id, "Group", false, "Default", Collections.singletonList(member));
        return SnapcastGroupDevice.of(SnapcastEndpoint.parse("192.168.1.5:1705").toKey(), group, false,
                new SnapcastGroupDevice.Labels("Snapcast group", "offline"));
    }

    private static PlayableItem item(String mime, String title) {
        PlayableItem item = mock(PlayableItem.class);
        when(item.getMimeType()).thenReturn(mime);
        when(item.getTitle()).thenReturn(title);
        return item;
    }

    @Before
    public void setUp() {
        service = spy(new PlayerService());
        doNothing().when(service).notifyNotPlayableYet(any());
        doNothing().when(service).notifySnapcastAudioOnly(any());
        doNothing().when(service).notifyNothingPlayable(any());
        doNothing().when(service).addPlayer(any());
        client = mock(UpnpClient.class);
        doReturn(true).when(service).snapcastPlayEnabled(any());
        doAnswer(call -> {
            madePlayers++;
            created = mock(SnapcastPlayer.class);
            return created;
        }).when(service).newSnapcastPlayer(any(), any());
    }

    @Test
    public void anAlbumWithAPlaylistFilePlaysTheOtherTracksOnAGroup() {
        PlayableItem one = item("audio/mpeg", "One");
        PlayableItem list = item("audio/x-mpegurl", "By The Way");
        PlayableItem two = item("audio/flac", "Two");

        List<Player> players = service.createPlayerFor(client, Arrays.asList(one, list, two),
                Collections.<Device<?, ?, ?>>singletonList(group("g1")));

        assertEquals(1, players.size());
        verify(created).setItems(one, two);
        verify(service, never()).notifyNothingPlayable(any());
    }

    @Test
    public void aPlaylistFileThatIsOnlyKnownByItsTitleIsLeftOutToo() {
        PlayableItem one = item("audio/mpeg", "One");
        PlayableItem list = item("audio/mpeg", "By The Way.m3u");

        service.createPlayerFor(client, Arrays.asList(list, one), Collections.<Device<?, ?, ?>>singletonList(group("g1")));

        verify(created).setItems(one);
    }

    @Test
    public void hlsStaysInTheQueue() {
        PlayableItem radio = item("application/x-mpegURL", "Radio");
        PlayableItem two = item("audio/flac", "Two");

        service.createPlayerFor(client, Arrays.asList(radio, two), Collections.<Device<?, ?, ?>>singletonList(group("g1")));

        verify(created).setItems(radio, two);
    }

    @Test
    public void whenEverythingIsAPlaylistFileNoPlayerIsMadeAndTheUserIsToldOnce() {
        List<Device<?, ?, ?>> devices = Arrays.<Device<?, ?, ?>>asList(group("g1"), group("g2"));

        List<Player> players = service.createPlayerFor(client,
                Arrays.asList(item("audio/x-mpegurl", "A"), item("audio/x-scpls", "B")), devices);

        assertTrue(players.isEmpty());
        assertEquals(0, madePlayers);
        verify(service, times(1)).notifyNothingPlayable(client);
        verify(service, never()).addPlayer(any());
    }

    @Test
    public void everyGroupGetsTheFilteredListNotJustTheFirst() {
        PlayableItem one = item("audio/mpeg", "One");
        PlayableItem list = item("audio/x-mpegurl", "List");
        SnapcastPlayer[] seen = new SnapcastPlayer[2];
        int[] n = {0};
        doAnswer(call -> {
            seen[n[0]] = mock(SnapcastPlayer.class);
            return seen[n[0]++];
        }).when(service).newSnapcastPlayer(any(), any());

        service.createPlayerFor(client, Arrays.asList(list, one),
                Arrays.<Device<?, ?, ?>>asList(group("g1"), group("g2")));

        verify(seen[0]).setItems(one);
        verify(seen[1]).setItems(one);
    }

    @Test
    public void aPickedQueueWithAPlaylistFileLeavesItOut() {
        PickedQueue.Result queue = mock(PickedQueue.Result.class);
        PickedQueue.Entry entry = mock(PickedQueue.Entry.class);
        when(queue.audio()).thenReturn(Collections.singletonList(entry));
        when(queue.video()).thenReturn(Collections.<PickedQueue.Entry>emptyList());
        when(queue.image()).thenReturn(Collections.<PickedQueue.Entry>emptyList());
        PlayableItem song = item("audio/mpeg", "song.mp3");
        PlayableItem list = item("audio/x-mpegurl", "list.m3u");
        QueueItems.Built built = new QueueItems.Built();
        built.items().add(list);
        built.items().add(song);
        built.entries().add(entry);
        built.entries().add(entry);
        try (MockedStatic<QueueItems> items = mockStatic(QueueItems.class)) {
            items.when(() -> QueueItems.direct(any())).thenReturn(built);

            List<Player> players = service.createQueuePlayers(client, queue,
                    Collections.<Device<?, ?, ?>>singletonList(group("g1")));

            assertEquals(1, players.size());
        }
        verify(created).setItems(song);
    }

    @Test
    public void aPickedQueueOfPlaylistFilesOnlyMakesNoPlayerAndSaysSo() {
        PickedQueue.Result queue = mock(PickedQueue.Result.class);
        PickedQueue.Entry entry = mock(PickedQueue.Entry.class);
        when(queue.audio()).thenReturn(Collections.singletonList(entry));
        when(queue.video()).thenReturn(Collections.<PickedQueue.Entry>emptyList());
        when(queue.image()).thenReturn(Collections.<PickedQueue.Entry>emptyList());
        QueueItems.Built built = new QueueItems.Built();
        built.items().add(item("audio/x-mpegurl", "list.m3u"));
        built.entries().add(entry);
        try (MockedStatic<QueueItems> items = mockStatic(QueueItems.class)) {
            items.when(() -> QueueItems.direct(any())).thenReturn(built);

            List<Player> players = service.createQueuePlayers(client, queue,
                    Collections.<Device<?, ?, ?>>singletonList(group("g1")));

            assertTrue(players.isEmpty());
        }
        assertEquals(0, madePlayers);
        verify(service, times(1)).notifyNothingPlayable(client);
    }

    @Test
    public void onlyReceiversThatReadOnThisPhoneAreFiltered() {
        assertTrue(PlayerService.readsOnThisPhone(group("g1")));
        assertFalse(PlayerService.readsOnThisPhone(mock(Device.class)));
        assertFalse(PlayerService.readsOnThisPhone(null));
    }
}
