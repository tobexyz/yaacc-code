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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The short ids the content directory hands to clients for SAF documents, and the way back from an id to the
 * document uri. Reads are lock free (concurrent maps); creating and restoring ids is synchronized, so one uri never
 * gets two ids. No Android classes.
 * <p>
 * Persistent ids are consecutive numbers from a counter ("1", "2", ...); the counter only ever grows, so a number
 * is never given to another uri (see {@link #restoreCounter(long)} and {@link #restore(String, String)}). Transient
 * ids ("t1", ...) are for the rare case that the stored state is not loaded yet and the caller must not wait: they
 * resolve within this process, are never persisted and cannot collide with a persistent id.
 */
final class SafShortIds {

    private static final String TRANSIENT_PREFIX = "t";

    private final Map<String, String> idToUri = new ConcurrentHashMap<>();
    private final Map<String, String> uriToId = new ConcurrentHashMap<>();
    private final Map<String, String> transientByUri = new ConcurrentHashMap<>();
    private long counter = 1;
    private long transientCounter = 1;

    /** @return the persistent id of the uri, null if it has none (yet) */
    String existing(String uri) {
        return uriToId.get(uri);
    }

    /** @return the uri of the id (persistent or transient), null if unknown */
    String uriFor(String id) {
        return id == null ? null : idToUri.get(id);
    }

    /** Returns the id of the uri, creating the next persistent one if it has none. */
    synchronized String getOrCreate(String uri) {
        String existing = uriToId.get(uri);
        if (existing != null) {
            return existing;
        }
        String id = String.valueOf(counter++);
        uriToId.put(uri, id);
        idToUri.put(id, uri);
        return id;
    }

    /** An id that is valid in this process only; the same uri gets the same transient id. */
    synchronized String getOrCreateTransient(String uri) {
        String existing = transientByUri.get(uri);
        if (existing != null) {
            return existing;
        }
        String id = TRANSIENT_PREFIX + transientCounter++;
        transientByUri.put(uri, id);
        idToUri.put(id, uri);
        return id;
    }

    /**
     * Takes over a stored mapping. A numeric id at or above the counter moves the counter behind it, so
     * a stored id is never given out again.
     */
    synchronized void restore(String id, String uri) {
        if (id == null || uri == null) {
            return;
        }
        idToUri.put(id, uri);
        uriToId.put(uri, id);
        try {
            long numeric = Long.parseLong(id);
            if (numeric >= counter) {
                counter = numeric + 1;
            }
        } catch (NumberFormatException e) {
            // a non-numeric id is not from the counter
        }
    }

    /** Takes over the stored counter; it never moves back. */
    synchronized void restoreCounter(long stored) {
        if (stored > counter) {
            counter = stored;
        }
    }

    /** The next number the counter will give out. */
    synchronized long counter() {
        return counter;
    }

    /** Forgets every id; the numbering starts again at 1. */
    synchronized void reset() {
        idToUri.clear();
        uriToId.clear();
        transientByUri.clear();
        counter = 1;
        transientCounter = 1;
    }

    int size() {
        return idToUri.size();
    }
}
