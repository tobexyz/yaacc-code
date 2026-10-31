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

import android.content.Context;

import androidx.preference.PreferenceManager;

import de.yaacc.R;

/**
 * The one place that reads the settings "replay playlist" and "shuffle music": they are the play mode a
 * new player starts with. Nothing writes them but the settings screen.
 */
public final class PlayModeSettings {

    private PlayModeSettings() {
    }

    /** @return the play mode a new player starts with */
    public static PlayMode defaultPlayMode(Context context) {
        return PlayMode.fromPreferences(PreferenceManager.getDefaultSharedPreferences(context),
                context.getString(R.string.settings_replay_playlist_chkbx),
                context.getString(R.string.settings_music_player_shuffle_chkbx));
    }
}
