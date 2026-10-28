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

import android.content.SharedPreferences;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * The receivers of the last confirmed pick. The picker of the new layout ticks them again, so the usual
 * receiver is one tap away; the receiver selection itself is still cleared after every action. Only ids
 * of receivers that are listed are ever returned by the picker, so an id of a receiver that is gone is
 * harmless.
 */
final class LastPickedReceivers {

    static final String KEY = "last_picked_receivers";

    private LastPickedReceivers() {
    }

    /** A copy of the stored ids; never null. */
    static Set<String> load(SharedPreferences preferences) {
        Set<String> stored;
        try {
            stored = preferences.getStringSet(KEY, null);
        } catch (RuntimeException e) {
            stored = null; // another type under the key: start with nothing
        }
        return stored == null ? new HashSet<>() : new HashSet<>(stored);
    }

    /** Remembers the ids of a confirmed pick; an empty pick keeps what is stored. */
    static void save(SharedPreferences preferences, Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        preferences.edit().putStringSet(KEY, new HashSet<>(ids)).apply();
    }
}
