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
package de.yaacc.upnp.server.contentdirectory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.LongSupplier;

/**
 * Decides which SAF folders are checked for changes (issue #299): the roots of the shared SAF trees always, plus
 * the folders a control point browsed lately. Whole trees are never walked - a library can have 100000 files.
 * <p>
 * At most {@code maxFolders} folders are watched; the roots come first (in alphabetical order), then the browsed
 * folders, the one browsed last first. A browsed folder drops out {@code browsedTtlMillis} after its last Browse.
 * Thread-safe, no Android types, injectable clock.
 *
 * @author Tobias Schoene (tobexyz)
 */
public final class SafWatchSet {

    private final int maxFolders;
    private final long browsedTtlMillis;
    private final LongSupplier clock;

    private final Object lock = new Object();
    // folder -> time of its last Browse, in order of that time (eldest first)
    private final LinkedHashMap<String, Long> browsed = new LinkedHashMap<>();

    public SafWatchSet(int maxFolders, long browsedTtlMillis, LongSupplier clock) {
        this.maxFolders = Math.max(1, maxFolders);
        this.browsedTtlMillis = browsedTtlMillis;
        this.clock = clock;
    }

    /** A control point browsed this folder just now. */
    public void noteBrowsed(String folder) {
        if (folder == null) {
            return;
        }
        synchronized (lock) {
            // remove first: the re-inserted folder goes to the end (the newest)
            browsed.remove(folder);
            browsed.put(folder, clock.getAsLong());
            // nothing but the newest maxFolders can ever be watched
            Iterator<Map.Entry<String, Long>> eldest = browsed.entrySet().iterator();
            while (browsed.size() > maxFolders && eldest.hasNext()) {
                eldest.next();
                eldest.remove();
            }
        }
    }

    /**
     * @param roots the roots of the shared SAF trees
     * @return the folders to check in this pass, never more than the maximum: roots first, then browsed folders
     */
    public List<String> watched(Collection<String> roots) {
        Set<String> result = new LinkedHashSet<>(new TreeSet<>(roots));
        long now = clock.getAsLong();
        synchronized (lock) {
            Iterator<Map.Entry<String, Long>> it = browsed.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue() >= browsedTtlMillis) {
                    it.remove();
                }
            }
            List<String> newestFirst = new ArrayList<>(browsed.keySet());
            for (int i = newestFirst.size() - 1; i >= 0; i--) {
                result.add(newestFirst.get(i));
            }
        }
        List<String> limited = new ArrayList<>(result);
        return limited.size() > maxFolders ? new ArrayList<>(limited.subList(0, maxFolders)) : limited;
    }

    /** Forgets the browsed folders (the shared folders changed). */
    public void reset() {
        synchronized (lock) {
            browsed.clear();
        }
    }
}
