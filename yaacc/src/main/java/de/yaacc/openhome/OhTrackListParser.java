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

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Reads the answer of {@code ReadList}: {@code <TrackList><Entry><Id/><Uri/><Metadata/></Entry>...</TrackList>}. */
public final class OhTrackListParser {
    private OhTrackListParser() {
    }

    /** Entries with an id that is not a valid id are left out. */
    public static List<OhQueue.Entry> parse(String trackListXml) {
        Document document = OhXml.parse(trackListXml);
        if (document == null || !"TrackList".equals(document.getDocumentElement().getTagName())) {
            return Collections.emptyList();
        }
        List<OhQueue.Entry> entries = new ArrayList<>();
        for (Element entry : OhXml.children(document.getDocumentElement(), "Entry")) {
            long id;
            try {
                id = Long.parseLong(OhXml.text(entry, "Id"));
            } catch (NumberFormatException e) {
                continue;
            }
            if (OhIds.isId(id)) {
                entries.add(new OhQueue.Entry(id, OhXml.text(entry, "Uri"), OhXml.text(entry, "Metadata")));
            }
        }
        return entries;
    }
}
