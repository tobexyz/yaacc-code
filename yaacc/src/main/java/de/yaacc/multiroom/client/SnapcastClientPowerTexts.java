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
package de.yaacc.multiroom.client;

import android.content.Context;

import de.yaacc.R;

/**
 * The words for the state of the phone's own Snapcast client (the switch on the first page, the listen test) and for the reason of a
 * start that did not happen. The choice of the string is pure (and tested on the JVM); only {@link #stateText} and
 * {@link #refusalText} need a context.
 */
public final class SnapcastClientPowerTexts {

    private SnapcastClientPowerTexts() {
    }

    /** The string resource for the state; for a stopped client with a known ending it takes the reason as its argument. */
    public static int stateTextRes(SnapcastClientPower.State state) {
        switch (state.shown) {
            case CONNECTING:
                return R.string.snapcast_client_notification_connecting;
            case PLAYING:
                return R.string.snapcast_client_notification_playing;
            case RECONNECTING:
                return R.string.snapcast_client_notification_reconnecting;
            case STOPPED:
                return reasonOf(state).isEmpty() ? R.string.snapcast_client_state_stopped : R.string.snapcast_client_notification_stopped;
            case OFF:
            default:
                return R.string.snapcast_client_state_off;
        }
    }

    /** The reason the last session ended, in words; empty when there is none (or the name is not an ending). */
    public static String reasonOf(SnapcastClientPower.State state) {
        if (state.endingName == null || state.endingName.isEmpty()) {
            return "";
        }
        try {
            return SnapcastClientStatusText.endingText(SnapcastClientRuntime.Ending.valueOf(state.endingName));
        } catch (IllegalArgumentException unknown) {
            return "";
        }
    }

    /** The text for a start that did not happen; 0 for an outcome that is no refusal. */
    public static int refusalTextRes(SnapcastClientPower.Outcome outcome) {
        switch (outcome) {
            case OPTION_OFF:
                return R.string.snapcast_client_refused_option_off;
            case NO_SERVER:
                return R.string.snapcast_client_refused_no_server;
            case BAD_SERVER:
                return R.string.snapcast_client_refused_bad_server;
            case NOT_ALLOWED:
                return R.string.snapcast_client_refused_not_allowed;
            default:
                return 0;
        }
    }

    /** The line for the state: "Playing", "Off", "Stopped: <reason>". */
    public static String stateText(Context context, SnapcastClientPower.State state) {
        int res = stateTextRes(state);
        return res == R.string.snapcast_client_notification_stopped ? context.getString(res, reasonOf(state)) : context.getString(res);
    }

    /** The reason for a refusal, or null for an outcome that is none. */
    public static String refusalText(Context context, SnapcastClientPower.Outcome outcome) {
        int res = refusalTextRes(outcome);
        return res == 0 ? null : context.getString(res);
    }
}
