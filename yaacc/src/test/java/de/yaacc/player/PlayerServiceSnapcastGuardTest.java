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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.fourthline.cling.model.meta.Device;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import de.yaacc.multiroom.SnapcastGroupDevice;
import de.yaacc.share.PickedQueue;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.multiroom.control.SnapcastClient;
import de.yaacc.multiroom.control.SnapcastGroup;
import de.yaacc.multiroom.control.SnapcastVolume;
import de.yaacc.multiroom.discovery.SnapcastEndpoint;

/**
 * Until the Snapcast player exists, a Snapcast group is listed as a receiver but nothing can be played to it: the
 * player service must say so instead of building a player that silently does nothing.
 */
public class PlayerServiceSnapcastGuardTest {

    private static final String[] LOCALES = {"values", "values-de", "values-es", "values-fr", "values-nl", "values-pt", "values-zh"};

    private static SnapcastGroupDevice groupDevice() {
        SnapcastClient member = new SnapcastClient("m", "member", "", true, new SnapcastVolume(50, false), 0);
        SnapcastGroup group = new SnapcastGroup("g-a", "Attic", false, "Default", Collections.singletonList(member));
        return SnapcastGroupDevice.of(SnapcastEndpoint.parse("192.168.1.5:1705").toKey(), group, false,
                new SnapcastGroupDevice.Labels("Snapcast group", "offline"));
    }

    @Test
    public void aSnapcastGroupHasNoPlayerYet() {
        assertTrue(PlayerService.isNotPlayableYet(groupDevice()));
    }

    @Test
    public void otherReceiversAreNotAffected() {
        assertFalse(PlayerService.isNotPlayableYet((Device<?, ?, ?>) null));
    }

    @Test
    public void theToastTextExistsInEveryLanguage() throws Exception {
        for (String locale : LOCALES) {
            File file = new File("src/main/res/" + locale + "/strings.xml");
            assertTrue("missing " + file.getAbsolutePath(), file.isFile());
            String xml = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            int at = xml.indexOf("<string name=\"snapcast_play_not_available\">");
            assertTrue(locale + " lacks snapcast_play_not_available", at >= 0);
            int end = xml.indexOf("</string>", at);
            assertTrue(locale + ": empty text", end - at > "<string name=\"snapcast_play_not_available\">".length());
        }
    }

    @Test
    public void theEnglishTextSaysNotAvailableYet() throws Exception {
        String xml = new String(Files.readAllBytes(new File("src/main/res/values/strings.xml").toPath()), StandardCharsets.UTF_8);
        assertTrue(xml.contains(">Playing to Snapcast groups is not available yet<"));
        assertEquals(1, xml.split("snapcast_play_not_available", -1).length - 1);
    }

    // ---- the wiring: no player, no share lease, one toast per call

    private static SnapcastGroupDevice secondGroupDevice() {
        SnapcastClient member = new SnapcastClient("m2", "member", "", true, new SnapcastVolume(50, false), 0);
        SnapcastGroup group = new SnapcastGroup("g-b", "Bath", false, "Default", Collections.singletonList(member));
        return SnapcastGroupDevice.of(SnapcastEndpoint.parse("192.168.1.5:1705").toKey(), group, false,
                new SnapcastGroupDevice.Labels("Snapcast group", "offline"));
    }

    @Test
    public void aQueueToSnapcastGroupsMakesNoPlayerAndTellsTheUserOnce() {
        PlayerService service = spy(new PlayerService());
        doNothing().when(service).notifyNotPlayableYet(any());
        UpnpClient client = mock(UpnpClient.class);
        PickedQueue.Result queue = mock(PickedQueue.Result.class);
        when(queue.audio()).thenReturn(Collections.singletonList(mock(PickedQueue.Entry.class)));
        when(queue.video()).thenReturn(Collections.singletonList(mock(PickedQueue.Entry.class)));
        when(queue.image()).thenReturn(Collections.singletonList(mock(PickedQueue.Entry.class)));
        List<Device<?, ?, ?>> devices = Arrays.<Device<?, ?, ?>>asList(groupDevice(), secondGroupDevice());

        List<Player> players = service.createQueuePlayers(client, queue, devices);

        assertTrue("no player, so no share lease either", players.isEmpty());
        verify(service, times(1)).notifyNotPlayableYet(client);
    }

    @Test
    public void playingContentToSnapcastGroupsMakesNoPlayerAndTellsTheUserOnce() {
        PlayerService service = spy(new PlayerService());
        doNothing().when(service).notifyNotPlayableYet(any());
        UpnpClient client = mock(UpnpClient.class);
        PlayableItem item = mock(PlayableItem.class);
        List<Device<?, ?, ?>> devices = Arrays.<Device<?, ?, ?>>asList(groupDevice(), secondGroupDevice());

        List<Player> players = service.createPlayerFor(client, Collections.singletonList(item), devices);

        assertTrue(players.isEmpty());
        verify(service, times(1)).notifyNotPlayableYet(client);
    }

    @Test
    public void nothingIsSaidWhenNoSnapcastGroupIsInvolved() {
        PlayerService service = spy(new PlayerService());
        doNothing().when(service).notifyNotPlayableYet(any());
        UpnpClient client = mock(UpnpClient.class);

        List<Player> players = service.createPlayerFor(client, Collections.<PlayableItem>emptyList(),
                Collections.<Device<?, ?, ?>>emptyList());

        assertTrue(players.isEmpty());
        verify(service, never()).notifyNotPlayableYet(any());
    }
}
