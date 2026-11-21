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
package de.yaacc.fcast.discovery;

import java.util.ArrayList;
import java.util.List;

import de.yaacc.R;

/**
 * The text that the "Add receiver" input shows for a {@link FCastAddReceiver.Result} (tasks 5.4, 5.7): one or more lines of string
 * resources. The failure of a name says what was tried (the receivers found, the system's name lookup, the wait for an announcement,
 * or that discovery is off), so that the user can see whether the name, the network or mDNS is the problem. Pure.
 */
public final class FCastAddReceiverText {

    /** One line: a string resource and its arguments. */
    public static final class Line {
        private final int resId;
        private final Object[] args;

        Line(int resId, Object... args) {
            this.resId = resId;
            this.args = args;
        }

        public int getResId() {
            return resId;
        }

        public Object[] getArgs() {
            return args;
        }
    }

    private FCastAddReceiverText() {
    }

    /** @return the lines for the user, empty for a result that needs no message ({@link FCastAddReceiver.Outcome#ADDED}) */
    public static List<Line> lines(FCastAddReceiver.Result result) {
        List<Line> lines = new ArrayList<>();
        switch (result.getOutcome()) {
            case ADDED:
                break;
            case EMPTY:
                lines.add(new Line(R.string.fcast_receivers_error_empty));
                break;
            case FULL:
                lines.add(new Line(R.string.fcast_receivers_error_full, FCastStoredReceivers.MAX_MANUAL));
                break;
            case ALREADY_LISTED:
                lines.add(new Line(R.string.fcast_receivers_error_already));
                break;
            case UNREACHABLE:
                lines.add(new Line(R.string.fcast_receivers_error_unreachable, result.getTriedAddresses()));
                break;
            case BAD_LINK:
                switch (result.getLinkProblem()) {
                    case NO_ADDRESS:
                        lines.add(new Line(R.string.fcast_receivers_error_link_no_address));
                        break;
                    case NO_SERVICE:
                        lines.add(new Line(R.string.fcast_receivers_error_link_no_service));
                        break;
                    default:
                        lines.add(new Line(R.string.fcast_receivers_error_bad_link));
                        break;
                }
                break;
            case AMBIGUOUS:
                lines.add(new Line(R.string.fcast_receivers_error_ambiguous, String.join(", ", result.getCandidates())));
                break;
            case NAME_NOT_FOUND:
                lines.add(new Line(R.string.fcast_receivers_error_not_found));
                lines.add(new Line(R.string.fcast_receivers_tried_found));
                if (result.isTriedSystemLookup()) {
                    lines.add(new Line(R.string.fcast_receivers_tried_system));
                }
                if (result.isTriedSearch()) {
                    lines.add(new Line(R.string.fcast_receivers_tried_search,
                            (int) (FCastAddReceiver.SEARCH_ROUNDS * FCastAddReceiver.SEARCH_ROUND_MILLIS / 1000)));
                } else {
                    lines.add(new Line(R.string.fcast_receivers_tried_search_off));
                }
                break;
            case INVALID:
            default:
                lines.add(new Line(R.string.fcast_receivers_error_invalid));
                break;
        }
        return lines;
    }
}
