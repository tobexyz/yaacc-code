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

import de.yaacc.R;
import de.yaacc.multiroom.control.SnapcastDisplayText;
import de.yaacc.multiroom.feed.FeedError;
import de.yaacc.multiroom.feed.SnapcastStreams;

/**
 * The decisions of {@link SnapcastPlayer} that need no Android and no ExoPlayer, so that they are tested on the JVM:
 * what a change of ExoPlayer's play-when-ready flag means for the feed, which text goes with which feed error, and the
 * player id.
 */
final class SnapcastPlayerLogic {

    private SnapcastPlayerLogic() {
    }

    /** What the feed must do when ExoPlayer's play-when-ready flag changed. */
    enum ExoAction {
        /** Nothing. */
        NONE,
        /** ExoPlayer paused (the button, the notification, a headset, a call, the loss of audio focus): drop what is queued. */
        DISCARD,
        /** ExoPlayer resumed from outside (the notification, a headset) while the feed is closed (a long pause): open it. */
        START_FEED
    }

    static ExoAction onPlayWhenReadyChanged(boolean playWhenReady, boolean feeding) {
        if (!playWhenReady) {
            return ExoAction.DISCARD;
        }
        return feeding ? ExoAction.NONE : ExoAction.START_FEED;
    }

    /** The string resource that tells the user what went wrong. Every kind has one. */
    static int textFor(FeedError.Kind kind) {
        switch (kind) {
            case NO_CONTROL:
            case SERVER_FAILED:
                return R.string.snapcast_feed_error_unreachable;
            case REFUSED:
            case NO_FREE_PORT:
            case CONFLICT:
                return R.string.snapcast_feed_error_refused;
            case UNAUTHORIZED:
                return R.string.snapcast_feed_error_unauthorized;
            case GROUP_GONE:
                return R.string.snapcast_feed_error_group_gone;
            case CONNECT_FAILED:
                return R.string.snapcast_feed_error_connect;
            case BUSY:
                return R.string.snapcast_feed_error_busy;
            case PLAYER_FAILED:
                return R.string.snapcast_feed_error_player;
            case FEED_FAILED:
            case CONTROL_LOST:
            default:
                return R.string.snapcast_feed_error_lost;
        }
    }

    /** The string resource of a hint key of {@link SnapcastStreams}, or 0 for none or an unknown key. */
    static int hintFor(String hintKey) {
        if (SnapcastStreams.HINT_STATIC_SOURCE.equals(hintKey)) {
            return R.string.snapcast_feed_static_source_hint;
        }
        if (SnapcastStreams.HINT_AUTHORIZATION.equals(hintKey)) {
            return R.string.snapcast_feed_authorization_hint;
        }
        return 0;
    }

    /**
     * The text of a toast for a feed error: the text of the kind, the server's reason in brackets if there is one (display
     * text, already cleaned and cut by the stream handling), and the hint on a line of its own.
     *
     * @param texts looks up a string resource
     */
    static String errorMessage(FeedError error, java.util.function.IntFunction<String> texts) {
        StringBuilder message = new StringBuilder(texts.apply(textFor(error.getKind())));
        String reason = toastReason(error.getReason());
        if (!reason.isEmpty()) {
            message.append(" (").append(reason).append(')');
        }
        int hint = hintFor(error.getHintKey());
        if (hint != 0) {
            message.append('\n').append(texts.apply(hint));
        }
        return message.toString();
    }

    /** The most code points of a server's reason that a toast shows. */
    static final int MAX_REASON_CODE_POINTS = SnapcastDisplayText.MAX_TOAST_REASON_CODE_POINTS;

    /**
     * The server's own words, as little of them as makes sense in a toast that looks like the app's: the characters of
     * the category "format" (zero width space and joiners, word joiner and invisible operators, soft hyphen, byte order
     * mark, bidi controls) are removed, and at most {@value #MAX_REASON_CODE_POINTS} code points are kept (a pair is never
     * cut). The stream handling has replaced control characters and line separators already. Empty when nothing is left.
     * The rule is shared with the controls of the Snapcast rows ({@link SnapcastDisplayText#toastReason}).
     */
    static String toastReason(String reason) {
        return SnapcastDisplayText.toastReason(reason, MAX_REASON_CODE_POINTS);
    }

    /**
     * The id of a Snapcast player: above the fixed notification ids of the app (1 to 10) and 0 (which the player
     * service refuses), and different for each player, like the ids of the AVTransport players.
     */
    static int playerIdFrom(int raw) {
        return 100 + (raw & 0x3fffffff) % 1_000_000_000;
    }
}
