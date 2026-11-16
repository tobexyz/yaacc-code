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
import java.util.Objects;

/**
 * The last {@code Standby} value read from each OpenHome device (task 12.4), by UDN, and whether a new value is a change. Bounded:
 * the oldest are dropped. Thread safe.
 */
final class OpenHomePowerBook {

    /** The most devices remembered (the lists watch at most 64). */
    static final int MAX_DEVICES = 128;

    private final Map<String, Boolean> standby = new LinkedHashMap<String, Boolean>() {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > MAX_DEVICES;
        }
    };

    /**
     * @param value true for standby, false for on, null for not known (the device did not answer)
     * @return whether this is another value than the one before
     */
    synchronized boolean update(String udn, Boolean value) {
        if (udn == null) {
            return false;
        }
        boolean known = standby.containsKey(udn);
        Boolean before = standby.get(udn);
        if (value == null) {
            standby.remove(udn);
            return known;
        }
        standby.put(udn, value);
        return !known || !Objects.equals(before, value);
    }

    /** @return the last value; null when none is known */
    synchronized Boolean standbyOf(String udn) {
        return udn == null ? null : standby.get(udn);
    }

    synchronized void forget(String udn) {
        standby.remove(udn);
    }

    synchronized int size() {
        return standby.size();
    }
}
