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
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

import de.yaacc.util.YaaccLogger;

/**
 * The logic of the SAF change check (issue #299), without threads and Android types: which folders to list in a
 * pass, comparing them with {@link SafChangeDetector}, and asking for the bump of the ContentDirectory's
 * SystemUpdateID. {@link SafChangeRunner} supplies the thread, the preferences and the real bump.
 *
 * @author Tobias Schoene (tobexyz)
 */
public final class SafChangeMonitor {

    /** Time between the end of a pass and the start of the next one. */
    public static final long PASS_INTERVAL_MILLIS = 30_000L;
    /** Two bumps of the SystemUpdateID are at least this far apart. */
    public static final long MIN_BUMP_INTERVAL_MILLIS = 15_000L;
    /** A folder a control point browsed is checked for this long after its last Browse. */
    public static final long BROWSED_TTL_MILLIS = 15 * 60_000L;
    /** Folders checked per pass at most (roots first). */
    public static final int MAX_WATCHED_FOLDERS = 16;
    /** A pass that took long is followed by a pause of this many times its duration (but at least the interval). */
    static final int SLOW_PASS_PAUSE_FACTOR = 3;

    /** Lists a folder from the provider now (not from a still valid cache) and keeps the result in the cache. */
    public interface Lister {
        SafDirectory refresh(String folder) throws Exception;
    }

    private final SafChangeDetector detector;
    private final SafWatchSet watchSet;
    private final Lister lister;
    private final BooleanSupplier bump;
    private final LongConsumer flushRequest;
    private final AtomicBoolean flushRequested = new AtomicBoolean();
    private final Object flushLock = new Object();

    /**
     * @param bump         raises the SystemUpdateID and sends the event; false if that was not possible (no
     *                     ContentDirectory yet). Called on the thread that calls {@link #flush()}.
     * @param flushRequest asked to call {@link #flush()} after the given delay in millis; called when a change was
     *                     found outside of a pass (by a Browse), on whatever thread that Browse runs
     */
    public SafChangeMonitor(Lister lister, BooleanSupplier bump, LongConsumer flushRequest, LongSupplier clock) {
        this(new SafChangeDetector(MAX_WATCHED_FOLDERS, MIN_BUMP_INTERVAL_MILLIS, clock),
                new SafWatchSet(MAX_WATCHED_FOLDERS, BROWSED_TTL_MILLIS, clock), lister, bump, flushRequest);
    }

    SafChangeMonitor(SafChangeDetector detector, SafWatchSet watchSet, Lister lister, BooleanSupplier bump,
                     LongConsumer flushRequest) {
        this.detector = detector;
        this.watchSet = watchSet;
        this.lister = lister;
        this.bump = bump;
        this.flushRequest = flushRequest;
    }

    /**
     * One check: lists the roots and the lately browsed folders (one at a time, from the provider) and compares
     * them with what was seen before, then bumps if a change is due. Runs on the caller's (background) thread.
     *
     * @param roots the roots of the shared SAF trees
     * @return the number of folders that were checked
     */
    public int runPass(Collection<String> roots) {
        List<String> folders = watchSet.watched(roots);
        // a folder that is no longer watched is forgotten, so it starts with a new baseline when it comes back
        detector.retainOnly(folders);
        int listed = 0;
        int changed = 0;
        for (String folder : folders) {
            try {
                if (detector.observe(folder, lister.refresh(folder))) {
                    changed++;
                }
                listed++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // not listable now (provider gone, timeout): no change, the old fingerprint stays
                YaaccLogger.v(getClass().getName(), "SAF change check of " + folder + " failed: " + e);
            }
        }
        YaaccLogger.v(getClass().getName(), "SAF change pass: " + listed + " of " + folders.size() + " folder(s) listed, "
                + changed + " changed, " + detector.pendingFolders() + " waiting for a bump");
        flush();
        return listed;
    }

    /**
     * A Browse got the listing of a folder. The folder is watched from now on (for 15 minutes). A listing that
     * was just loaded from the provider (or of a folder not seen yet) is compared with the earlier one, which
     * costs no provider query; if it differs, a bump is asked for through {@code flushRequest}.
     *
     * @param fromCache true if the Browse got the listing from the cache (nothing new to compare then)
     */
    public void onBrowse(String folder, SafDirectory listing, boolean fromCache) {
        watchSet.noteBrowsed(folder);
        if (fromCache && detector.knows(folder)) {
            return;
        }
        if (detector.observe(folder, listing)) {
            requestFlush();
        }
    }

    /**
     * Bumps if changes are due. Call it from the one background thread of the runner.
     *
     * @return -1 if nothing is waiting any more, else the millis after which a call is due again (changes that wait
     * for the minimum interval to pass); {@code flushRequest} has been asked for that call
     */
    public long flush() {
        synchronized (flushLock) {
            flushRequested.set(false);
            Set<String> due = detector.takeDue();
            if (!due.isEmpty()) {
                boolean sent;
                try {
                    sent = bump.getAsBoolean();
                } catch (RuntimeException e) {
                    YaaccLogger.w(getClass().getName(), "SystemUpdateID not bumped after a SAF change", e);
                    sent = false;
                }
                if (sent) {
                    YaaccLogger.d(getClass().getName(), "SAF change detected in " + due.size() + " folder(s), SystemUpdateID bumped");
                } else {
                    // kept for the next pass
                    detector.giveBack(due);
                    return -1;
                }
            }
            long next = detector.delayUntilBump();
            if (next >= 0) {
                // changes wait for the minimum interval: come back when it is over
                requestFlush();
            }
            return next;
        }
    }

    /** The shared folders changed (the bump for that is done elsewhere): everything seen so far is forgotten. */
    public void reset() {
        detector.reset();
        watchSet.reset();
    }

    private void requestFlush() {
        if (flushRequested.compareAndSet(false, true)) {
            flushRequest.accept(Math.max(0, detector.delayUntilBump()));
        }
    }

    /** @return how long to wait after a pass that took {@code passMillis}: the interval, or longer after a slow pass */
    public static long pauseAfterPass(long passMillis) {
        return Math.max(PASS_INTERVAL_MILLIS, Math.max(0, passMillis) * SLOW_PASS_PAUSE_FACTOR);
    }

    /**
     * @return true if the SAF folders are to be checked: the local server provides content, serves SAF folders
     * and at least one folder is selected
     */
    public static boolean shouldWatch(boolean serverEnabled, boolean providerEnabled, boolean serveSaf, int selectedFolders) {
        return serverEnabled && providerEnabled && serveSaf && selectedFolders > 0;
    }
}
