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
package de.yaacc.sendspin.player;

import de.yaacc.sendspin.client.DisplayRoles;
import de.yaacc.sendspin.session.ControllerMessages;

/**
 * What the display screen of this phone shows at one moment (task 11.5): taken from the display roles of a server's connection. An
 * immutable copy, so the screen reads it without a lock; a screen compares {@link #artworkVersion} to decode the image only when it changed.
 */
public final class DisplaySnapshot {
    public final String title;
    public final String artist;
    public final String album;
    /** Milliseconds into the track, or -1 if the server reports none. */
    public final long positionMs;
    /** Milliseconds, 0 if unknown or unlimited, -1 if there is no progress. */
    public final long durationMs;
    public final boolean playing;
    public final byte[] artwork;
    public final long artworkVersion;
    /** The colours of the color role, or null. */
    public final ControllerMessages.Color color;
    public final String serverName;

    DisplaySnapshot(String title, String artist, String album, long positionMs, long durationMs, boolean playing, byte[] artwork,
                    long artworkVersion, ControllerMessages.Color color, String serverName) {
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.positionMs = positionMs;
        this.durationMs = durationMs;
        this.playing = playing;
        this.artwork = artwork;
        this.artworkVersion = artworkVersion;
        this.color = color;
        this.serverName = serverName;
    }

    /** True if the server shows nothing on this display (no text, image or colour yet). */
    public boolean isEmpty() {
        return title == null && artist == null && album == null && artwork == null && color == null;
    }

    static DisplaySnapshot of(DisplayRoles roles, long serverNowMicros, String serverName) {
        ControllerMessages.Metadata m = roles.metadata(serverNowMicros);
        long position = roles.positionMs(serverNowMicros);
        long duration = m == null || m.progress == null ? -1 : m.progress.trackDurationMs;
        boolean playing = m != null && m.progress != null && m.progress.playbackSpeed > 0;
        return new DisplaySnapshot(m == null ? null : m.title, m == null ? null : m.artist, m == null ? null : m.album, position, duration, playing,
                roles.artwork(serverNowMicros), roles.artworkVersion(serverNowMicros), roles.color(serverNowMicros), serverName);
    }
}
