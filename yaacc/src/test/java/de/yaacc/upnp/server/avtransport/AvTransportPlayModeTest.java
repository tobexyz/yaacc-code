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
package de.yaacc.upnp.server.avtransport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;

import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;
import org.fourthline.cling.support.avtransport.AVTransportErrorCode;
import org.fourthline.cling.support.avtransport.AVTransportException;
import org.fourthline.cling.support.avtransport.lastchange.AVTransportLastChangeParser;
import org.fourthline.cling.support.avtransport.lastchange.AVTransportVariable;
import org.fourthline.cling.support.lastchange.LastChange;
import org.fourthline.cling.support.model.PlayMode;
import org.fourthline.cling.support.model.StorageMedium;
import org.fourthline.cling.support.model.TransportSettings;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.Collections;
import java.util.List;

import de.yaacc.player.Player;
import de.yaacc.upnp.UpnpClient;

/**
 * The play mode of the media renderer (UPnP {@code CurrentPlayMode}): {@code SetPlayMode} and
 * {@code GetTransportSettings} of the service, and the link between the renderer's transport and the
 * players it plays on (what Home Assistant's repeat and shuffle services use). Plain JVM.
 */
public class AvTransportPlayModeTest {

    private static final UnsignedIntegerFourBytes ID = new UnsignedIntegerFourBytes(0);

    private YaaccAVTransportService service;
    private UpnpClient client;
    private Player player;

    @Before
    public void setUp() {
        service = new YaaccAVTransportService();
        client = mock(UpnpClient.class);
        player = mock(Player.class);
        when(player.getPlayMode()).thenReturn(de.yaacc.player.PlayMode.fromSettings(true, false));
        AvTransport.setUpnpClient(client);
    }

    @After
    public void tearDown() {
        AvTransport.setUpnpClient(null);
    }

    private AvTransport transport() throws Exception {
        return (AvTransport) service.findStateMachine(ID).getCurrentState().getTransport();
    }

    private void playersOfTheTransport(Player... players) throws Exception {
        when(client.getCurrentPlayers(transport())).thenReturn(List.of(players));
    }

    private void settings(boolean replay, boolean shuffle) {
        Context context = mock(Context.class);
        SharedPreferences preferences = mock(SharedPreferences.class);
        when(client.getContext()).thenReturn(context);
        when(context.getPackageName()).thenReturn("de.yaacc");
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences);
        when(context.getString(de.yaacc.R.string.settings_replay_playlist_chkbx)).thenReturn("replay");
        when(context.getString(de.yaacc.R.string.settings_music_player_shuffle_chkbx)).thenReturn("shuffle");
        when(preferences.getBoolean("replay", false)).thenReturn(replay);
        when(preferences.getBoolean("shuffle", false)).thenReturn(shuffle);
    }

    // --- SetPlayMode / GetTransportSettings

    @Test
    public void setPlayModeIsAnsweredByGetTransportSettings() throws Exception {
        for (PlayMode mode : new PlayMode[]{PlayMode.NORMAL, PlayMode.REPEAT_ONE, PlayMode.REPEAT_ALL,
                PlayMode.SHUFFLE, PlayMode.RANDOM, PlayMode.DIRECT_1, PlayMode.INTRO}) {
            service.setPlayMode(ID, mode.name());

            assertEquals(mode, service.getTransportSettings(ID).getPlayMode());
        }
    }

    @Test
    public void getTransportSettingsReportsNoRecordQualityMode() throws Exception {
        TransportSettings settings = service.getTransportSettings(ID);

        assertEquals(org.fourthline.cling.support.model.RecordQualityMode.NOT_IMPLEMENTED, settings.getRecQualityMode());
    }

    @Test
    public void anUnknownPlayModeIsAnError712() throws Exception {
        service.setPlayMode(ID, "REPEAT_ONE");
        try {
            service.setPlayMode(ID, "BACKWARDS");
            fail("expected an error");
        } catch (AVTransportException e) {
            assertEquals(AVTransportErrorCode.PLAYMODE_NOT_SUPPORTED.getCode(), e.getErrorCode());
        }
        try {
            service.setPlayMode(ID, null);
            fail("expected an error");
        } catch (AVTransportException e) {
            assertEquals(712, e.getErrorCode());
        }

        assertEquals(PlayMode.REPEAT_ONE, service.getTransportSettings(ID).getPlayMode());
    }

    @Test
    public void setPlayModeIsEventedAsLastChange() throws Exception {
        service.setPlayMode(ID, "REPEAT_ALL");

        AVTransportVariable.CurrentPlayMode evented =
                service.getLastChange().getEventedValue(ID, AVTransportVariable.CurrentPlayMode.class);
        assertNotNull(evented);
        assertEquals(PlayMode.REPEAT_ALL, evented.getValue());
    }

    @Test
    public void theInitialStateOfTheLastChangeCarriesThePlayMode() throws Exception {
        service.setPlayMode(ID, "SHUFFLE");
        LastChange initial = new LastChange(new AVTransportLastChangeParser());

        service.appendCurrentState(initial, ID);

        assertEquals(PlayMode.SHUFFLE, initial.getEventedValue(ID, AVTransportVariable.CurrentPlayMode.class).getValue());
    }

    @Test
    public void anUnchangedPlayModeIsNotEventedAgain() throws Exception {
        service.setPlayMode(ID, "RANDOM");
        service.getLastChange().reset();

        service.setPlayMode(ID, "RANDOM");

        assertNull(service.getLastChange().getEventedValue(ID, AVTransportVariable.CurrentPlayMode.class));
    }

    // --- the mode before anything was set: what a new player gets from the settings

    @Test
    public void beforeAnythingWasSetTheModeIsTheOneANewPlayerStartsWith() throws Exception {
        settings(true, false);
        assertEquals(PlayMode.REPEAT_ALL, service.getTransportSettings(ID).getPlayMode());

        settings(false, false);
        assertEquals(PlayMode.NORMAL, service.getTransportSettings(ID).getPlayMode());

        settings(true, true);
        assertEquals(PlayMode.RANDOM, service.getTransportSettings(ID).getPlayMode());

        settings(false, true);
        assertEquals(PlayMode.SHUFFLE, service.getTransportSettings(ID).getPlayMode());
    }

    @Test
    public void onceSetTheSettingsNoLongerDecide() throws Exception {
        settings(true, false);
        service.setPlayMode(ID, "NORMAL");

        assertEquals(PlayMode.NORMAL, service.getTransportSettings(ID).getPlayMode());
    }

    // --- SetPlayMode reaches the players

    @Test
    public void setPlayModeGivesTheModeToThePlayersThatPlay() throws Exception {
        playersOfTheTransport(player);

        service.setPlayMode(ID, "RANDOM");

        verify(player).setPlayMode(de.yaacc.player.PlayMode.of(de.yaacc.player.PlayMode.RepeatMode.ALL, true));
    }

    @Test
    public void modesTheAppDoesNotHaveArePlayedAsNormal() throws Exception {
        playersOfTheTransport(player);

        service.setPlayMode(ID, "INTRO");

        verify(player).setPlayMode(de.yaacc.player.PlayMode.NORMAL);
        assertEquals(PlayMode.INTRO, service.getTransportSettings(ID).getPlayMode());
    }

    @Test
    public void setPlayModeWithoutAPlayerOrClientIsKept() throws Exception {
        AvTransport.setUpnpClient(null);

        service.setPlayMode(ID, "REPEAT_ONE");

        assertEquals(PlayMode.REPEAT_ONE, service.getTransportSettings(ID).getPlayMode());
    }

    @Test
    public void aPlayerThatStartsLaterGetsTheModeThatWasSet() throws Exception {
        service.setPlayMode(ID, "REPEAT_ONE");

        transport().attachPlayers(Collections.singletonList(player));

        verify(player).setPlayMode(de.yaacc.player.PlayMode.of(de.yaacc.player.PlayMode.RepeatMode.ONE, false));
    }

    @Test
    public void aPlayerThatStartsWithoutAChosenModeKeepsItsOwnAndTheTransportReportsIt() throws Exception {
        when(player.getPlayMode()).thenReturn(de.yaacc.player.PlayMode.of(de.yaacc.player.PlayMode.RepeatMode.ALL, true));

        transport().attachPlayers(Collections.singletonList(player));

        verify(player, never()).setPlayMode(any());
        assertEquals(PlayMode.RANDOM, service.getTransportSettings(ID).getPlayMode());
        assertEquals(PlayMode.RANDOM,
                service.getLastChange().getEventedValue(ID, AVTransportVariable.CurrentPlayMode.class).getValue());
    }

    // --- a change at the player reaches the control point

    @Test
    public void aChangeAtThePlayerIsReportedToTheControlPoint() throws Exception {
        transport().attachPlayers(Collections.singletonList(player));
        ArgumentCaptor<PropertyChangeListener> listener = ArgumentCaptor.forClass(PropertyChangeListener.class);
        verify(player).addPropertyChangeListener(listener.capture());
        service.getLastChange().reset();

        de.yaacc.player.PlayMode changed = de.yaacc.player.PlayMode.of(de.yaacc.player.PlayMode.RepeatMode.ONE, false);
        listener.getValue().propertyChange(new PropertyChangeEvent(player, Player.PROPERTY_PLAY_MODE,
                de.yaacc.player.PlayMode.NORMAL, changed));

        assertEquals(PlayMode.REPEAT_ONE, service.getTransportSettings(ID).getPlayMode());
        assertEquals(PlayMode.REPEAT_ONE,
                service.getLastChange().getEventedValue(ID, AVTransportVariable.CurrentPlayMode.class).getValue());
    }

    @Test
    public void aModeChosenAtAPlayerIsKeptForTheNextPlayer() throws Exception {
        transport().attachPlayers(Collections.singletonList(player));
        ArgumentCaptor<PropertyChangeListener> listener = ArgumentCaptor.forClass(PropertyChangeListener.class);
        verify(player).addPropertyChangeListener(listener.capture());
        listener.getValue().propertyChange(new PropertyChangeEvent(player, Player.PROPERTY_PLAY_MODE,
                de.yaacc.player.PlayMode.NORMAL, de.yaacc.player.PlayMode.of(de.yaacc.player.PlayMode.RepeatMode.OFF, true)));
        Player next = mock(Player.class);

        transport().attachPlayers(Collections.singletonList(next));

        verify(next).setPlayMode(de.yaacc.player.PlayMode.of(de.yaacc.player.PlayMode.RepeatMode.OFF, true));
    }

    @Test
    public void attachingThePlayerAgainReplacesItsListener() throws Exception {
        transport().attachPlayers(Collections.singletonList(player));
        transport().attachPlayers(Collections.singletonList(player));

        verify(player, org.mockito.Mockito.times(2)).removePropertyChangeListener(any());
        verify(player, org.mockito.Mockito.times(2)).addPropertyChangeListener(any());
    }

    @Test
    public void attachingNoPlayersChangesNothing() throws Exception {
        transport().attachPlayers(Collections.emptyList());
        transport().attachPlayers(null);

        assertEquals(PlayMode.NORMAL, service.getTransportSettings(ID).getPlayMode());
    }
}
