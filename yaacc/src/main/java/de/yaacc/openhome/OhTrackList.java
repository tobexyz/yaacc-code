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

    public static String toXml(List<OhQueue.Entry> entries) {
        StringBuilder xml = new StringBuilder("<TrackList>");
        for (OhQueue.Entry entry : entries) {
            xml.append("<Entry><Id>").append(entry.getId()).append("</Id><Uri>").append(OhEscape.xml(entry.getUri()))
                    .append("</Uri><Metadata>").append(OhEscape.xml(entry.getMetadata())).append("</Metadata></Entry>");
        }
        return xml.append("</TrackList>").toString();
    }
}
