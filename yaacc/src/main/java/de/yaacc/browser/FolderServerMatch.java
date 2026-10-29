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
package de.yaacc.browser;

/**
 * Tells whether a device id (as stored for the selected provider, or the UDN of a device that was
 * added or removed) names the server whose folder the content list shows. Ids are compared without
 * the "uuid:" prefix, so the stored form and the form of a device identity match. No Android
 * classes: unit-testable on the JVM.
 */
final class FolderServerMatch {

    private static final String UUID_PREFIX = "uuid:";

    private FolderServerMatch() {
    }

    /**
     * @return true if both ids are set and name the same device
     */
    static boolean sameServer(String idA, String idB) {
        String a = normalize(idA);
        String b = normalize(idB);
        return !a.isEmpty() && a.equals(b);
    }

    private static String normalize(String id) {
        if (id == null) {
            return "";
        }
        String trimmed = id.trim();
        if (trimmed.regionMatches(true, 0, UUID_PREFIX, 0, UUID_PREFIX.length())) {
            return trimmed.substring(UUID_PREFIX.length());
        }
        return trimmed;
    }
}
