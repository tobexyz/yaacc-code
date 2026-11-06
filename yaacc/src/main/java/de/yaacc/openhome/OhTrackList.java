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
package de.yaacc.openhome;

import java.util.List;

/** The answer of {@code ReadList}: {@code <TrackList><Entry><Id/><Uri/><Metadata/></Entry>...</TrackList>}, texts escaped. */
public final class OhTrackList {
    private OhTrackList() {
    }

    /** The most characters of a track list answer. */
    public static final int MAX_LENGTH = 1024 * 1024;

    public static String toXml(List<OhQueue.Entry> entries) {
        return toXml(entries, MAX_LENGTH);
    }

    /** Entries that would take the text over {@code maxLength} characters (before XML escaping of the whole text) are left out. */
    public static String toXml(List<OhQueue.Entry> entries, int maxLength) {
        StringBuilder xml = new StringBuilder("<TrackList>");
        for (OhQueue.Entry entry : entries) {
            String one = "<Entry><Id>" + entry.getId() + "</Id><Uri>" + OhEscape.xml(entry.getUri()) + "</Uri><Metadata>"
                    + OhEscape.xml(entry.getMetadata()) + "</Metadata></Entry>";
            if (xml.length() + one.length() > maxLength) {
                break;
            }
            xml.append(one);
        }
        return xml.append("</TrackList>").toString();
    }
}
