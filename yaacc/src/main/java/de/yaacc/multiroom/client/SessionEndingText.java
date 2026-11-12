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

import java.util.Locale;

/** The line about how the last session ended, kept across the end of the service and of the process. Pure. */
public final class SessionEndingText {

    private SessionEndingText() {
    }

    /**
     * @param endingName the name of a {@link SnapcastClientRuntime.Ending} as stored; null or unknown: no line
     * @param atMillis   when it ended (wall clock); 0 when not known
     * @param nowMillis  now (wall clock)
     * @return a line, or the empty string when there is nothing to say
     */
    public static String of(String endingName, long atMillis, long nowMillis) {
        if (endingName == null || endingName.isEmpty()) {
            return "";
        }
        SnapcastClientRuntime.Ending ending;
        try {
            ending = SnapcastClientRuntime.Ending.valueOf(endingName);
        } catch (IllegalArgumentException unknown) {
            return "";
        }
        if (ending == SnapcastClientRuntime.Ending.NONE) {
            return "";
        }
        String when = "";
        if (atMillis > 0 && nowMillis >= atMillis) {
            long seconds = (nowMillis - atMillis) / 1000L;
            when = seconds < 120 ? seconds + " s ago" : seconds < 7200 ? seconds / 60 + " min ago" : seconds / 3600 + " h ago";
        }
        return String.format(Locale.ROOT, "last session ended%s: %s", when.isEmpty() ? "" : " " + when,
                SnapcastClientStatusText.endingText(ending));
    }
}
