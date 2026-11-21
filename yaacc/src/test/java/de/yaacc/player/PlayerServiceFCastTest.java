/*
 *
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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.fourthline.cling.model.meta.Device;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import de.yaacc.fcast.discovery.FCastDirectory;
import de.yaacc.fcast.discovery.FCastEndpoint;
import de.yaacc.fcast.discovery.FCastReceiverDevice;
import de.yaacc.upnp.UpnpClient;

/**
 * Task 4.4: the service makes one FCast player per receiver, for music, video and pictures alike, and ends an earlier player of the
 * same receiver first. Plain JVM: the player factory is a seam of the service.
 */
public class PlayerServiceFCastTest {

    private PlayerService service;
    private UpnpClient client;
    private final List<FCastPlayer> made = new ArrayList<>();

    private static FCastReceiverDevice receiver(String endpoint) {
        FCastDirectory directory = new FCastDirectory(() -> 0L, 30_000);
        directory.setManual(Collections.singletonList(new FCastDirectory.Known(FCastEndpoint.parse(endpoint), "Room " + endpoint)));
        return FCastReceiverDevice.of(directory.snapshot().get(0), null);
    }

    private static PlayableItem item(String mime) {
        PlayableItem item = mock(PlayableItem.class);
        when(item.getMimeType()).thenReturn(mime);
        return item;
    }

    private static FCastPlayer playerOf(String receiverId) {
        FCastPlayer player = mock(FCastPlayer.class);
        when(player.getDeviceId()).thenReturn(receiverId);
        return player;
    }

    @Before
    public void setUp() {
        service = spy(new PlayerService());
        doNothing().when(service).addPlayer(any());
        doNothing().when(service).shutdown(any(Player.class));
        client = mock(UpnpClient.class);
        doReturn(Collections.emptyList()).when(service).getCurrentPlayersOfType(FCastPlayer.class);
        org.mockito.Mockito.doAnswer(call -> {
            FCastReceiverDevice device = call.getArgument(1);
            FCastPlayer player = playerOf(device.getReceiverId());
            made.add(player);
            return player;
        }).when(service).newFCastPlayer(any(), any());
    }

    @Test
    public void everyKindOfContentGetsAPlayerForTheReceiver() {
        for (String[] mimes : new String[][]{{"audio/mpeg"}, {"video/mp4"}, {"image/jpeg"}, {"audio/mpeg", "video/mp4", "image/png"}}) {
            made.clear();
            List<PlayableItem> items = new ArrayList<>();
            for (String mime : mimes) {
                items.add(item(mime));
            }

            List<Player> players = service.createPlayerFor(client, items, Collections.<Device<?, ?, ?>>singletonList(receiver("192.168.0.9")));

            assertEquals(Arrays.toString(mimes), 1, players.size());
            assertSame(made.get(0), players.get(0));
            verify(made.get(0)).setItems(items.toArray(new PlayableItem[0]));
        }
        verify(service, never()).notifyNotPlayableYet(any());
    }

    @Test
    public void twoReceiversGetTwoPlayers() {
        List<Device<?, ?, ?>> devices = Arrays.<Device<?, ?, ?>>asList(receiver("192.168.0.9"), receiver("192.168.0.10:1234"));

        List<Player> players = service.createPlayerFor(client, Collections.singletonList(item("audio/mpeg")), devices);

        assertEquals(2, players.size());
        assertEquals("fcast:192.168.0.9:46899", players.get(0).getDeviceId());
        assertEquals("fcast:192.168.0.10:1234", players.get(1).getDeviceId());
    }

    @Test
    public void anEarlierPlayerOfTheSameReceiverEndsFirstAndOtherReceiversKeepTheirs() {
        FCastPlayer same = playerOf("fcast:192.168.0.9:46899");
        FCastPlayer other = playerOf("fcast:192.168.0.77:46899");
        doReturn(Arrays.<Player>asList(other, same)).when(service).getCurrentPlayersOfType(FCastPlayer.class);

        List<Player> players = service.createPlayerFor(client, Collections.singletonList(item("audio/mpeg")),
                Collections.<Device<?, ?, ?>>singletonList(receiver("192.168.0.9")));

        assertEquals(1, players.size());
        verify(service).shutdown(same);
        verify(service, never()).shutdown(other);
        // the old player is gone before the new one is made
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(service);
        order.verify(service).shutdown(same);
        order.verify(service).newFCastPlayer(any(), any());
        assertTrue(made.size() == 1);
    }
}
