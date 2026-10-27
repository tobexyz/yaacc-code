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
package de.yaacc.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The logcat command of the log viewer. "Clear" is a time marker: only entries after it are shown, so
 * it works even where the system refuses to empty the log buffer of an app.
 */
public final class LogCommand {

    /** The time format logcat accepts for {@code -T}, in the time zone of the device. */
    static final String SINCE_FORMAT = "MM-dd HH:mm:ss.SSS";

    private LogCommand() {
    }

    /** Valid log levels of the setting; anything else shows errors only. */
    static String safeLevel(String level) {
        if (level != null && level.length() == 1 && "VDIWEF".contains(level)) {
            return level;
        }
        return "E";
    }

    /**
     * @param level          the configured log level (V, D, I, W, E, F)
     * @param clearedAtMillis when the user cleared the log, or 0 if never
     * @param zone           the time zone of the device, the one logcat prints in
     * @return the command with its arguments, for {@code Runtime#exec(String[])}
     */
    public static String[] build(String level, long clearedAtMillis, TimeZone zone) {
        String filter = "*:" + safeLevel(level);
        if (clearedAtMillis <= 0) {
            return new String[]{"logcat", "-d", filter};
        }
        SimpleDateFormat format = new SimpleDateFormat(SINCE_FORMAT, Locale.US);
        format.setTimeZone(zone);
        return new String[]{"logcat", "-d", "-T", format.format(new Date(clearedAtMillis)), filter};
    }

    /** The command that tries to empty the buffer as well; the marker is what makes "clear" reliable. */
    public static String[] clearBuffer() {
        return new String[]{"logcat", "-c"};
    }
}
