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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The per-renderer state of the server-side time range management of ONE share. Bounded: at
 * most {@code max} renderers; when a new one arrives the least recently used is dropped. A
 * state is created only for a valid device id ({@link RendererIdPolicy}) and only by
 * {@link #create}; {@link #get} and {@link #update} never create one, so lookups with unknown
 * ids cannot grow the map. The map lives inside the share's target, so it disappears with the
 * share. Thread safe.
 */
public final class RendererStateMap {

    /** Renderers per share. With at most {@link ShareRegistry#DEFAULT_MAX_ACTIVE} shares the whole process holds at most 64 states. */
    public static final int DEFAULT_MAX = 8;

    /** What is known about one renderer. All accessors are thread safe. */
    public static final class State {
        private long timePositionMs;
        private boolean paused;
        private long totalDurationMs;
        private long lastBytePosition;

        private State(long timePositionMs) {
            this.timePositionMs = timePositionMs;
        }

        public synchronized long getTimePositionMs() {
            return timePositionMs;
        }

        public synchronized boolean isPaused() {
            return paused;
        }

        /** The duration estimated for the file, 0 while unknown. */
        public synchronized long getTotalDurationMs() {
            return totalDurationMs;
        }

        public synchronized void setTotalDurationMs(long totalDurationMs) {
            this.totalDurationMs = totalDurationMs;
        }

        /** The byte position last computed for this renderer, 0 if none. */
        public synchronized long getLastBytePosition() {
            return lastBytePosition;
        }

        public synchronized void setLastBytePosition(long lastBytePosition) {
            this.lastBytePosition = lastBytePosition;
        }

        private synchronized void set(long timePositionMs, boolean paused) {
            this.timePositionMs = timePositionMs;
            this.paused = paused;
        }
    }

    private final int max;
    /** Access ordered: the eldest entry is the least recently used. */
    private final LinkedHashMap<String, State> states = new LinkedHashMap<>(16, 0.75f, true);

    /** @throws IllegalArgumentException if {@code max} is not positive */
    public RendererStateMap(int max) {
        if (max <= 0) {
            throw new IllegalArgumentException("max must be positive");
        }
        this.max = max;
    }

    /** @return the state, or null if there is none or the id is invalid; never creates one */
    public synchronized State get(String deviceId) {
        return RendererIdPolicy.isValid(deviceId) ? states.get(deviceId) : null;
    }

    /**
     * @return the existing state, or a new one starting at {@code initialTimeMs} (dropping the
     * least recently used if the map is full); null for an invalid id
     */
    public synchronized State create(String deviceId, long initialTimeMs) {
        if (!RendererIdPolicy.isValid(deviceId)) {
            return null;
        }
        State existing = states.get(deviceId);
        if (existing != null) {
            return existing;
        }
        while (states.size() >= max) {
            Map.Entry<String, State> eldest = states.entrySet().iterator().next();
            states.remove(eldest.getKey());
        }
        State created = new State(initialTimeMs);
        states.put(deviceId, created);
        return created;
    }

    /**
     * Sets the playback time and pause flag of an EXISTING state.
     *
     * @return false if there is no state for the id (nothing is created)
     */
    public synchronized boolean update(String deviceId, long timeMs, boolean paused) {
        State state = RendererIdPolicy.isValid(deviceId) ? states.get(deviceId) : null;
        if (state == null) {
            return false;
        }
        state.set(timeMs, paused);
        return true;
    }

    public synchronized int size() {
        return states.size();
    }

    public synchronized void clear() {
        states.clear();
    }
}
