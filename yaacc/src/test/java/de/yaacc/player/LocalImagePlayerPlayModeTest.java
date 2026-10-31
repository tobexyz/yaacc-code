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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import de.yaacc.player.PlayMode.RepeatMode;
import de.yaacc.upnp.UpnpClient;

/** The slideshow player has its own play mode too: from the settings at the start, then the player's. */
public class LocalImagePlayerPlayModeTest {

    private SharedPreferences preferences;
    private LocalImagePlayer player;
    private final List<Object[]> changes = new ArrayList<>();

    @Before
    public void setUp() {
        Context context = mock(Context.class);
        preferences = mock(SharedPreferences.class);
        UpnpClient client = mock(UpnpClient.class);
        when(client.getContext()).thenReturn(context);
        when(context.getPackageName()).thenReturn("de.yaacc");
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences);
        when(context.getString(de.yaacc.R.string.settings_replay_playlist_chkbx)).thenReturn("replay");
        when(context.getString(de.yaacc.R.string.settings_music_player_shuffle_chkbx)).thenReturn("shuffle");
        when(preferences.getBoolean("replay", true)).thenReturn(true);
        when(preferences.getBoolean("shuffle", false)).thenReturn(false);
        player = new LocalImagePlayer(client);
        player.addPropertyChangeListener(event -> {
            if (Player.PROPERTY_PLAY_MODE.equals(event.getPropertyName())) {
                changes.add(new Object[]{event.getOldValue(), event.getNewValue()});
            }
        });
    }

    @Test
    public void startsWithTheModeTheSettingsGive() {
        assertEquals(PlayMode.of(RepeatMode.ALL, false), player.getPlayMode());
    }

    @Test
    public void aChangeIsKeptReportedOnceAndNeverWrittenToTheSettings() {
        PlayMode changed = PlayMode.of(RepeatMode.ONE, true);

        player.setPlayMode(changed);
        player.setPlayMode(changed);
        player.setPlayMode(null);

        assertEquals(changed, player.getPlayMode());
        assertEquals(1, changes.size());
        assertEquals(PlayMode.of(RepeatMode.ALL, false), changes.get(0)[0]);
        assertEquals(changed, changes.get(0)[1]);
        verify(preferences, never()).edit();
    }

    @Test
    public void theSettingsAreReadOnlyOnceForThePlayer() {
        player.getPlayMode();
        when(preferences.getBoolean("replay", true)).thenReturn(false);

        assertEquals(RepeatMode.ALL, player.getPlayMode().getRepeat());
    }
}
