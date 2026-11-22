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
package de.yaacc.fcast.discovery;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import de.yaacc.fcast.protocol.FCastPin;
import de.yaacc.multiroom.discovery.SnapcastManualServers;

/**
 * The fingerprints that came with an {@code fcast://r/} link (task 7.2, D24): endpoint and key of a receiver, stored as one string,
 * one line per receiver ({@code <canonical endpoint>\t<base64 fingerprint>}). Only a link can put an entry here: the link is the
 * channel the network can not change (the v4 document's threat model), so what is stored is the strong pin. A damaged line is
 * skipped, the list never grows over its cap ({@value #MAX}; a new receiver pushes out nothing: it is refused), an endpoint is in
 * it once. Thread safe. The fingerprint is public (it is announced by the receiver) but is not logged in full.
 */
public final class FCastPinStore {
    /** The most receivers with a stored key (the manual list holds {@link FCastStoredReceivers#MAX_MANUAL}). */
    public static final int MAX = 32;

    /** What {@link #put} did. */
    public enum Result {
        /** A new key was stored. */
        STORED,
        /** The receiver had another key, now replaced (the receiver made a new one, or the link is of another receiver at that address). */
        REPLACED,
        /** The same key was there already. */
        UNCHANGED,
        /** The list is full. */
        FULL
    }

    private final SnapcastManualServers.Storage storage;

    public FCastPinStore(SnapcastManualServers.Storage storage) {
        this.storage = storage;
    }

    /** The stored key of the receiver (source {@code LINK}), or null. */
    public synchronized FCastPin get(FCastEndpoint endpoint) {
        return endpoint == null ? null : readLocked().get(endpoint);
    }

    public synchronized Result put(FCastEndpoint endpoint, FCastPin pin) {
        if (endpoint == null || pin == null) {
            throw new IllegalArgumentException("endpoint and pin are needed");
        }
        Map<FCastEndpoint, FCastPin> pins = readLocked();
        FCastPin old = pins.get(endpoint);
        if (old != null && old.equals(pin)) {
            return Result.UNCHANGED;
        }
        if (old == null && pins.size() >= MAX) {
            return Result.FULL;
        }
        pins.put(endpoint, pin.withSource(FCastPin.Source.LINK));
        writeLocked(pins);
        return old == null ? Result.STORED : Result.REPLACED;
    }

    /** @return true if the receiver had a stored key */
    public synchronized boolean remove(FCastEndpoint endpoint) {
        Map<FCastEndpoint, FCastPin> pins = readLocked();
        boolean removed = pins.remove(endpoint) != null;
        if (removed) {
            writeLocked(pins);
        }
        return removed;
    }

    private Map<FCastEndpoint, FCastPin> readLocked() {
        Map<FCastEndpoint, FCastPin> pins = new LinkedHashMap<>();
        String text;
        try {
            text = storage.read();
        } catch (RuntimeException unreadable) {
            return pins;
        }
        if (text == null || text.isEmpty()) {
            return pins;
        }
        for (String line : text.split("\n")) {
            if (pins.size() >= MAX) {
                break;
            }
            int tab = line.indexOf('\t');
            if (tab < 0) {
                continue;
            }
            FCastEndpoint endpoint = FCastEndpoint.parseCanonical(line.substring(0, tab).trim());
            FCastPin pin = FCastPin.parse(line.substring(tab + 1), FCastPin.Source.LINK);
            if (endpoint != null && pin != null) {
                pins.put(endpoint, pin);
            }
        }
        return pins;
    }

    private void writeLocked(Map<FCastEndpoint, FCastPin> pins) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<FCastEndpoint, FCastPin> entry : new ArrayList<>(pins.entrySet())) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(entry.getKey()).append('\t').append(entry.getValue().toBase64());
        }
        storage.write(sb.toString());
    }
}
