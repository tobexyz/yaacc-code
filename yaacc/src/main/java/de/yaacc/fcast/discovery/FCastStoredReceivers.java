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
import java.util.List;

import de.yaacc.fcast.protocol.FCastText;
import de.yaacc.multiroom.discovery.SnapcastManualServers;

/**
 * A short list of receivers that is stored as one string: the receivers the user added by hand (cap 16) or the ones that were
 * selected in the last run (cap 16). One line per receiver, {@code <canonical endpoint>} and, after a tab, the name; the name
 * has no control characters, so no line can contain another line. A damaged line is skipped on reading, the list never
 * grows over its cap, an endpoint is in it once. No credentials are ever stored. Thread safe.
 */
public final class FCastStoredReceivers {
    /** The cap of the manual list. */
    public static final int MAX_MANUAL = 16;
    /** The cap of the remembered selection. */
    public static final int MAX_REMEMBERED = 16;
    private static final int MAX_NAME = 64;

    /** The outcome of an {@link #add}. */
    public enum AddResult {
        ADDED, INVALID, DUPLICATE, FULL
    }

    private final SnapcastManualServers.Storage storage;
    private final int maxItems;

    public FCastStoredReceivers(SnapcastManualServers.Storage storage, int maxItems) {
        this.storage = storage;
        this.maxItems = maxItems;
    }

    /** The stored receivers in the order they were added. */
    public synchronized List<FCastDirectory.Known> list() {
        return readLocked();
    }

    /** Adds a receiver typed in as {@code host[:port]}. */
    public synchronized AddResult add(String hostText, String name) {
        FCastEndpoint endpoint = FCastEndpoint.parse(hostText);
        return endpoint == null ? AddResult.INVALID : add(endpoint, name);
    }

    public synchronized AddResult add(FCastEndpoint endpoint, String name) {
        if (endpoint == null) {
            return AddResult.INVALID;
        }
        List<FCastDirectory.Known> items = readLocked();
        for (FCastDirectory.Known item : items) {
            if (item.getEndpoint().equals(endpoint)) {
                return AddResult.DUPLICATE;
            }
        }
        if (items.size() >= maxItems) {
            return AddResult.FULL;
        }
        items.add(new FCastDirectory.Known(endpoint, FCastText.clean(name, MAX_NAME)));
        writeLocked(items);
        return AddResult.ADDED;
    }

    /** @return true if the receiver was in the list */
    public synchronized boolean remove(FCastEndpoint endpoint) {
        List<FCastDirectory.Known> items = readLocked();
        boolean removed = false;
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).getEndpoint().equals(endpoint)) {
                items.remove(i);
                removed = true;
            }
        }
        if (removed) {
            writeLocked(items);
        }
        return removed;
    }

    /** Replaces the whole list (the remembered selection); duplicates and what does not fit are left out. */
    public synchronized void replaceAll(List<FCastDirectory.Known> receivers) {
        List<FCastDirectory.Known> items = new ArrayList<>();
        for (FCastDirectory.Known known : receivers) {
            if (known == null || known.getEndpoint() == null || items.size() >= maxItems || contains(items, known.getEndpoint())) {
                continue;
            }
            items.add(new FCastDirectory.Known(known.getEndpoint(), FCastText.clean(known.getName(), MAX_NAME)));
        }
        writeLocked(items);
    }

    private static boolean contains(List<FCastDirectory.Known> items, FCastEndpoint endpoint) {
        for (FCastDirectory.Known item : items) {
            if (item.getEndpoint().equals(endpoint)) {
                return true;
            }
        }
        return false;
    }

    private List<FCastDirectory.Known> readLocked() {
        List<FCastDirectory.Known> items = new ArrayList<>();
        String text;
        try {
            text = storage.read();
        } catch (RuntimeException unreadable) {
            return items;
        }
        if (text == null || text.isEmpty()) {
            return items;
        }
        for (String line : text.split("\n")) {
            if (items.size() >= maxItems) {
                break;
            }
            int tab = line.indexOf('\t');
            String address = tab < 0 ? line : line.substring(0, tab);
            FCastEndpoint endpoint = FCastEndpoint.parseCanonical(address.trim());
            if (endpoint == null || contains(items, endpoint)) {
                continue;
            }
            String name = tab < 0 ? "" : FCastText.clean(line.substring(tab + 1), MAX_NAME);
            items.add(new FCastDirectory.Known(endpoint, name));
        }
        return items;
    }

    private void writeLocked(List<FCastDirectory.Known> items) {
        StringBuilder sb = new StringBuilder();
        for (FCastDirectory.Known item : items) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(item.getEndpoint());
            if (!item.getName().isEmpty()) {
                sb.append('\t').append(item.getName());
            }
        }
        storage.write(sb.toString());
    }
}
