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

import android.content.SharedPreferences;

import java.util.Locale;
import java.util.Objects;

/**
 * How one player continues after an item: repeat (off, all, one) and shuffle (off, on). Immutable, plain
 * JVM. The two settings "replay playlist" and "shuffle music" only give the mode a new player starts with
 * ({@link #fromSettings}); after that the mode belongs to the player and is never written back.
 * <p>
 * The UPnP AVTransport state variable {@code CurrentPlayMode} knows five modes that cover the combinations
 * (see {@link #toUpnpPlayMode()}): the mapping is the one Home Assistant uses for its repeat and shuffle
 * services.
 */
public final class PlayMode {

    /** What happens when an item has ended. */
    public enum RepeatMode {
        /** Play the list once, then stop. */
        OFF,
        /** Start again with the first item after the last one. */
        ALL,
        /** Play the same item again. */
        ONE;

        /** @return the mode a click on the repeat button switches to: OFF, ALL, ONE, OFF, ... */
        public RepeatMode next() {
            switch (this) {
                case OFF:
                    return ALL;
                case ALL:
                    return ONE;
                default:
                    return OFF;
            }
        }
    }

    public static final String UPNP_NORMAL = "NORMAL";
    public static final String UPNP_SHUFFLE = "SHUFFLE";
    public static final String UPNP_REPEAT_ONE = "REPEAT_ONE";
    public static final String UPNP_REPEAT_ALL = "REPEAT_ALL";
    public static final String UPNP_RANDOM = "RANDOM";

    /** Repeat off, shuffle off. */
    public static final PlayMode NORMAL = new PlayMode(RepeatMode.OFF, false);

    private final RepeatMode repeat;
    private final boolean shuffle;

    private PlayMode(RepeatMode repeat, boolean shuffle) {
        this.repeat = repeat;
        this.shuffle = shuffle;
    }

    public static PlayMode of(RepeatMode repeat, boolean shuffle) {
        return new PlayMode(repeat == null ? RepeatMode.OFF : repeat, shuffle);
    }

    /** @return the mode a new player starts with: "replay playlist" on means repeat all, off repeat off */
    public static PlayMode fromSettings(boolean replayPlaylist, boolean shuffle) {
        return of(replayPlaylist ? RepeatMode.ALL : RepeatMode.OFF, shuffle);
    }

    /**
     * Reads the two settings (defaults as in the settings screen: replay off, shuffle off).
     *
     * @param replayKey  the key of the "replay playlist" setting
     * @param shuffleKey the key of the "shuffle music" setting
     */
    public static PlayMode fromPreferences(SharedPreferences preferences, String replayKey, String shuffleKey) {
        return fromSettings(preferences.getBoolean(replayKey, false), preferences.getBoolean(shuffleKey, false));
    }

    public RepeatMode getRepeat() {
        return repeat;
    }

    public boolean isShuffle() {
        return shuffle;
    }

    public PlayMode withRepeat(RepeatMode newRepeat) {
        return of(newRepeat, shuffle);
    }

    public PlayMode withShuffle(boolean newShuffle) {
        return of(repeat, newShuffle);
    }

    /** @return this mode with the repeat state a click on the repeat button leads to */
    public PlayMode nextRepeat() {
        return withRepeat(repeat.next());
    }

    /** @return this mode with shuffle switched */
    public PlayMode toggleShuffle() {
        return withShuffle(!shuffle);
    }

    /**
     * @return the {@code CurrentPlayMode} value of UPnP AVTransport: repeat one is {@code REPEAT_ONE}
     * (with or without shuffle, the protocol has no combination), repeat all {@code REPEAT_ALL} or, with
     * shuffle, {@code RANDOM} (endless shuffle); shuffle alone is {@code SHUFFLE} (every item once in random
     * order) and nothing is {@code NORMAL}
     */
    public String toUpnpPlayMode() {
        if (repeat == RepeatMode.ONE) {
            return UPNP_REPEAT_ONE;
        }
        if (repeat == RepeatMode.ALL) {
            return shuffle ? UPNP_RANDOM : UPNP_REPEAT_ALL;
        }
        return shuffle ? UPNP_SHUFFLE : UPNP_NORMAL;
    }

    /**
     * @param upnpPlayMode a {@code CurrentPlayMode} value
     * @return the mode; {@code NORMAL}, the modes yaacc does not have ({@code DIRECT_1}, {@code INTRO}),
     * unknown values and null all give repeat off and shuffle off
     */
    public static PlayMode fromUpnpPlayMode(String upnpPlayMode) {
        if (upnpPlayMode == null) {
            return NORMAL;
        }
        switch (upnpPlayMode.trim().toUpperCase(Locale.ROOT)) {
            case UPNP_REPEAT_ONE:
                return of(RepeatMode.ONE, false);
            case UPNP_REPEAT_ALL:
                return of(RepeatMode.ALL, false);
            case UPNP_SHUFFLE:
                return of(RepeatMode.OFF, true);
            case UPNP_RANDOM:
                return of(RepeatMode.ALL, true);
            default:
                return NORMAL;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PlayMode)) {
            return false;
        }
        PlayMode other = (PlayMode) o;
        return repeat == other.repeat && shuffle == other.shuffle;
    }

    @Override
    public int hashCode() {
        return Objects.hash(repeat, shuffle);
    }

    @Override
    public String toString() {
        return "PlayMode{repeat=" + repeat + ", shuffle=" + shuffle + '}';
    }
}
