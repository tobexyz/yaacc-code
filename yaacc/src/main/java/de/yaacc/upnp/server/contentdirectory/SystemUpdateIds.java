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
package de.yaacc.upnp.server.contentdirectory;

/** The start value of the ContentDirectory's {@code SystemUpdateID}. */
public final class SystemUpdateIds {

    private static final long UI4_MASK = 0xFFFFFFFFL;

    private SystemUpdateIds() {
    }

    /**
     * The start value for a server started at {@code nowMillis}: the seconds since the epoch, as an unsigned 32 bit number
     * (until the year 2106) and at least 1. A later start gives a higher number, so a control point that saw the former run's
     * number sees a different one and looks at the content again.
     */
    public static long startValue(long nowMillis) {
        long seconds = (nowMillis / 1000L) & UI4_MASK;
        return Math.max(1L, seconds);
    }
}
