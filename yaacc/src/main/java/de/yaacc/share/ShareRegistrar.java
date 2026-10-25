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
package de.yaacc.share;

/**
 * The one way the glue puts an entry into the registry: if the registry is full, the entry that
 * was released the longest ago (a leftover of an ended playback) is evicted and the registration
 * is tried ONCE more. Plain Java.
 * <p>
 * The registry does not close the target of an entry it refused: when this returns null the
 * caller still owns the target and must close it.
 */
public final class ShareRegistrar {

    private ShareRegistrar() {
    }

    /**
     * @return the new key, or null if there was no room even after an eviction (the caller keeps
     * ownership of the target)
     */
    public static String register(ShareRegistry registry, ShareRegistry.Entry entry) {
        String key = registry.register(entry);
        if (key == null && registry.evictOldestReleased()) {
            key = registry.register(entry);
        }
        return key;
    }
}
