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
package de.yaacc.util;

/**
 * What a {@code saf_cache_<document uri>} value can be. The writers store the serialized {@link SAFMetadata}; the
 * preload used to store a bare duration ("00:03:12") under the same key, which {@link SAFMetadata#deserialize} cannot
 * parse, so the metadata was built again and the duration extracted a second time. Entries of that older kind may
 * still be stored: they are recognised here and upgraded when they are read. No Android classes.
 */
final class SafCacheEntries {

    private SafCacheEntries() {
    }

    /**
     * @return true if the stored value is a duration on its own, as the former preload wrote it ("HH:MM:SS", the hours
     * may have more than two digits)
     */
    static boolean isBareDuration(String stored) {
        if (stored == null || stored.indexOf('|') >= 0) {
            return false;
        }
        String[] parts = stored.split(":", -1);
        if (parts.length != 3) {
            return false;
        }
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.length() < (i == 0 ? 1 : 2) || (i > 0 && part.length() != 2)) {
                return false;
            }
            for (int c = 0; c < part.length(); c++) {
                if (!Character.isDigit(part.charAt(c))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * @return the serialized metadata, or null if the value is missing, a bare duration or corrupt (never throws)
     */
    static SAFMetadata parseMetadata(String stored) {
        if (stored == null || isBareDuration(stored)) {
            return null;
        }
        try {
            return SAFMetadata.deserialize(stored);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * @return the duration an entry holds, in either form; empty if the entry has none (yet) or is not readable
     */
    static String durationOf(String stored) {
        if (isBareDuration(stored)) {
            return stored;
        }
        SAFMetadata metadata = parseMetadata(stored);
        return metadata == null || metadata.duration == null ? "" : metadata.duration;
    }
}
