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
package de.yaacc.openhome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The play queue of an OpenHome playlist: tracks in an order, each with an id that is never used twice (a counter from 1; a deleted
 * track's id does not come back soon, so a control point that still holds it cannot hit another track by mistake).
 * <p>
 * The queue is the model only: {@code Insert} puts a track after another one (or at the start), the edits change the
 * {@link #token()}, which a control point uses to see that its copy of the {@code IdArray} is old. The limits are checked here
 * (number of tracks, length of address and metadata), so nothing a control point sends can make the queue grow without bound.
 * Thread safe.
 */
public final class OhQueue {
    public static final int DEFAULT_TRACKS_MAX = 1000;
    public static final int MAX_URI_LENGTH = 4096;
    public static final int MAX_METADATA_LENGTH = 65536;

    /** One track of the queue. */
    public static final class Entry {
        private final long id;
        private final String uri;
        private final String metadata;

        Entry(long id, String uri, String metadata) {
            this.id = id;
            this.uri = uri;
            this.metadata = metadata;
        }

        public long getId() {
            return id;
        }

        public String getUri() {
            return uri;
        }

        public String getMetadata() {
            return metadata;
        }
    }

    private final int tracksMax;
    private final List<Entry> entries = new ArrayList<>();
    private final Map<Long, Entry> byId = new HashMap<>();
    private long lastId;
    private long token = 1;

    public OhQueue(int tracksMax) {
        this(tracksMax, 0);
    }

    /** @param lastId the id before the first one (for tests of the wrap around of the counter) */
    OhQueue(int tracksMax, long lastId) {
        if (tracksMax < 1) {
            throw new IllegalArgumentException("tracksMax");
        }
        this.tracksMax = tracksMax;
        this.lastId = lastId;
    }

    public int tracksMax() {
        return tracksMax;
    }

    /**
     * Puts a track into the queue.
     *
     * @param afterId  the track it follows, {@link OhIds#NONE} for the start of the queue
     * @param uri      the address; not empty, at most {@link #MAX_URI_LENGTH} characters
     * @param metadata the DIDL-Lite text of the track; may be empty, at most {@link #MAX_METADATA_LENGTH} characters
     * @return the id of the new track
     * @throws OhError 800 if {@code afterId} is not in the queue, 801 if the queue is full, 402 for an address or metadata that is
     *                 empty or too long
     */
    public synchronized long insert(long afterId, String uri, String metadata) {
        if (uri == null || uri.isEmpty() || uri.length() > MAX_URI_LENGTH) {
            throw OhError.invalid("address");
        }
        String meta = metadata == null ? "" : metadata;
        if (meta.length() > MAX_METADATA_LENGTH) {
            throw OhError.invalid("metadata");
        }
        int index = 0;
        if (afterId != OhIds.NONE) {
            index = indexOf(afterId);
            if (index < 0) {
                throw OhError.idNotFound();
            }
            index++;
        }
        if (entries.size() >= tracksMax) {
            throw OhError.playlistFull();
        }
        Entry entry = new Entry(nextId(), uri, meta);
        entries.add(index, entry);
        byId.put(entry.getId(), entry);
        token++;
        return entry.getId();
    }

    private long nextId() {
        do {
            lastId = lastId >= OhIds.MAX_ID ? 1 : lastId + 1;
        } while (byId.containsKey(lastId));
        return lastId;
    }

    /** @throws OhError 800 if the id is not in the queue */
    public synchronized void delete(long id) {
        Entry entry = byId.remove(id);
        if (entry == null) {
            throw OhError.idNotFound();
        }
        entries.remove(entry);
        token++;
    }

    public synchronized void deleteAll() {
        if (!entries.isEmpty()) {
            entries.clear();
            byId.clear();
            token++;
        }
    }

    /** @throws OhError 800 if the id is not in the queue */
    public synchronized Entry read(long id) {
        Entry entry = byId.get(id);
        if (entry == null) {
            throw OhError.idNotFound();
        }
        return entry;
    }

    /** The tracks of the ids that are in the queue, in the order of the ids asked for; others are skipped. */
    public synchronized List<Entry> readList(List<Long> ids) {
        List<Entry> result = new ArrayList<>();
        for (long id : ids) {
            Entry entry = byId.get(id);
            if (entry != null) {
                result.add(entry);
            }
        }
        return result;
    }

    public synchronized List<Long> idArray() {
        List<Long> ids = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            ids.add(entry.getId());
        }
        return ids;
    }

    /** A number that changes with every edit of the queue: the token of the {@code IdArray}. */
    public synchronized long token() {
        return token;
    }

    public synchronized int size() {
        return entries.size();
    }

    /** @return the position of the track, -1 if it is not in the queue */
    public synchronized int indexOf(long id) {
        Entry entry = byId.get(id);
        return entry == null ? -1 : entries.indexOf(entry);
    }

    /** @throws OhError 802 if there is no track at the position */
    public synchronized long idAt(int index) {
        if (index < 0 || index >= entries.size()) {
            throw OhError.indexNotFound();
        }
        return entries.get(index).getId();
    }

    public synchronized List<Entry> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }
}
