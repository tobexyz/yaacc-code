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

/** The {@code SourceXml} of the product service: the list of the sources of the device. */
public final class OhSourceXml {
    public static final String PLAYLIST = "Playlist";

    private OhSourceXml() {
    }

    /** The one source of YAACC's renderer: the playlist. Name, type and system name are all "Playlist". */
    public static String playlistOnly() {
        return source(PLAYLIST, PLAYLIST, true, PLAYLIST);
    }

    public static String source(String name, String type, boolean visible, String systemName) {
        return "<SourceList><Source><Name>" + OhEscape.xml(name) + "</Name><Type>" + OhEscape.xml(type) + "</Type><Visible>"
                + visible + "</Visible><SystemName>" + OhEscape.xml(systemName) + "</SystemName></Source></SourceList>";
    }
}
