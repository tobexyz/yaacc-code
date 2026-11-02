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
package de.yaacc.upnp.server.http;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The HTTP date of RFC 9110 (IMF-fixdate, for example {@code Sun, 01 Nov 2026 17:55:36 GMT}): English day and month names,
 * a two digit day, the literal {@code GMT}. The date generator of httpcore5 5.3.6 formats with the default locale of the
 * phone (a German phone sent {@code So., 01 Nov. 2026 17:55:36 GMT+00:00}), so the app formats the header itself.
 */
public final class HttpDates {

    private static final DateTimeFormatter IMF_FIXDATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);

    private HttpDates() {
    }

    /** @return the HTTP date of the time, independent of the default locale and time zone of the device */
    public static String imfFixdate(long epochMillis) {
        return IMF_FIXDATE.format(Instant.ofEpochMilli(epochMillis));
    }

    /** @return the HTTP date of now */
    public static String now() {
        return imfFixdate(System.currentTimeMillis());
    }
}
