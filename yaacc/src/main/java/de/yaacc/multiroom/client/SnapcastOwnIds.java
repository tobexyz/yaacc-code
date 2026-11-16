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
package de.yaacc.multiroom.client;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Every client id that is THIS phone's on a server (task 12.19): the current id, one for all servers ({@link SnapcastClientIdentity}),
 * and the <b>legacy</b> ids of this install, the per-server ids that earlier builds gave each server and that are still on the servers
 * as offline entries ({@link SnapcastLegacyClientIds}). The legacy ids are computed from the install secret, so they are only ever
 * the ones of THIS install; the entries of another YAACC install are not recognised. Immutable; it never prints an id.
 *
 * <p>Without a current id (this install has no secret, so it never started a client) there is no legacy id either: nothing is own.
 */
public final class SnapcastOwnIds {

    private static final SnapcastOwnIds NONE = new SnapcastOwnIds(null, Collections.<String>emptySet());

    private final String current;
    private final Set<String> legacy;

    private SnapcastOwnIds(String current, Set<String> legacy) {
        this.current = current;
        this.legacy = legacy;
    }

    /** Nothing is the phone's. */
    public static SnapcastOwnIds none() {
        return NONE;
    }

    /**
     * @param current the id the phone gives every server; null or empty for none (then the legacy ids are dropped too)
     * @param legacy  the per-server ids of earlier builds; null, empty and the current id entries are left out
     */
    public static SnapcastOwnIds of(String current, Collection<String> legacy) {
        if (current == null || current.isEmpty()) {
            return NONE;
        }
        Set<String> kept = new LinkedHashSet<>();
        if (legacy != null) {
            for (String id : legacy) {
                if (id != null && !id.isEmpty() && !id.equals(current)) {
                    kept.add(id);
                }
            }
        }
        return new SnapcastOwnIds(current, Collections.unmodifiableSet(kept));
    }

    /** @return the current id, null for none */
    public String current() {
        return current;
    }

    /** @return the legacy ids, never containing the current one */
    public Set<String> legacy() {
        return legacy;
    }

    public boolean isCurrent(String id) {
        return current != null && current.equals(id);
    }

    public boolean isLegacy(String id) {
        return id != null && legacy.contains(id);
    }

    /** The current id or a legacy id: an entry of this phone. */
    public boolean isOwn(String id) {
        return isCurrent(id) || isLegacy(id);
    }

    /** One phone, one client: a legacy id is the current id, any other id stays what it is. */
    public String canonical(String id) {
        return isLegacy(id) ? current : id;
    }

    /** Never an id. */
    @Override
    public String toString() {
        return "SnapcastOwnIds{legacy=" + legacy.size() + "}";
    }
}
