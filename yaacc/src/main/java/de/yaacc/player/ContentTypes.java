/*
 *
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
package de.yaacc.player;

/**
 * The type of what a controller sent to the renderer: the type of its description (the DIDL {@code protocolInfo}), else the
 * type of the file extension of its address. One rule for creating the player ({@code UpnpClient.initializePlayers}) and for
 * finding it again ({@code UpnpClient.getCurrentPlayers}); a type that nothing knows is {@code null}, never an empty text.
 */
public final class ContentTypes {

    private ContentTypes() {
    }

    /**
     * @return {@code described} if it says something, else {@code byExtension} if it does, else null; normalised
     * ({@link #normalize})
     */
    public static String pick(String described, String byExtension) {
        String type = normalize(described);
        return type != null ? type : normalize(byExtension);
    }

    /**
     * The one rule for what a content type is, for the gate that decides "music, play it into the group" and for the lookup
     * that finds the player again: trimmed, lower case, without parameters ({@code ;charset=...}). The description of a
     * controller keeps the case it was written in ({@code Audio/Mpeg}), and two code paths that compare the raw text can
     * disagree, which would leave a feed that no controller can stop.
     *
     * @return the normalised type, null for null or for text that has no type in it
     */
    public static String normalize(String type) {
        if (type == null) {
            return null;
        }
        String text = type;
        int parameters = text.indexOf(';');
        if (parameters >= 0) {
            text = text.substring(0, parameters);
        }
        text = text.trim().toLowerCase(java.util.Locale.ROOT);
        return text.isEmpty() ? null : text;
    }
}
