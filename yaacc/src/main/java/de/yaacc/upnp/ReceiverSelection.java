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
package de.yaacc.upnp;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Set arithmetic on the stored receiver ids, plain Java so it can be unit tested on the JVM.
 * <p>
 * The set returned by {@code SharedPreferences.getStringSet} must never be modified (the docs say
 * the result of changing it is undefined, and it may be the very object the preferences keep), so
 * {@link UpnpClient} always works on a copy made here.
 */
final class ReceiverSelection {

    private ReceiverSelection() {
    }

    /** A modifiable copy of {@code stored}; null counts as empty. The input is never changed. */
    static Set<String> copy(Set<String> stored) {
        return stored == null ? new HashSet<>() : new HashSet<>(stored);
    }

    /**
     * A modifiable copy of {@code stored} without the ids in {@code unknownIds}. The input is
     * never changed. An empty stored set stays empty; it is NOT replaced by a default.
     */
    static Set<String> without(Set<String> stored, Collection<String> unknownIds) {
        Set<String> result = copy(stored);
        if (unknownIds != null) {
            result.removeAll(unknownIds);
        }
        return result;
    }
}
