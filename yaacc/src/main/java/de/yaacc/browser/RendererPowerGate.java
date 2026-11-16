/*
 *
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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One power change per device at a time, and not a second one right after the first (task 12.4): a device that is waking up must not
 * get another request from a second tap. A change that never reports its end is over after {@link #MAX_RUN_MILLIS}. Bounded memory.
 * Thread safe.
 */
final class RendererPowerGate {

    /** The longest a change may take before the gate lets the next one through. */
    static final long MAX_RUN_MILLIS = 60_000L;
    /** The most devices remembered. */
    static final int MAX_KEYS = 64;

    private static final class Slot {
        boolean running;
        long startedAt;
        long finishedAt;
    }

    private final long minGapMillis;
    private final Map<String, Slot> slots = new LinkedHashMap<String, Slot>() {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Slot> eldest) {
            return size() > MAX_KEYS;
        }
    };

    RendererPowerGate(long minGapMillis) {
        this.minGapMillis = minGapMillis;
    }

    /** @return true when a change for this device may start now (the caller then calls {@link #finished}) */
    synchronized boolean tryStart(String key, long now) {
        Slot slot = slots.get(key);
        if (slot != null) {
            if (slot.running && now - slot.startedAt < MAX_RUN_MILLIS) {
                return false;
            }
            if (!slot.running && now - slot.finishedAt < minGapMillis) {
                return false;
            }
        } else {
            slot = new Slot();
            slots.put(key, slot);
        }
        slot.running = true;
        slot.startedAt = now;
        return true;
    }

    synchronized void finished(String key, long now) {
        Slot slot = slots.get(key);
        if (slot != null) {
            slot.running = false;
            slot.finishedAt = now;
        }
    }

    synchronized int size() {
        return slots.size();
    }
}
