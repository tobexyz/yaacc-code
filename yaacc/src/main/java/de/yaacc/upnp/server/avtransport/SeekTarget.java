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
package de.yaacc.upnp.server.avtransport;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The target of a {@code Seek} with unit {@code REL_TIME}, shared by the states that accept a seek (Playing and Paused).
 * <p>
 * The text comes from any control point in the network, so it is read strictly: {@code H+:MM:SS} with an optional fraction of a
 * second (the UPnP form), hours of at most four digits, minutes and seconds below 60, at most {@link #MAX_LENGTH} characters.
 * Anything else (a sign, a longer number, trailing text, a lenient 99:99:99) is not a position and the seek is ignored. The old
 * lenient date parser took negative and huge numbers and was slow on very long digit strings, under the lock of the transport.
 */
final class SeekTarget {
    static final int MAX_LENGTH = 32;
    private static final Pattern TIME_CODE = Pattern.compile("(\\d{1,4}):([0-5]\\d):([0-5]\\d)(?:\\.(\\d{1,3}))?");

    private SeekTarget() {
    }

    /**
     * @param target the time code of the action; {@code null} means the start
     * @return the position in milliseconds (never negative), or {@code null} if the text is not a time code (the seek is then ignored)
     */
    static Long millis(String target) {
        if (target == null) {
            return 0L;
        }
        if (target.length() > MAX_LENGTH) {
            return null;
        }
        Matcher matcher = TIME_CODE.matcher(target);
        if (!matcher.matches()) {
            return null;
        }
        long hours = Long.parseLong(matcher.group(1));
        long minutes = Long.parseLong(matcher.group(2));
        long seconds = Long.parseLong(matcher.group(3));
        long millis = 0;
        String fraction = matcher.group(4);
        if (fraction != null) {
            millis = Long.parseLong((fraction + "00").substring(0, 3));
        }
        return ((hours * 60 + minutes) * 60 + seconds) * 1000 + millis;
    }
}
