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
package de.yaacc.sendspin.control;

import java.util.List;

/**
 * What the controller role of the server needs of the app (issue #325, K15): the groups of the hub that an app player plays to. The
 * Android side is made of the hub's groups and the app's players; the tests use a fake. A controller never sees anything else of the
 * app.
 */
public interface PlaybackBridge {

    /** The groups of the hub that have an app player behind them (a leader, or a lone player, with its members). */
    List<Group> groups();

    /** One group and its player. The commands may throw; the logic treats that as a failed command. */
    interface Group {
        /** A stable id of the group while it exists (the receiver id of its leader). */
        String id();

        /** The leader's name, plus the number of the members ("Kitchen +2"). */
        String name();

        boolean isPlaying();

        /** Rises when the group starts to play: the group that started last has the highest value, 0 if it never played. */
        long activitySerial();

        int itemCount();

        /** 0 if the length of the current item is not known. */
        long durationMs();

        long positionMs();

        /** The volume of the whole group, 0 to 100. */
        int volume();

        boolean isMuted();

        boolean supportsVolume();

        boolean supportsMute();

        boolean supportsPlayModes();

        /** "off", "one" or "all". */
        String repeat();

        boolean isShuffle();

        String title();

        String artist();

        String album();

        /** An http or https address of the cover on the local network, or null. */
        String artworkUrl();

        /** 0 if unknown. */
        int year();

        /** 0 if unknown. */
        int track();

        void play();

        void pause();

        void stop();

        void next();

        void previous();

        void setVolume(int percent);

        void setMuted(boolean muted);

        void setRepeat(String mode);

        void setShuffle(boolean on);

        void seekTo(long millis);
    }
}
