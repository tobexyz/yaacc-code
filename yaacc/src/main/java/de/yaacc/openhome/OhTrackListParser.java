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

    /**
     * Reads the entries the caller asked for. Entries with an id that is not a valid id or was not asked for, with an address that is
     * empty or longer than {@link OhQueue#MAX_URI_LENGTH} and with metadata longer than {@link OhQueue#MAX_METADATA_LENGTH} are left
     * out, and so is everything after as many entries as were asked for.
     *
     * @param wanted the ids that were asked for
     */
    public static List<OhQueue.Entry> parse(String trackListXml, java.util.Set<Long> wanted) {
        Document document = OhXml.parse(trackListXml);
        if (document == null || !"TrackList".equals(document.getDocumentElement().getTagName())) {
            return Collections.emptyList();
        }
        List<OhQueue.Entry> entries = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (Element entry : OhXml.children(document.getDocumentElement(), "Entry")) {
            if (entries.size() >= wanted.size()) {
                break;
            }
            long id;
            try {
                id = Long.parseLong(OhXml.text(entry, "Id"));
            } catch (NumberFormatException e) {
                continue;
            }
            String uri = OhXml.text(entry, "Uri");
            String metadata = OhXml.text(entry, "Metadata");
            if (OhIds.isId(id) && wanted.contains(id) && seen.add(id) && !uri.isEmpty()
                    && uri.length() <= OhQueue.MAX_URI_LENGTH && metadata.length() <= OhQueue.MAX_METADATA_LENGTH) {
                entries.add(new OhQueue.Entry(id, uri, metadata));
            }
        }
        return entries;
    }
}
