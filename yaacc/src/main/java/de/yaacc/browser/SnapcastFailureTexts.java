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
package de.yaacc.browser;

import java.util.function.IntFunction;

import de.yaacc.R;
import de.yaacc.multiroom.control.SnapcastDisplayText;
import de.yaacc.multiroom.ui.SnapcastControls;

/**
 * The words for a change that failed on a Snapcast server, the counterpart of {@code SnapcastPlayerLogic.errorMessage} for
 * the controls of the rows and the group sheet. Pure (the strings come in through a function), so it is tested on the
 * JVM.
 *
 * <p>The user never sees raw server text: only a refusal of the server carries its reason, and that reason is cleaned
 * and shortened ({@link SnapcastDisplayText#toastReason}); every other kind is a fixed text of the app.
 */
final class SnapcastFailureTexts {

    private SnapcastFailureTexts() {
    }

    /** The string resource that tells what went wrong. Every kind has one. */
    static int textFor(SnapcastControls.Failure.Kind kind) {
        switch (kind) {
            case UNREACHABLE:
                return R.string.snapcast_feed_error_unreachable;
            case TIMEOUT:
                return R.string.snapcast_action_timeout;
            case UNAUTHORIZED:
                return R.string.snapcast_feed_error_unauthorized;
            case REJECTED:
                return R.string.snapcast_action_rejected;
            case GONE:
                return R.string.snapcast_feed_error_group_gone;
            case NOT_POSSIBLE:
                return R.string.snapcast_action_not_possible;
            case FAILED:
            default:
                return R.string.snapcast_action_failed;
        }
    }

    /** The text of a toast for a failure; the reason of a refusal follows in brackets. */
    static String message(SnapcastControls.Failure failure, IntFunction<String> texts) {
        StringBuilder message = new StringBuilder(texts.apply(textFor(failure.getKind())));
        if (failure.getKind() == SnapcastControls.Failure.Kind.REJECTED) {
            // cleaned once by the controls; once more here, because this is the last step before the screen
            String reason = SnapcastDisplayText.of(
                    SnapcastDisplayText.toastReason(failure.getReason(), SnapcastDisplayText.MAX_TOAST_REASON_CODE_POINTS),
                    SnapcastDisplayText.MAX_TOAST_REASON_CODE_POINTS);
            if (!reason.isEmpty()) {
                message.append(" (").append(reason).append(')');
            }
        }
        return message.toString();
    }
}
