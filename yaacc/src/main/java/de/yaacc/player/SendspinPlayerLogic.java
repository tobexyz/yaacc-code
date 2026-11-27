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
import de.yaacc.sendspin.hub.HubConnection;

/**
 * The decisions of {@link SendspinPlayer} that need no Android and no ExoPlayer, so that they are tested on the JVM: what the
 * phase of the connection means for a start, which text goes with which problem, and what a change of ExoPlayer's
 * play-when-ready flag means for the stream.
 */
final class SendspinPlayerLogic {

    private SendspinPlayerLogic() {
    }

    /** Why the player can not play to the Sendspin player. */
    enum Problem {
        NOT_READY, PAIRING_REQUIRED, RE_PAIR_NEEDED, PAIRING_FAILED, UNUSABLE, IN_USE, LOST
    }

    /** What the player does while it waits for the connection to be ready. */
    enum Wait {
        /** Keep waiting. */
        WAIT,
        /** The player waits for the operator: choosing it to play is the approval; approve, then keep waiting. */
        APPROVE,
        /** Available, nothing plays yet: declare the playback activity (once), then keep waiting. */
        ACTIVATE,
        /** Ready: start. */
        READY,
        /** Give up with this problem. */
        FAIL
    }

    static Wait waitFor(HubConnection.Phase phase) {
        switch (phase) {
            case READY:
                return Wait.READY;
            case NEEDS_APPROVAL:
                return Wait.APPROVE;
            case AVAILABLE:
                return Wait.ACTIVATE;
            case PAIRING_REQUIRED:
            case RE_PAIR_NEEDED:
            case CODE_PAIRING:
            case PAIRING_FAILED:
            case UNUSABLE:
            case ENDED:
                return Wait.FAIL;
            default:
                return Wait.WAIT;
        }
    }

    /** The problem of a phase that {@link #waitFor} says to give up on. */
    static Problem problemOf(HubConnection.Phase phase, HubConnection.EndReason reason) {
        switch (phase) {
            case PAIRING_REQUIRED:
            case CODE_PAIRING:
                return Problem.PAIRING_REQUIRED;
            case RE_PAIR_NEEDED:
                return Problem.RE_PAIR_NEEDED;
            case PAIRING_FAILED:
                return Problem.PAIRING_FAILED;
            case UNUSABLE:
                return Problem.UNUSABLE;
            case ENDED:
                return reason == HubConnection.EndReason.ANOTHER_SERVER || reason == HubConnection.EndReason.CONCURRENT_ATTEMPT
                        ? Problem.IN_USE : Problem.LOST;
            default:
                return Problem.NOT_READY;
        }
    }

    /** The string resource that tells the user what went wrong. Every problem has one. */
    static int textFor(Problem problem) {
        switch (problem) {
            case PAIRING_REQUIRED:
                return R.string.sendspin_error_pairing_required;
            case RE_PAIR_NEEDED:
                return R.string.sendspin_error_re_pair;
            case PAIRING_FAILED:
                return R.string.sendspin_error_pairing_failed;
            case UNUSABLE:
                return R.string.sendspin_error_unusable;
            case IN_USE:
                return R.string.sendspin_error_in_use;
            case LOST:
                return R.string.sendspin_error_lost;
            case NOT_READY:
            default:
                return R.string.sendspin_error_not_ready;
        }
    }

    /** What the stream does when ExoPlayer's play-when-ready flag changed. */
    enum StreamAction {
        /** ExoPlayer paused (the button, the notification, a headset, a call, the loss of audio focus): {@code stream/end}. */
        END,
        /** ExoPlayer resumed: audio is accepted again and the stream starts with the next chunk. */
        BEGIN
    }

    static StreamAction onPlayWhenReadyChanged(boolean playWhenReady) {
        return playWhenReady ? StreamAction.BEGIN : StreamAction.END;
    }
}
