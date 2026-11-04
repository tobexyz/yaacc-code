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

import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

import de.yaacc.player.PlayableItem;

/**
 * The items of the players of a picked queue. Duration is unknown (-1): nothing starts a timer for
 * the next item, the receiver's transport state or the local player tells when an item ended.
 */
public final class QueueItems {

    /** The placeholder url: not fetchable, replaced when the share is bound. */
    private static final String PENDING_SCHEME = "yaacc-pending";

    private QueueItems() {
    }

    /** The entries that could be turned into items and those items, same order and size. */
    public static final class Built {
        private final List<PickedQueue.Entry> entries = new ArrayList<>();
        private final List<PlayableItem> items = new ArrayList<>();

        public List<PickedQueue.Entry> entries() {
            return entries;
        }

        public List<PlayableItem> items() {
            return items;
        }

        void add(PickedQueue.Entry entry, PlayableItem item) {
            if (item != null) {
                entries.add(entry);
                items.add(item);
            }
        }
    }

    /**
     * Items that are shared just in time: they carry name and type, a placeholder url and no key.
     * The entries come from {@link PickedQueue} (type and name are already checked and cleaned).
     */
    public static Built placeholders(List<PickedQueue.Entry> entries) {
        Built result = new Built();
        for (int i = 0; i < entries.size(); i++) {
            PickedQueue.Entry entry = entries.get(i);
            result.add(entry, create(entry, Uri.parse(PENDING_SCHEME + ":" + i)));
        }
        return result;
    }

    /**
     * Items for this phone's own music and image player: the picked {@code content:} uri itself,
     * read with the grant of the app; nothing is shared on the network.
     */
    public static Built direct(List<PickedQueue.Entry> entries) {
        Built result = new Built();
        for (PickedQueue.Entry entry : entries) {
            result.add(entry, create(entry, Uri.parse(entry.getUri())));
        }
        return result;
    }

    private static PlayableItem create(PickedQueue.Entry entry, Uri uri) {
        PlayableItem item = SharedItemFactory.create(entry.getMimeType(), entry.getDisplayName(), uri,
                entry.getSizeBytes(), 0L, 0L);
        if (item != null) {
            item.setDuration(-1L);
        }
        return item;
    }
}
