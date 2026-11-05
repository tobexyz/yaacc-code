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

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.TimeZone;

/**
 * The target of a {@code Seek} with unit {@code REL_TIME}, shared by the states that accept a seek (Playing and Paused).
 */
final class SeekTarget {
    private SeekTarget() {
    }

    /**
     * @param target the {@code HH:mm:ss} of the action; {@code null} means the start
     * @return the position in milliseconds, or {@code null} if the text cannot be read (the seek is then ignored)
     */
    static Long millis(String target) {
        if (target == null) {
            return 0L;
        }
        SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");
        dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
        try {
            return dateFormat.parse(target).getTime();
        } catch (ParseException e) {
            return null;
        }
    }
}
