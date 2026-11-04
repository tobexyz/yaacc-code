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
package de.yaacc.yxc;

import org.json.JSONObject;

import java.util.List;

/**
 * Answer of {@code netusb/getPlayInfo}: what the device plays from a network source by itself. The notes say the times
 * are seconds; that was not confirmed: a stopped device reported a play time of -60000, so a negative value is not a
 * time to show.
 */
public final class YxcPlayInfo {
    private final String input;
    private final String playback;
    private final String repeat;
    private final String shuffle;
    private final String artist;
    private final String album;
    private final String track;
    private final String albumArtUrl;
    private final Integer totalTime;
    private final Integer playTime;
    private final Long attribute;
    private final List<String> repeatAvailable;
    private final List<String> shuffleAvailable;

    private YxcPlayInfo(JSONObject json) {
        input = YxcJson.string(json, "input");
        playback = YxcJson.string(json, "playback");
        repeat = YxcJson.string(json, "repeat");
        shuffle = YxcJson.string(json, "shuffle");
        artist = YxcJson.string(json, "artist");
        album = YxcJson.string(json, "album");
        track = YxcJson.string(json, "track");
        albumArtUrl = YxcJson.string(json, "albumart_url");
        totalTime = YxcJson.integer(json, "total_time");
        playTime = YxcJson.integer(json, "play_time");
        attribute = YxcJson.longInteger(json, "attribute");
        repeatAvailable = YxcJson.strings(json, "repeat_available");
        shuffleAvailable = YxcJson.strings(json, "shuffle_available");
    }

    static YxcPlayInfo fromJson(JSONObject json) {
        return new YxcPlayInfo(json);
    }

    /** @return the id of the network input currently selected */
    public String getInput() {
        return input;
    }

    /** @return {@code play}, {@code pause}, {@code stop} or another word the device uses */
    public String getPlayback() {
        return playback;
    }

    public String getRepeat() {
        return repeat;
    }

    public String getShuffle() {
        return shuffle;
    }

    public String getArtist() {
        return artist;
    }

    public String getAlbum() {
        return album;
    }

    public String getTrack() {
        return track;
    }

    /** @return a path on the device, not a URL */
    public String getAlbumArtUrl() {
        return albumArtUrl;
    }

    public Integer getTotalTime() {
        return totalTime;
    }

    public Integer getPlayTime() {
        return playTime;
    }

    /**
     * @return the bit field the device sends as {@code attribute} (which controls the current source allows); the
     * meaning of the bits is not known, {@code null} if absent
     */
    public Long getAttribute() {
        return attribute;
    }

    /** @return the repeat words the current source accepts; empty when none or when the device does not report the list */
    public List<String> getRepeatAvailable() {
        return repeatAvailable;
    }

    /** @return the shuffle words the current source accepts; empty when none or when the device does not report the list */
    public List<String> getShuffleAvailable() {
        return shuffleAvailable;
    }
}
