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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.SharedPreferences;

import org.junit.Test;

import de.yaacc.player.PlayMode.RepeatMode;

/** The repeat and shuffle mode of a player: states, the settings it starts from and the UPnP play modes. */
public class PlayModeTest {

    @Test
    public void theRepeatButtonCyclesOffAllOneOff() {
        PlayMode mode = PlayMode.NORMAL;

        mode = mode.nextRepeat();
        assertEquals(RepeatMode.ALL, mode.getRepeat());
        mode = mode.nextRepeat();
        assertEquals(RepeatMode.ONE, mode.getRepeat());
        mode = mode.nextRepeat();
        assertEquals(RepeatMode.OFF, mode.getRepeat());
    }

    @Test
    public void cyclingTheRepeatKeepsShuffle() {
        PlayMode shuffled = PlayMode.of(RepeatMode.OFF, true);

        assertTrue(shuffled.nextRepeat().isShuffle());
        assertTrue(shuffled.nextRepeat().nextRepeat().isShuffle());
    }

    @Test
    public void withersChangeOnlyTheirPart() {
        PlayMode mode = PlayMode.of(RepeatMode.ONE, false);

        assertEquals(PlayMode.of(RepeatMode.ONE, true), mode.withShuffle(true));
        assertEquals(PlayMode.of(RepeatMode.ALL, false), mode.withRepeat(RepeatMode.ALL));
        assertEquals(PlayMode.of(RepeatMode.ONE, true), mode.toggleShuffle());
        assertEquals(mode, mode.toggleShuffle().toggleShuffle());
    }

    @Test
    public void modesAreValuesAndImmutable() {
        PlayMode a = PlayMode.of(RepeatMode.ALL, true);
        PlayMode b = PlayMode.of(RepeatMode.ALL, true);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, PlayMode.of(RepeatMode.ALL, false));
        a.withRepeat(RepeatMode.ONE);
        assertEquals(RepeatMode.ALL, a.getRepeat());
        assertEquals(PlayMode.NORMAL, PlayMode.of(null, false));
    }

    @Test
    public void settingsGiveTheModeANewPlayerStartsWith() {
        assertEquals(PlayMode.of(RepeatMode.ALL, false), PlayMode.fromSettings(true, false));
        assertEquals(PlayMode.of(RepeatMode.OFF, false), PlayMode.fromSettings(false, false));
        assertEquals(PlayMode.of(RepeatMode.ALL, true), PlayMode.fromSettings(true, true));
        assertEquals(PlayMode.of(RepeatMode.OFF, true), PlayMode.fromSettings(false, true));
    }

    @Test
    public void preferencesDefaultToReplayOffAndShuffleOff() {
        SharedPreferences preferences = mock(SharedPreferences.class);
        when(preferences.getBoolean("replay", false)).thenAnswer(i -> i.getArgument(1));
        when(preferences.getBoolean("shuffle", false)).thenAnswer(i -> i.getArgument(1));

        assertEquals(PlayMode.of(RepeatMode.OFF, false), PlayMode.fromPreferences(preferences, "replay", "shuffle"));
    }

    @Test
    public void theSettingsScreenStartsWithReplayPlaylistUnchecked() throws Exception {
        java.io.File xml = new java.io.File("src/main/res/xml/preference.xml");
        String text = new String(java.nio.file.Files.readAllBytes(xml.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        int key = text.indexOf("@string/settings_replay_playlist_chkbx");
        assertTrue(key > 0);
        int start = text.lastIndexOf("<CheckBoxPreference", key);
        String element = text.substring(start, text.indexOf("/>", key));
        assertTrue(element, element.contains("android:defaultValue=\"false\""));
    }

    @Test
    public void preferencesAreReadWithTheirKeys() {
        SharedPreferences preferences = mock(SharedPreferences.class);
        when(preferences.getBoolean("replay", false)).thenReturn(false);
        when(preferences.getBoolean("shuffle", false)).thenReturn(true);

        assertEquals(PlayMode.of(RepeatMode.OFF, true), PlayMode.fromPreferences(preferences, "replay", "shuffle"));
    }

    @Test
    public void everyCombinationHasAnUpnpPlayMode() {
        assertEquals("NORMAL", PlayMode.of(RepeatMode.OFF, false).toUpnpPlayMode());
        assertEquals("SHUFFLE", PlayMode.of(RepeatMode.OFF, true).toUpnpPlayMode());
        assertEquals("REPEAT_ALL", PlayMode.of(RepeatMode.ALL, false).toUpnpPlayMode());
        assertEquals("RANDOM", PlayMode.of(RepeatMode.ALL, true).toUpnpPlayMode());
        assertEquals("REPEAT_ONE", PlayMode.of(RepeatMode.ONE, false).toUpnpPlayMode());
        // the protocol has no repeat one with shuffle
        assertEquals("REPEAT_ONE", PlayMode.of(RepeatMode.ONE, true).toUpnpPlayMode());
    }

    @Test
    public void upnpPlayModesMapBack() {
        assertEquals(PlayMode.of(RepeatMode.OFF, false), PlayMode.fromUpnpPlayMode("NORMAL"));
        assertEquals(PlayMode.of(RepeatMode.OFF, true), PlayMode.fromUpnpPlayMode("SHUFFLE"));
        assertEquals(PlayMode.of(RepeatMode.ALL, false), PlayMode.fromUpnpPlayMode("REPEAT_ALL"));
        assertEquals(PlayMode.of(RepeatMode.ALL, true), PlayMode.fromUpnpPlayMode("RANDOM"));
        assertEquals(PlayMode.of(RepeatMode.ONE, false), PlayMode.fromUpnpPlayMode("REPEAT_ONE"));
    }

    @Test
    public void unknownAndUnsupportedUpnpPlayModesAreNormal() {
        assertSame(PlayMode.NORMAL, PlayMode.fromUpnpPlayMode(null));
        assertSame(PlayMode.NORMAL, PlayMode.fromUpnpPlayMode(""));
        assertSame(PlayMode.NORMAL, PlayMode.fromUpnpPlayMode("DIRECT_1"));
        assertSame(PlayMode.NORMAL, PlayMode.fromUpnpPlayMode("INTRO"));
        assertSame(PlayMode.NORMAL, PlayMode.fromUpnpPlayMode("whatever"));
    }

    @Test
    public void upnpPlayModesAreReadLeniently() {
        assertEquals(PlayMode.of(RepeatMode.ALL, true), PlayMode.fromUpnpPlayMode(" random "));
        assertEquals(PlayMode.of(RepeatMode.ONE, false), PlayMode.fromUpnpPlayMode("repeat_one"));
    }

    @Test
    public void theUpnpMappingRoundTripsExceptRepeatOneWithShuffle() {
        for (RepeatMode repeat : RepeatMode.values()) {
            for (boolean shuffle : new boolean[]{false, true}) {
                PlayMode mode = PlayMode.of(repeat, shuffle);
                PlayMode back = PlayMode.fromUpnpPlayMode(mode.toUpnpPlayMode());
                if (repeat == RepeatMode.ONE) {
                    assertEquals(PlayMode.of(RepeatMode.ONE, false), back);
                } else {
                    assertEquals(mode, back);
                }
            }
        }
    }
}
