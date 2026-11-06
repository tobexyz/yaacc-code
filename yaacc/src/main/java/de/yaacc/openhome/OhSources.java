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
import java.util.Locale;

/** The sources of a device, read from the {@code SourceXml} of its product service. */
public final class OhSources {
    /** The most sources that are read from a device. */
    public static final int MAX_SOURCES = 64;

    /** One source: its position in the list is the index to select it with. */
    public static final class Source {
        public final int index;
        public final String name;
        public final String type;
        public final boolean visible;
        public final String systemName;

        Source(int index, String name, String type, boolean visible, String systemName) {
            this.index = index;
            this.name = name;
            this.type = type;
            this.visible = visible;
            this.systemName = systemName;
        }
    }

    private OhSources() {
    }

    /** @return the sources in the order of the list, empty for text that is not a source list */
    public static List<Source> parse(String sourceXml) {
        Document document = OhXml.parse(sourceXml);
        if (document == null || !"SourceList".equals(document.getDocumentElement().getTagName())) {
            return Collections.emptyList();
        }
        List<Source> sources = new ArrayList<>();
        for (Element source : OhXml.children(document.getDocumentElement(), "Source")) {
            if (sources.size() >= MAX_SOURCES) {
                break;
            }
            sources.add(new Source(sources.size(), OhXml.text(source, "Name"), OhXml.text(source, "Type"),
                    parseBoolean(OhXml.text(source, "Visible"), true), OhXml.text(source, "SystemName")));
        }
        return sources;
    }

    /** @return the first source of the type "Playlist", null if the device has none */
    public static Source findPlaylist(List<Source> sources) {
        for (Source source : sources) {
            if (OhSourceXml.PLAYLIST.toLowerCase(Locale.ROOT).equals(source.type.toLowerCase(Locale.ROOT))) {
                return source;
            }
        }
        return null;
    }

    static boolean parseBoolean(String text, boolean fallback) {
        if (text == null) {
            return fallback;
        }
        String value = text.trim().toLowerCase(Locale.ROOT);
        if (value.equals("true") || value.equals("1") || value.equals("yes")) {
            return true;
        }
        if (value.equals("false") || value.equals("0") || value.equals("no")) {
            return false;
        }
        return fallback;
    }
}
