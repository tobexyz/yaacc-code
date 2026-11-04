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

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Notices that the content of watched SAF folders changed, so the ContentDirectory's SystemUpdateID can be raised
 * and the control points that cache Browse results look again (issue #299).
 * <ul>
 *   <li>A folder is known by a fingerprint of its listing (name, document id, last modified, size and directory
 *   flag of every entry, and their number). The fingerprint does not depend on the order of the entries.</li>
 *   <li>The first listing of a folder is the baseline, not a change. A listing that failed (provider error, no
 *   permission, not a directory) is neither: the old fingerprint stays.</li>
 *   <li>Changes are collected, not reported one by one: {@link #takeDue()} gives the changed folders
 *   once a bump is allowed, and a bump covers all of them. Two bumps are at least {@code minBumpIntervalMillis}
 *   apart; a change that comes in too early is kept until the interval is over ({@link #delayUntilBump()}).</li>
 *   <li>At most {@code maxFolders} folders are known; the one watched least recently is forgotten first (its next
 *   listing is a baseline again).</li>
 * </ul>
 * Thread-safe. No Android types and an injectable clock, so it is testable on the plain JVM.
 *
 * @author Tobias Schoene (tobexyz)
 */
public final class SafChangeDetector {

    private final int maxFolders;
    private final long minBumpIntervalMillis;
    private final LongSupplier clock;

    private final Object lock = new Object();
    // access order: the eldest entry is the folder watched least recently
    private final LinkedHashMap<String, Long> fingerprints = new LinkedHashMap<>(16, 0.75f, true);
    private final Set<String> changed = new HashSet<>();
    private boolean bumpedBefore;
    private long lastBumpAt;
    private boolean previousBumpedBefore;
    private long previousBumpAt;

    public SafChangeDetector(int maxFolders, long minBumpIntervalMillis, LongSupplier clock) {
        this.maxFolders = Math.max(1, maxFolders);
        this.minBumpIntervalMillis = Math.max(0, minBumpIntervalMillis);
        this.clock = clock;
    }

    /**
     * Compares a listing with the one seen before.
     *
     * @param folder  the folder's key (its uri as {@link SafDirectoryLister} lists it)
     * @param listing what the provider returned for it now
     * @return true if the folder was known and its listing differs; the folder is then waiting for the next bump
     */
    public boolean observe(String folder, SafDirectory listing) {
        if (folder == null || listing == null || !listing.isCacheable()) {
            return false;
        }
        long fingerprint = fingerprintOf(listing.entries);
        synchronized (lock) {
            Long known = fingerprints.put(folder, fingerprint);
            if (known == null) {
                evictOverflow(folder);
                return false;
            }
            if (known == fingerprint) {
                return false;
            }
            changed.add(folder);
            return true;
        }
    }

    /** @return true if the folder has a fingerprint (a listing of it was seen) */
    public boolean knows(String folder) {
        synchronized (lock) {
            return fingerprints.containsKey(folder);
        }
    }

    /** Forgets every folder that is not in {@code folders}: its next listing is a baseline again. */
    public void retainOnly(Collection<String> folders) {
        synchronized (lock) {
            fingerprints.keySet().retainAll(folders);
        }
    }

    /** Forgets all fingerprints and the changes not yet reported (the shared folders changed; that bump is done elsewhere). */
    public void reset() {
        synchronized (lock) {
            fingerprints.clear();
            changed.clear();
        }
    }

    /** @return the number of changed folders that wait for a bump, due or not */
    public int pendingFolders() {
        synchronized (lock) {
            return changed.size();
        }
    }

    /**
     * @return -1 if no change waits, else how long (millis, 0 if now) until a bump is allowed
     */
    public long delayUntilBump() {
        synchronized (lock) {
            if (changed.isEmpty()) {
                return -1;
            }
            if (!bumpedBefore) {
                return 0;
            }
            return Math.max(0, lastBumpAt + minBumpIntervalMillis - clock.getAsLong());
        }
    }

    /**
     * Takes the changes a bump has to report now: all changed folders, if the last bump is at least the minimum
     * interval ago (or there was none). They are cleared and the interval starts at once, so a change that is
     * observed while the bump is being sent waits for the next one instead of being lost. If the bump could not
     * be sent, hand the folders back with {@link #giveBack(Set)}.
     *
     * @return the changed folders; empty if there is none or a bump is not allowed yet
     */
    public Set<String> takeDue() {
        synchronized (lock) {
            if (delayUntilBump() != 0) {
                return new HashSet<>();
            }
            Set<String> taken = new HashSet<>(changed);
            changed.clear();
            previousBumpedBefore = bumpedBefore;
            previousBumpAt = lastBumpAt;
            bumpedBefore = true;
            lastBumpAt = clock.getAsLong();
            return taken;
        }
    }

    /** The bump for the folders of the last {@link #takeDue()} was not sent: they wait again, and the interval is undone. */
    public void giveBack(Set<String> folders) {
        synchronized (lock) {
            changed.addAll(folders);
            bumpedBefore = previousBumpedBefore;
            lastBumpAt = previousBumpAt;
        }
    }

    // caller holds the lock; the folder just put is the newest, so it is never the victim
    private void evictOverflow(String justAdded) {
        Iterator<Map.Entry<String, Long>> eldest = fingerprints.entrySet().iterator();
        while (fingerprints.size() > maxFolders && eldest.hasNext()) {
            String victim = eldest.next().getKey();
            if (!victim.equals(justAdded)) {
                // a change of the victim that was not reported yet stays pending
                eldest.remove();
            }
        }
    }

    /**
     * A 64 bit fingerprint of the entries: the sum of one well mixed hash per entry (so the order does not matter)
     * combined with the number of entries.
     */
    static long fingerprintOf(List<SafDirectoryEntry> entries) {
        long sum = 0;
        for (SafDirectoryEntry entry : entries) {
            sum += entryHash(entry);
        }
        return mix(sum ^ mix(entries.size() + 0x9E3779B97F4A7C15L));
    }

    private static long entryHash(SafDirectoryEntry entry) {
        long hash = 0xCBF29CE484222325L;
        hash = hashText(hash, entry.name);
        hash = hashText(hash, entry.documentId);
        hash = mix(hash ^ entry.lastModified);
        hash = mix(hash ^ entry.size);
        return mix(hash ^ (entry.isDirectory() ? 0x5BD1E995L : 0x1B873593L));
    }

    // FNV-1a over the characters; null and the empty text differ
    private static long hashText(long hash, String text) {
        if (text == null) {
            return (hash ^ 0xFFFFL) * 0x100000001B3L;
        }
        for (int i = 0; i < text.length(); i++) {
            hash = (hash ^ text.charAt(i)) * 0x100000001B3L;
        }
        // end marker, so "ab"+"c" and "a"+"bc" differ
        return (hash ^ 0x1FFFFL) * 0x100000001B3L;
    }

    // splitmix64 finalizer
    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}
