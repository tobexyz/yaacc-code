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
package de.yaacc.multiroom.feed;

/**
 * Why a feed to a Snapcast group could not be started or ended on its own: a kind for the UI to pick a text, the
 * server's reason as display text (already cleaned and cut by {@link SnapcastStreams}; null if there is none) and the
 * key of a hint text. No address, no exception, nothing that could carry one: it is shown to the user and may be
 * written to a log.
 */
public final class FeedError {

    /** What went wrong. */
    public enum Kind {
        /** The control connection to the server is not up (or was never made). */
        NO_CONTROL,
        /** The server refused to add the stream: use a static source (see the hint). */
        REFUSED,
        /** The server wants a login, which YAACC does not support. */
        UNAUTHORIZED,
        /** Every port of YAACC's range is named by a stream of the server. */
        NO_FREE_PORT,
        /** A stream of YAACC with another configuration is in use by a group. */
        CONFLICT,
        /** A control call failed (timeout, connection problem, unreadable answer). */
        SERVER_FAILED,
        /** The group is not on the server (any more). */
        GROUP_GONE,
        /** The connection to the stream port could not be opened. */
        CONNECT_FAILED,
        /** Another group of the same server is fed from this phone; one feed per server at a time. */
        BUSY,
        /** The feed broke while playing (the server closed it, the network went away, a stall). */
        FEED_FAILED,
        /** The control connection was lost while playing. */
        CONTROL_LOST,
        /** ExoPlayer failed (a source that can not be read, a track the audio tap refuses). */
        PLAYER_FAILED
    }

    private final Kind kind;
    private final String reason;
    private final String hintKey;

    public FeedError(Kind kind, String reason, String hintKey) {
        if (kind == null) {
            throw new IllegalArgumentException("no kind");
        }
        this.kind = kind;
        this.reason = reason;
        this.hintKey = hintKey;
    }

    public static FeedError of(Kind kind) {
        return new FeedError(kind, null, null);
    }

    public Kind getKind() {
        return kind;
    }

    /** The server's reason as display text, or null. */
    public String getReason() {
        return reason;
    }

    /** The resource key of a hint text, for example {@link SnapcastStreams#HINT_STATIC_SOURCE}, or null. */
    public String getHintKey() {
        return hintKey;
    }

    @Override
    public String toString() {
        return "FeedError{" + kind + "}";
    }
}
