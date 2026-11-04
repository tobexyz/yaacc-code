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

import java.util.List;
import java.util.Set;

import de.yaacc.player.PlayableItem;
import de.yaacc.util.YaaccLogger;

/**
 * Creates the share of one item of a picked queue when a player needs it and releases it again:
 * the placeholder item of the player gets the proxy url and the DIDL item of the registered share
 * ({@link ShareService#prepare}, the allowed addresses are those of the receiver this player plays
 * on, resolved once when the queue was confirmed), and releasing removes the share from the registry at once (not after the idle grace), so a
 * finished file is not reachable any more. Never logs a key, url or name.
 */
public final class QueueShareBinder implements QueueShareWindow.Binder {

    private static final String TAG = QueueShareBinder.class.getName();

    /**
     * One share is created at a time over all players of all queues: the players of a mixed folder
     * start together and the provider gate lets only one call per provider in, so overlapping binds
     * would be refused as busy and their items skipped.
     */
    private static final Object BIND_LOCK = new Object();

    /** Registers one file; {@link ShareService#prepare} in the app. */
    public interface Preparer {
        ShareService.Prepared prepare(PendingShare.Data data, Set<String> allowedAddresses);
    }

    /** The bookkeeping of the registry; the app removes the key from both registries. */
    public interface Keys {
        /** A cheap look before a file is opened: false when the registry has no room for another share. */
        boolean hasRoom();

        /** The window holds the share now: the sweeper must not remove it as unclaimed. */
        void claimed(String key);

        /** Removes the share (closes the file) and forgets the key. */
        void remove(String key);
    }

    /** The duration of a picked file in milliseconds, -1 when unknown (read when the share is created). */
    public interface Durations {
        long millisOf(PickedQueue.Entry entry);
    }

    private final List<PickedQueue.Entry> entries;
    private final List<PlayableItem> items;
    private final Set<String> allowedAddresses;
    private final Preparer preparer;
    private final Keys keys;
    private final Durations durations;

    /**
     * @param entries     the picked files of this queue, in play order
     * @param items       this player's placeholder items, same order and size as {@code entries}
     * @param allowedAddresses the addresses of the receiver this player plays on, fixed at the confirm
     *                         (the share is allowed for them only)
     */
    public QueueShareBinder(List<PickedQueue.Entry> entries, List<PlayableItem> items,
                            Set<String> allowedAddresses, Preparer preparer, Keys keys,
                            Durations durations) {
        this.entries = entries;
        this.items = items;
        this.allowedAddresses = new java.util.HashSet<>(allowedAddresses);
        this.preparer = preparer;
        this.keys = keys;
        this.durations = durations;
    }

    @Override
    public QueueShareWindow.Bound bind(int index) {
        if (index < 0 || index >= entries.size() || index >= items.size()) {
            return null;
        }
        if (!keys.hasRoom()) {
            // transient: no file is opened and nothing is shown for a registry that is full
            YaaccLogger.w(TAG, "No room for another share");
            return null;
        }
        PickedQueue.Entry entry = entries.get(index);
        ShareService.Prepared prepared;
        try {
            synchronized (BIND_LOCK) {
                prepared = preparer.prepare(PendingShare.Data.forFile(entry.getUri(), entry.getDisplayName(),
                        entry.getMimeType()), allowedAddresses);
            }
        } catch (RuntimeException e) {
            YaaccLogger.w(TAG, "Could not share an item of the queue (" + e.getClass().getSimpleName() + ")");
            return null;
        }
        if (prepared == null) {
            return null;
        }
        String key = prepared.getKey();
        // from here the window owns the key: it is released with the share
        keys.claimed(key);
        PlayableItem target = items.get(index);
        PlayableItem source = prepared.getItem();
        long duration = readDuration(entry);
        PlayableItem withDuration = duration > 0L ? SharedItemFactory.create(entry.getMimeType(),
                entry.getDisplayName(), source.getUri(), sizeOf(source), duration, 0L) : null;
        PlayableItem result = withDuration != null ? withDuration : source;
        target.setItem(result.getItem());
        target.setUri(result.getUri());
        if (withDuration != null) {
            target.setDuration(duration);
        }
        return () -> keys.remove(key);
    }

    private long readDuration(PickedQueue.Entry entry) {
        try {
            return durations.millisOf(entry);
        } catch (RuntimeException e) {
            return -1L;
        }
    }

    private static long sizeOf(PlayableItem item) {
        try {
            Long size = item.getItem().getFirstResource().getSize();
            return size == null ? 0L : size;
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}
