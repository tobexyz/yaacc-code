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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;

import org.fourthline.cling.model.meta.Device;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 * Playing to a Snapcast group is wired behind the experimental option: with it off the group gets no player and the
 * user hears "not available yet" once (the behavior of before, see PlayerServiceSnapcastGuardTest); with it on a
 * music queue gets a Snapcast player per group, straight from the phone (no share), and video or images are refused
 * once with their own text. Plain JVM: the player factory and the toasts are seams of the service.
 */
public class PlayerServiceSnapcastPlayTest {

    private PlayerService service;
    private UpnpClient client;
    private SnapcastPlayer created;
    private int madePlayers;

    private static SnapcastGroupDevice group(String id, String name) {
        SnapcastClient member = new SnapcastClient("m-" + id, "member", "", true, new SnapcastVolume(50, false), 0);
        SnapcastGroup group = new SnapcastGroup(id, name, false, "Default", Collections.singletonList(member));
        return SnapcastGroupDevice.of(SnapcastEndpoint.parse("192.168.1.5:1705").toKey(), group, false,
                new SnapcastGroupDevice.Labels("Snapcast group", "offline"));
    }

    private static PlayableItem item(String mime) {
        PlayableItem item = mock(PlayableItem.class);
        when(item.getMimeType()).thenReturn(mime);
        return item;
    }

    @Before
    public void setUp() {
        service = spy(new PlayerService());
        doNothing().when(service).notifyNotPlayableYet(any());
        doNothing().when(service).notifySnapcastAudioOnly(any());
        doNothing().when(service).addPlayer(any());
        client = mock(UpnpClient.class);
        madePlayers = 0;
        doReturn(null).when(service).newSnapcastPlayer(any(), any());
    }

    private void optionIs(boolean on) {
        doReturn(on).when(service).snapcastPlayEnabled(any());
        org.mockito.stubbing.Answer<Player> make = call -> {
            madePlayers++;
            created = mock(SnapcastPlayer.class);
            return created;
        };
        org.mockito.Mockito.doAnswer(make).when(service).newSnapcastPlayer(any(), any());
    }

    // ---- the option

    @Test
    public void theOptionIsOffWhenNothingCanBeRead() {
        PlayerService plain = new PlayerService();

        assertFalse(plain.snapcastPlayEnabled(null));
        assertFalse(plain.snapcastPlayEnabled(client)); // the mocked client has no context
    }

    @Test
    public void theOptionIsReadFromThePreferenceWithOffAsDefault() {
        Context context = mock(Context.class);
        SharedPreferences preferences = mock(SharedPreferences.class);
        when(context.getString(de.yaacc.R.string.settings_snapcast_play_experimental)).thenReturn("settings_snapcast_play_experimental");
        when(context.getSharedPreferences(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(preferences);
        when(client.getContext()).thenReturn(context);
        when(preferences.getBoolean("settings_snapcast_play_experimental", false)).thenReturn(false, true);
        PlayerService plain = new PlayerService();

        assertFalse(plain.snapcastPlayEnabled(client));
        assertTrue("read again at every call", plain.snapcastPlayEnabled(client));
        verify(preferences, times(2)).getBoolean("settings_snapcast_play_experimental", false);
    }

    @Test
    public void thePreferenceIsDeclaredOffByDefaultInTheSettingsScreen() throws Exception {
        String xml = new String(Files.readAllBytes(new File("src/main/res/xml/preference.xml").toPath()), StandardCharsets.UTF_8);
        int at = xml.indexOf("android:key=\"@string/settings_snapcast_play_experimental\"");
        assertTrue(at > 0);
        int start = xml.lastIndexOf("<CheckBoxPreference", at);
        int end = xml.indexOf("/>", at);
        String element = xml.substring(start, end);
        assertTrue(element, element.contains("android:defaultValue=\"false\""));
        assertTrue(element, element.contains("@string/settings_snapcast_play_experimental_summary"));
        assertTrue(element, element.contains("@string/settings_snapcast_play_experimental_title"));
        assertEquals("a category with a clear name", 1, xml.split("@string/settings_snapcast_category", -1).length - 1);
    }

    @Test
    public void theSettingsTextsExistInEveryLocaleAndTheSummaryWarnsAboutTheMissingEncryption() throws Exception {
        for (String locale : new String[]{"values", "values-de", "values-es", "values-fr", "values-nl", "values-pt", "values-zh"}) {
            String xml = new String(Files.readAllBytes(new File("src/main/res/" + locale + "/setting_strings.xml").toPath()),
                    StandardCharsets.UTF_8);
            for (String name : new String[]{"settings_snapcast_category", "settings_snapcast_play_experimental_title",
                    "settings_snapcast_play_experimental_summary"}) {
                assertTrue(locale + " lacks " + name, xml.contains("<string name=\"" + name + "\">"));
            }
        }
        String english = new String(Files.readAllBytes(new File("src/main/res/values/setting_strings.xml").toPath()), StandardCharsets.UTF_8);
        assertTrue(english.contains("<string name=\"settings_snapcast_play_experimental\" translatable=\"false\">settings_snapcast_play_experimental</string>"));
        assertTrue(english.contains("unencrypted"));
        assertTrue(english.contains("Not tested on a device yet"));
        assertTrue(english.contains("Snapcast (experimental)"));
    }

    // ---- the option off: as before

    @Test
    public void withTheOptionOffAGroupGetsNoPlayerAndOneToastWhateverTheContent() {
        doReturn(false).when(service).snapcastPlayEnabled(any());
        List<Device<?, ?, ?>> devices = Arrays.<Device<?, ?, ?>>asList(group("g1", "A"), group("g2", "B"));

        List<Player> players = service.createPlayerFor(client, Collections.singletonList(item("audio/mpeg")), devices);

        assertTrue(players.isEmpty());
        verify(service, times(1)).notifyNotPlayableYet(client);
        verify(service, never()).notifySnapcastAudioOnly(any());
        verify(service, never()).newSnapcastPlayer(any(), any());
    }

    @Test
    public void theGuardKeepsItsMeaningForTheStaticQuestion() {
        assertTrue(PlayerService.isNotPlayableYet(group("g1", "A")));
        assertTrue(PlayerService.isNotPlayableYet(group("g1", "A"), false));
        assertFalse(PlayerService.isNotPlayableYet(group("g1", "A"), true));
        assertFalse(PlayerService.isNotPlayableYet((Device<?, ?, ?>) null, true));
    }

    // ---- the option on

    @Test
    public void withTheOptionOnAMusicQueueGetsOneSnapcastPlayerPerGroup() {
        optionIs(true);
        List<Device<?, ?, ?>> devices = Arrays.<Device<?, ?, ?>>asList(group("g1", "A"), group("g2", "B"));
        PlayableItem song = item("audio/mpeg");

        List<Player> players = service.createPlayerFor(client, Collections.singletonList(song), devices);

        assertEquals(2, players.size());
        assertEquals(2, madePlayers);
        verify(service, never()).notifyNotPlayableYet(any());
        verify(service, never()).notifySnapcastAudioOnly(any());
        verify(service, times(2)).addPlayer(any());
        verify(created).setItems(song);
    }

    @Test
    public void withTheOptionOnVideoToAGroupIsRefusedOnceAndTheOtherReceiversAreNotAffected() {
        optionIs(true);
        List<Device<?, ?, ?>> devices = Arrays.<Device<?, ?, ?>>asList(group("g1", "A"), group("g2", "B"));

        List<Player> players = service.createPlayerFor(client, Collections.singletonList(item("video/mp4")), devices);

        assertTrue(players.isEmpty());
        verify(service, times(1)).notifySnapcastAudioOnly(client);
        verify(service, never()).newSnapcastPlayer(any(), any());
        verify(service, never()).notifyNotPlayableYet(any());
    }

    @Test
    public void withTheOptionOnImagesAndMixedContentAreRefusedToo() {
        optionIs(true);
        service.createPlayerFor(client, Collections.singletonList(item("image/png")),
                Collections.<Device<?, ?, ?>>singletonList(group("g1", "A")));
        service.createPlayerFor(client, Arrays.asList(item("audio/mpeg"), item("image/png")),
                Collections.<Device<?, ?, ?>>singletonList(group("g1", "A")));

        verify(service, times(2)).notifySnapcastAudioOnly(client);
        verify(service, never()).newSnapcastPlayer(any(), any());
    }

    @Test
    public void contentWithoutATypeIsNotMusicToAGroup() {
        optionIs(true);

        service.createPlayerFor(client, Collections.singletonList(item(null)),
                Collections.<Device<?, ?, ?>>singletonList(group("g1", "A")));

        verify(service, times(1)).notifySnapcastAudioOnly(client);
        verify(service, never()).newSnapcastPlayer(any(), any());
    }

    @Test
    public void aPickedMusicQueueGoesToTheGroupStraightFromThePhoneWithoutAShareLease() {
        optionIs(true);
        PickedQueue.Result queue = mock(PickedQueue.Result.class);
        PickedQueue.Entry entry = mock(PickedQueue.Entry.class);
        when(queue.audio()).thenReturn(Collections.singletonList(entry));
        when(queue.video()).thenReturn(Collections.<PickedQueue.Entry>emptyList());
        when(queue.image()).thenReturn(Collections.<PickedQueue.Entry>emptyList());
        QueueItems.Built built = new QueueItems.Built();
        built.items().add(item("audio/mpeg"));
        built.entries().add(entry);
        try (MockedStatic<QueueItems> items = mockStatic(QueueItems.class)) {
            items.when(() -> QueueItems.direct(any())).thenReturn(built);
            items.when(() -> QueueItems.placeholders(any(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(new QueueItems.Built());

            List<Player> players = service.createQueuePlayers(client, queue,
                    Collections.<Device<?, ?, ?>>singletonList(group("g1", "A")));

            assertEquals(1, players.size());
            items.verify(() -> QueueItems.direct(any()), times(1));
            items.verify(() -> QueueItems.placeholders(any(), org.mockito.ArgumentMatchers.anyLong()), never());
        }
        verify(created, never()).setShareLease(any());
        verify(created).setItems(any());
        verify(service, never()).notifySnapcastAudioOnly(any());
    }

    @Test
    public void aPickedQueueWithVideoOrImagesTellsTheUserOnceAndStillPlaysTheMusic() {
        optionIs(true);
        PickedQueue.Result queue = mock(PickedQueue.Result.class);
        PickedQueue.Entry music = mock(PickedQueue.Entry.class);
        PickedQueue.Entry video = mock(PickedQueue.Entry.class);
        PickedQueue.Entry image = mock(PickedQueue.Entry.class);
        when(queue.audio()).thenReturn(Collections.singletonList(music));
        when(queue.video()).thenReturn(Collections.singletonList(video));
        when(queue.image()).thenReturn(Collections.singletonList(image));
        QueueItems.Built built = new QueueItems.Built();
        built.items().add(item("audio/mpeg"));
        built.entries().add(music);
        try (MockedStatic<QueueItems> items = mockStatic(QueueItems.class)) {
            items.when(() -> QueueItems.direct(any())).thenReturn(built);
            items.when(() -> QueueItems.placeholders(any(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(new QueueItems.Built());

            List<Player> players = service.createQueuePlayers(client, queue,
                    Collections.<Device<?, ?, ?>>singletonList(group("g1", "A")));

            assertEquals("the music plays", 1, players.size());
            items.verify(() -> QueueItems.placeholders(any(), org.mockito.ArgumentMatchers.anyLong()), never());
        }
        verify(service, times(1)).notifySnapcastAudioOnly(client);
        assertEquals(1, madePlayers);
    }

    @Test
    public void aSecondPlayerForTheSameGroupEndsTheFirst() {
        optionIs(true);
        SnapcastGroupDevice device = group("g1", "A");
        SnapcastPlayer old = mock(SnapcastPlayer.class);
        when(old.getDeviceId()).thenReturn(device.getIdentity().getUdn().getIdentifierString());
        when(old.getId()).thenReturn(4711);
        doReturn(Collections.singletonList(old)).when(service).getCurrentPlayersOfType(SnapcastPlayer.class);
        doNothing().when(service).shutdown(any(Player.class));

        service.createPlayerFor(client, Collections.singletonList(item("audio/mpeg")),
                Collections.<Device<?, ?, ?>>singletonList(device));

        verify(service).shutdown(old);
        assertEquals(1, madePlayers);
    }

    @Test
    public void aPlayerOfAnotherGroupIsLeftAlone() {
        optionIs(true);
        SnapcastPlayer other = mock(SnapcastPlayer.class);
        when(other.getDeviceId()).thenReturn(group("g9", "Other").getIdentity().getUdn().getIdentifierString());
        doReturn(Collections.singletonList(other)).when(service).getCurrentPlayersOfType(SnapcastPlayer.class);

        service.createPlayerFor(client, Collections.singletonList(item("audio/mpeg")),
                Collections.<Device<?, ?, ?>>singletonList(group("g1", "A")));

        verify(service, never()).shutdown(any(Player.class));
        assertEquals(1, madePlayers);
    }

    // ---- nothing changes for the rest

    @Test
    public void withoutSnapcastGroupsNothingIsFilteredOrSaid() {
        optionIs(true);
        List<Device<?, ?, ?>> devices = Collections.emptyList();

        assertSame(devices, service.withoutSnapcastGroups(client, devices, true));
        assertSame(devices, service.withoutNotPlayableYet(client, devices));
        verify(service, never()).notifySnapcastAudioOnly(any());
        verify(service, never()).notifyNotPlayableYet(any());
    }

    @Test
    public void musicContentLeavesTheGroupsInThelist() {
        optionIs(true);
        List<Device<?, ?, ?>> devices = Collections.<Device<?, ?, ?>>singletonList(group("g1", "A"));

        assertSame(devices, service.withoutSnapcastGroups(client, devices, false));
    }

    // ---- a Snapcast player is not "the local music player"

    @Test
    public void aSnapcastPlayerAnswersOnlyToItsOwnClassWhenSomeoneLooksForTheLocalMusicPlayer() {
        SnapcastPlayer snapcast = mock(SnapcastPlayer.class);
        LocalMediaSessionPlayer local = mock(LocalMediaSessionPlayer.class);

        assertFalse(PlayerService.isOfType(LocalMediaSessionPlayer.class, snapcast));
        assertTrue(PlayerService.isOfType(SnapcastPlayer.class, snapcast));
        assertTrue(PlayerService.isOfType(LocalMediaSessionPlayer.class, local));
        assertFalse(PlayerService.isOfType(SnapcastPlayer.class, local));
        assertFalse(PlayerService.isOfType(AVTransportPlayer.class, snapcast));
    }

    @Test
    public void theLookupsOfTheServiceSkipTheSnapcastPlayerForTheLocalMusicPlayer() {
        PlayerService plain = spy(new PlayerService());
        SnapcastPlayer snapcast = mock(SnapcastPlayer.class);
        LocalMediaSessionPlayer local = mock(LocalMediaSessionPlayer.class);
        doReturn(Arrays.<Player>asList(snapcast, local)).when(plain).getCurrentPlayers();

        assertSame(local, plain.getFirstCurrentPlayerOfType(LocalMediaSessionPlayer.class));
        assertEquals(Collections.singletonList(local), plain.getCurrentPlayersOfType(LocalMediaSessionPlayer.class));
        assertEquals(Collections.singletonList(snapcast), plain.getCurrentPlayersOfType(SnapcastPlayer.class));
    }

    @Test
    public void withOnlyASnapcastPlayerThereIsNoLocalMusicPlayer() {
        PlayerService plain = spy(new PlayerService());
        doReturn(Collections.<Player>singletonList(mock(SnapcastPlayer.class))).when(plain).getCurrentPlayers();

        assertSame(null, plain.getFirstCurrentPlayerOfType(LocalMediaSessionPlayer.class));
        assertTrue(plain.getCurrentPlayersOfType(LocalMediaSessionPlayer.class).isEmpty());
    }

    @Test
    public void theNewTextsExistAndTheOldGuardTextIsUntouched() throws Exception {
        String english = new String(Files.readAllBytes(new File("src/main/res/values/strings.xml").toPath()), StandardCharsets.UTF_8);
        assertTrue(english.contains("<string name=\"snapcast_play_not_available\">Playing to Snapcast groups is not available yet</string>"));
        assertTrue(english.contains("<string name=\"snapcast_play_audio_only\">Only music can be played to Snapcast groups</string>"));
        assertEquals(1, english.split("snapcast_play_audio_only", -1).length - 1);
    }
}
