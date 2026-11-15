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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.net.Uri;

import org.fourthline.cling.model.meta.Device;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.yaacc.multiroom.SnapcastGroupDevice;
import de.yaacc.multiroom.SnapcastRendererOutput;
import de.yaacc.multiroom.SnapcastRendererOutput.GroupFacts;
import de.yaacc.multiroom.SnapcastRendererOutput.Problem;
import de.yaacc.multiroom.control.SnapcastClient;
import de.yaacc.multiroom.control.SnapcastGroup;
import de.yaacc.multiroom.control.SnapcastVolume;
import de.yaacc.multiroom.discovery.SnapcastEndpoint;
import de.yaacc.upnp.UpnpClient;

/**
 * The one place that makes the player of the YAACC renderer (task 9.1: {@code createPlayerForLocalDevice} for AVTransport,
 * {@code createPlayerForLocalQueue} for OpenHome) follows the output setting: this device = the local player as before,
 * Snapcast group = the Snapcast player of the group (the same path as a group picked in the app), nothing = a refused play
 * with a message. Switching the output stops exactly the renderer's players of the other output. Plain JVM: the
 * preferences, the model of the servers, the wait, the toasts and the player factories are seams of the service.
 */
public class PlayerServiceRendererOutputTest {

    private static final String ID = SnapcastGroupDevice.idOf(SnapcastEndpoint.parse("192.168.1.5:1705").toKey(), "g-1");

    private PlayerService service;
    private UpnpClient client;
    private final Map<String, String> prefs = new HashMap<>();
    private final List<Problem> told = new ArrayList<>();
    private GroupFacts facts = GroupFacts.READY;
    private int probes;
    private int wakes;
    private Device<?, ?, ?> localDevice;
    private SnapcastGroupDevice groupDevice;
    private Player localPlayer;
    private SnapcastPlayer snapcastPlayer;
    private int snapcastPlayersMade;

    private static PlayableItem item(String mime, String uri) {
        PlayableItem item = mock(PlayableItem.class);
        when(item.getMimeType()).thenReturn(mime);
        when(item.getUri()).thenReturn(uri == null ? null : mock(Uri.class));
        when(item.getTitle()).thenReturn(uri);
        return item;
    }

    private static PlayableItem playlistFile() {
        PlayableItem item = mock(PlayableItem.class);
        when(item.getMimeType()).thenReturn("audio/x-mpegurl");
        when(item.getTitle()).thenReturn("list.m3u");
        return item;
    }

    @Before
    public void setUp() {
        service = spy(new PlayerService());
        client = mock(UpnpClient.class);
        SnapcastClient member = new SnapcastClient("m", "member", "", true, new SnapcastVolume(50, false), 0);
        groupDevice = SnapcastGroupDevice.of(SnapcastEndpoint.parse("192.168.1.5:1705").toKey(),
                new SnapcastGroup("g-1", "Kitchen", false, "Default", Collections.singletonList(member)), false,
                new SnapcastGroupDevice.Labels("Snapcast group", "offline"));
        localDevice = mock(Device.class);
        localPlayer = mock(Player.class);
        snapcastPlayer = mock(SnapcastPlayer.class);
        when(client.getDevice(UpnpClient.LOCAL_UID)).thenAnswer(call -> localDevice);
        when(client.getDevice(ID)).thenAnswer(call -> groupDevice);
        doAnswer(call -> SnapcastRendererOutput.read(prefs::get)).when(service).rendererOutputSetting(any());
        doAnswer(call -> new SnapcastRendererOutput.Probe() {
            @Override
            public GroupFacts facts(String receiverId) {
                probes++;
                return facts;
            }

            @Override
            public void wake(String receiverId) {
                wakes++;
            }
        }).when(service).rendererProbe(any());
        doAnswer(call -> new SnapcastRendererOutput.Waiter() {
            long now;

            @Override
            public long nowMillis() {
                return now;
            }

            @Override
            public void sleep(long millis) {
                now += millis;
            }
        }).when(service).rendererWaiter();
        doAnswer(call -> {
            told.add(call.getArgument(1));
            return null;
        }).when(service).notifyRendererOutputProblem(any(), any());
        doNothing().when(service).notifyNothingPlayable(any());
        doNothing().when(service).addPlayer(any());
        doNothing().when(service).updateForegroundState();
        doReturn(localPlayer).when(service).createPlayer(any(), eq(localDevice), anyBoolean(), anyBoolean(), anyBoolean());
        doAnswer(call -> {
            snapcastPlayersMade++;
            return snapcastPlayer;
        }).when(service).newSnapcastPlayer(any(), any());
        // the experimental option of the app's picker is off: the renderer's own choice does not need it
        doReturn(false).when(service).snapcastPlayEnabled(any());
    }

    private void groupMode() {
        prefs.put(SnapcastRendererOutput.KEY_OUTPUT, "group");
        prefs.put(SnapcastRendererOutput.KEY_GROUP, ID);
    }

    // ---- this device

    @Test
    public void thisDeviceIsTheLocalPlayerAsBeforeAndTheServerIsNeverAsked() {
        List<PlayableItem> items = Arrays.asList(item("audio/mpeg", "a.mp3"));

        List<Player> players = service.createPlayerForLocalDevice(client, items);

        assertEquals(Collections.singletonList(localPlayer), players);
        verify(service, never()).newSnapcastPlayer(any(), any());
        assertEquals(0, probes);
        assertEquals(0, wakes);
        assertTrue(told.isEmpty());
        verify(localPlayer).setItems(items.toArray(new PlayableItem[0]));
    }

    @Test
    public void aDamagedSettingPlaysLocallyAndTheUserIsToldOnce() {
        prefs.put(SnapcastRendererOutput.KEY_OUTPUT, "group");
        prefs.put(SnapcastRendererOutput.KEY_GROUP, "garbage");

        List<Player> players = service.createPlayerForLocalDevice(client, Arrays.asList(item("audio/mpeg", "a.mp3")));

        assertEquals(Collections.singletonList(localPlayer), players);
        assertEquals(Collections.singletonList(Problem.DAMAGED_SETTING), told);
        verify(service, never()).newSnapcastPlayer(any(), any());
    }

    @Test
    public void videoStillGoesToTheLocalPlayersWhenTheOutputIsThisDevice() {
        List<Player> players = service.createPlayerForLocalDevice(client, Arrays.asList(item("video/mp4", "v.mp4")));

        assertEquals(1, players.size());
        verify(service).createPlayer(any(), eq(localDevice), eq(true), eq(false), eq(false));
    }

    // ---- the group

    @Test
    public void inGroupModeBothRenderersGetTheSnapcastPlayerOfTheGroupNotALocalOne() {
        groupMode();
        List<PlayableItem> items = Arrays.asList(item("audio/mpeg", "a.mp3"), item("audio/flac", "b.flac"));

        List<Player> viaAvTransport = service.createPlayerForLocalDevice(client, items);
        List<Player> viaOpenHome = service.createPlayerForLocalQueue(client, items);

        assertEquals(Collections.singletonList(snapcastPlayer), viaAvTransport);
        assertEquals(Collections.singletonList(snapcastPlayer), viaOpenHome);
        assertEquals(2, snapcastPlayersMade);
        verify(service, times(2)).newSnapcastPlayer(client, groupDevice);
        verify(service, never()).createPlayer(any(), eq(localDevice), anyBoolean(), anyBoolean(), anyBoolean());
        verify(service, times(2)).addPlayer(snapcastPlayer);
        verify(snapcastPlayer, times(2)).setItems(items.toArray(new PlayableItem[0]));
        assertTrue(told.isEmpty());
    }

    @Test
    public void theChoiceInTheRendererSettingsNeedsNoExperimentalOption() {
        groupMode();
        doReturn(false).when(service).snapcastPlayEnabled(any());

        List<Player> players = service.createPlayerForLocalDevice(client, Arrays.asList(item("audio/mpeg", "a.mp3")));

        assertEquals(1, players.size());
        verify(service, never()).notifyNotPlayableYet(any());
    }

    @Test
    public void avTransportLeavesPlaylistFilesOutAndOpenHomeKeepsTheListAsItIs() {
        groupMode();
        PlayableItem song = item("audio/mpeg", "a.mp3");
        PlayableItem list = playlistFile();
        List<PlayableItem> items = Arrays.asList(song, list, item("audio/flac", "b.flac"));

        service.createPlayerForLocalDevice(client, items);
        verify(snapcastPlayer).setItems(items.get(0), items.get(2));
        service.createPlayerForLocalQueue(client, items);

        // OpenHome keeps its queue ids parallel to the player's list: the same three entries, the playlist file included
        verify(snapcastPlayer).setItems(items.get(0), items.get(1), items.get(2));
    }

    @Test
    public void onlyPlaylistFilesForAvTransportIsTheUsualNothingPlayableMessage() {
        groupMode();

        List<Player> players = service.createPlayerForLocalDevice(client, Collections.singletonList(playlistFile()));

        assertTrue(players.isEmpty());
        verify(service).notifyNothingPlayable(client);
        verify(service, never()).newSnapcastPlayer(any(), any());
    }

    @Test
    public void videoAndImagesAreRefusedInGroupModeWithAMessageAndNothingPlaysLocally() {
        groupMode();

        for (String mime : new String[]{"video/mp4", "image/png", "application/pdf"}) {
            told.clear();
            List<Player> players = service.createPlayerForLocalDevice(client, Collections.singletonList(item(mime, "x")));

            assertTrue(mime, players.isEmpty());
            assertEquals(mime, Collections.singletonList(Problem.NOT_MUSIC), told);
        }
        verify(service, never()).createPlayer(any(), any(), anyBoolean(), anyBoolean(), anyBoolean());
        verify(service, never()).newSnapcastPlayer(any(), any());
        assertEquals("the server was not asked for content it would refuse", 0, probes);
    }

    @Test
    public void everyProblemOfTheServerIsAMessageAndNeverALocalPlay() {
        groupMode();
        Map<GroupFacts, Problem> expected = new HashMap<>();
        expected.put(GroupFacts.NOT_LISTED, Problem.SERVER_NOT_LISTED);
        expected.put(GroupFacts.PENDING, Problem.SERVER_UNREACHABLE);
        expected.put(GroupFacts.OFFLINE, Problem.SERVER_UNREACHABLE);
        expected.put(GroupFacts.GONE, Problem.GROUP_GONE);

        for (Map.Entry<GroupFacts, Problem> entry : expected.entrySet()) {
            told.clear();
            facts = entry.getKey();

            List<Player> viaAvTransport = service.createPlayerForLocalDevice(client, Arrays.asList(item("audio/mpeg", "a.mp3")));
            List<Player> viaOpenHome = service.createPlayerForLocalQueue(client, Arrays.asList(item("audio/mpeg", "a.mp3")));

            assertTrue(entry.getKey().name(), viaAvTransport.isEmpty() && viaOpenHome.isEmpty());
            assertEquals(entry.getKey().name(), Arrays.asList(entry.getValue(), entry.getValue()), told);
        }
        verify(service, never()).createPlayer(any(), any(), anyBoolean(), anyBoolean(), anyBoolean());
        verify(service, never()).newSnapcastPlayer(any(), any());
    }

    @Test
    public void aGroupThatVanishedBetweenTheCheckAndTheLookupIsGroupGone() {
        groupMode();
        when(client.getDevice(ID)).thenReturn(null);

        List<Player> players = service.createPlayerForLocalDevice(client, Arrays.asList(item("audio/mpeg", "a.mp3")));

        assertTrue(players.isEmpty());
        assertEquals(Collections.singletonList(Problem.GROUP_GONE), told);
    }

    @Test
    public void noGroupChosenIsAMessageNotALocalPlay() {
        prefs.put(SnapcastRendererOutput.KEY_OUTPUT, "group");

        List<Player> players = service.createPlayerForLocalDevice(client, Arrays.asList(item("audio/mpeg", "a.mp3")));

        assertTrue(players.isEmpty());
        assertEquals(Collections.singletonList(Problem.NO_GROUP_CHOSEN), told);
        verify(service, never()).createPlayer(any(), any(), anyBoolean(), anyBoolean(), anyBoolean());
    }

    @Test
    public void aConnectionThatIsComingUpIsWokenAndWaitedForBeforeThePlay() {
        groupMode();
        facts = GroupFacts.PENDING;
        doAnswer(call -> new SnapcastRendererOutput.Probe() {
            int asked;

            @Override
            public GroupFacts facts(String receiverId) {
                return ++asked < 4 ? GroupFacts.PENDING : GroupFacts.READY;
            }

            @Override
            public void wake(String receiverId) {
                wakes++;
            }
        }).when(service).rendererProbe(any());

        List<Player> players = service.createPlayerForLocalDevice(client, Arrays.asList(item("audio/mpeg", "a.mp3")));

        assertEquals(1, players.size());
        assertEquals(1, wakes);
    }

    // ---- switching the output while playing

    @Test
    public void rendererPlayersAreTaggedWithTheOutputTheyWereMadeFor() {
        service.createPlayerForLocalDevice(client, Arrays.asList(item("audio/mpeg", "a.mp3")));
        groupMode();
        service.createPlayerForLocalQueue(client, Arrays.asList(item("audio/mpeg", "a.mp3")));

        assertEquals(Collections.singletonList(localPlayer), service.rendererPlayers().toStopFor("group:" + ID));
        assertEquals(Collections.singletonList(snapcastPlayer), service.rendererPlayers().toStopFor("device"));
    }

    @Test
    public void switchingFromThisDeviceToTheGroupStopsTheLocalRendererPlayerAndNothingElse() {
        service.createPlayerForLocalDevice(client, Arrays.asList(item("audio/mpeg", "a.mp3")));
        Player appsOwnLocalPlayer = mock(Player.class);
        doNothing().when(service).shutdown(any(Player.class));
        groupMode();

        service.rendererOutputChanged(SnapcastRendererOutput.outputKey(SnapcastRendererOutput.read(prefs::get)));

        verify(service).shutdown(localPlayer);
        verify(service, never()).shutdown(appsOwnLocalPlayer);
        verify(service, never()).newSnapcastPlayer(any(), any());
        verify(service, times(1)).createPlayer(any(), any(), anyBoolean(), anyBoolean(), anyBoolean()); // the first play only: the switch starts nothing
    }

    @Test
    public void switchingBackToThisDeviceShutsTheSnapcastPlayerDownSoTheFeedIsClosed() {
        groupMode();
        service.createPlayerForLocalDevice(client, Arrays.asList(item("audio/mpeg", "a.mp3")));
        doNothing().when(service).shutdown(any(Player.class));
        prefs.put(SnapcastRendererOutput.KEY_OUTPUT, "device");

        service.rendererOutputChanged(SnapcastRendererOutput.outputKey(SnapcastRendererOutput.read(prefs::get)));

        verify(service).shutdown(snapcastPlayer);
        verify(service, never()).createPlayer(any(), any(), anyBoolean(), anyBoolean(), anyBoolean());
    }

    @Test
    public void aChangeToTheSameOutputStopsNothing() {
        service.createPlayerForLocalDevice(client, Arrays.asList(item("audio/mpeg", "a.mp3")));
        doNothing().when(service).shutdown(any(Player.class));

        service.rendererOutputChanged("device");

        verify(service, never()).shutdown(any(Player.class));
    }

    @Test
    public void aShutDownPlayerIsForgottenSoALaterChangeDoesNotTouchItAgain() {
        service.createPlayerForLocalDevice(client, Arrays.asList(item("audio/mpeg", "a.mp3")));

        service.shutdown(localPlayer);

        assertTrue(service.rendererPlayers().toStopFor("group:x").isEmpty());
    }

    @Test
    public void theNextPlayAfterTheSwitchUsesTheNewOutput() {
        service.createPlayerForLocalDevice(client, Arrays.asList(item("audio/mpeg", "a.mp3")));
        doNothing().when(service).shutdown(any(Player.class));
        groupMode();
        service.rendererOutputChanged(SnapcastRendererOutput.outputKey(SnapcastRendererOutput.read(prefs::get)));

        List<Player> players = service.createPlayerForLocalDevice(client, Arrays.asList(item("audio/mpeg", "b.mp3")));

        assertSame(snapcastPlayer, players.get(0));
        assertEquals(1, snapcastPlayersMade);
    }

    // ---- finding the renderer's player again (stop, pause, seek of the transport)

    @Test
    public void inGroupModeTheTransportFindsTheSnapcastPlayerOfTheChosenGroupForMusic() {
        groupMode();
        SnapcastPlayer chosen = mock(SnapcastPlayer.class);
        when(chosen.getDeviceId()).thenReturn(ID);
        SnapcastPlayer another = mock(SnapcastPlayer.class);
        when(another.getDeviceId()).thenReturn(SnapcastGroupDevice.idOf(SnapcastEndpoint.parse("192.168.1.5:1705").toKey(), "g-2"));
        doReturn(Arrays.<Player>asList(another, chosen)).when(service).getCurrentPlayersOfType(SnapcastPlayer.class);
        Player appsLocal = mock(Player.class);
        doReturn(Collections.singletonList(appsLocal)).when(service).getCurrentPlayersOfType(LocalMediaSessionPlayer.class);

        List<Player> found = service.getRendererPlayersOfType(client, LocalMediaSessionPlayer.class);

        assertEquals("only the chosen group's player, never the app's local music player", Collections.singletonList(chosen), found);
    }

    @Test
    public void whenThisDeviceIsTheOutputTheLookupIsTheOldOne() {
        Player appsLocal = mock(Player.class);
        doReturn(Collections.singletonList(appsLocal)).when(service).getCurrentPlayersOfType(LocalMediaSessionPlayer.class);

        assertEquals(Collections.singletonList(appsLocal), service.getRendererPlayersOfType(client, LocalMediaSessionPlayer.class));
        verify(service, never()).getCurrentPlayersOfType(SnapcastPlayer.class);
    }

    @Test
    public void otherKindsOfPlayerAreLookedUpAsBeforeInGroupModeToo() {
        groupMode();
        Player video = mock(Player.class);
        doReturn(Collections.singletonList(video)).when(service).getCurrentPlayersOfType(MultiContentPlayer.class);

        assertEquals(Collections.singletonList(video), service.getRendererPlayersOfType(client, MultiContentPlayer.class));
    }

    // ---- Fix Group 9b S5: a content without a type has its own message

    @Test
    public void aContentWithoutATypeIsRefusedInGroupModeWithItsOwnMessage() {
        groupMode();

        for (String mime : new String[]{null, ""}) {
            told.clear();
            List<Player> players = service.createPlayerForLocalDevice(client, Collections.singletonList(item(mime, "x")));

            assertTrue(players.isEmpty());
            assertEquals(String.valueOf(mime), Collections.singletonList(Problem.UNKNOWN_TYPE), told);
        }
        assertEquals("the server was not asked", 0, probes);
        verify(service, never()).newSnapcastPlayer(any(), any());
    }

    @Test
    public void aContentWithoutATypeStillPlaysLocallyWhenTheOutputIsThisDevice() {
        List<Player> players = service.createPlayerForLocalDevice(client, Collections.singletonList(item(null, "x")));

        assertEquals(1, players.size());
        assertTrue(told.isEmpty());
    }

    // ---- Fix Group 9b S1: the setting changed while the play was waiting

    @Test
    public void aSwitchToThisDeviceWhileThePlayWaitsForTheServerLeavesNoGroupPlayerRunning() {
        groupMode();
        doNothing().when(service).shutdown(any(Player.class));
        doAnswer(call -> new SnapcastRendererOutput.Probe() {
            int asked;

            @Override
            public GroupFacts facts(String receiverId) {
                return ++asked < 3 ? GroupFacts.PENDING : GroupFacts.READY;
            }

            @Override
            public void wake(String receiverId) {
                prefs.put(SnapcastRendererOutput.KEY_OUTPUT, "device"); // the user switches during the wait
            }
        }).when(service).rendererProbe(any());

        List<Player> players = service.createPlayerForLocalDevice(client, Arrays.asList(item("audio/mpeg", "a.mp3")));

        verify(service).shutdown(snapcastPlayer);
        assertTrue("the play ends without a player: the transport says STOPPED", players.isEmpty());
    }

    @Test
    public void aSwitchToTheGroupWhileALocalPlayerIsBeingMadeLeavesNoLocalPlayerRunning() {
        doNothing().when(service).shutdown(any(Player.class));
        doAnswer(call -> {
            groupMode(); // the user switches while the local player is made
            return localPlayer;
        }).when(service).createPlayer(any(), eq(localDevice), anyBoolean(), anyBoolean(), anyBoolean());

        List<Player> players = service.createPlayerForLocalDevice(client, Arrays.asList(item("audio/mpeg", "a.mp3")));

        verify(service).shutdown(localPlayer);
        assertTrue(players.isEmpty());
    }

    @Test
    public void withoutASwitchThePlayerIsKept() {
        groupMode();
        doNothing().when(service).shutdown(any(Player.class));

        List<Player> players = service.createPlayerForLocalDevice(client, Arrays.asList(item("audio/mpeg", "a.mp3")));

        assertEquals(1, players.size());
        verify(service, never()).shutdown(any(Player.class));
    }
}
