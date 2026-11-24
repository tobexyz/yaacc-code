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
package de.yaacc.sendspin;

import java.util.HashSet;
import java.util.Set;

/**
 * The operator's approvals of unpaired players, by {@code client_id}, kept across restarts; the oldest are dropped beyond
 * {@link SendspinSettings#MAX_APPROVALS}. A stored set has no order (the preferences give a hash set), so each entry is written
 * as {@code <sequence>:<client id>} and the lowest sequence is the oldest. Pure.
 */
public final class SendspinApprovals {

    private static final int ID_LENGTH = 43;

    private final StringSetStore store;

    public SendspinApprovals(StringSetStore store) {
        this.store = store;
    }

    private static String idOf(String entry) {
        int colon = entry.indexOf(':');
        return colon < 0 ? null : entry.substring(colon + 1);
    }

    private static long sequenceOf(String entry) {
        int colon = entry.indexOf(':');
        try {
            return colon <= 0 ? -1 : Long.parseLong(entry.substring(0, colon));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public synchronized boolean isApproved(String clientId) {
        if (clientId == null) {
            return false;
        }
        for (String entry : store.get(SendspinSettings.KEY_APPROVED)) {
            if (clientId.equals(idOf(entry))) {
                return true;
            }
        }
        return false;
    }

    public synchronized void approve(String clientId) {
        if (clientId == null || clientId.length() != ID_LENGTH || clientId.indexOf(':') >= 0) {
            return;
        }
        Set<String> entries = new HashSet<>();
        long next = 1;
        for (String entry : store.get(SendspinSettings.KEY_APPROVED)) {
            long sequence = sequenceOf(entry);
            if (sequence < 0 || idOf(entry) == null) {
                continue; // damaged
            }
            next = Math.max(next, sequence + 1);
            if (!clientId.equals(idOf(entry))) {
                entries.add(entry);
            }
        }
        entries.add(next + ":" + clientId);
        while (entries.size() > SendspinSettings.MAX_APPROVALS) {
            String oldest = null;
            for (String entry : entries) {
                if (oldest == null || sequenceOf(entry) < sequenceOf(oldest)) {
                    oldest = entry;
                }
            }
            entries.remove(oldest);
        }
        store.put(SendspinSettings.KEY_APPROVED, entries);
    }

    /** The ids of the approved players, sorted. */
    public synchronized java.util.List<String> ids() {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (String entry : store.get(SendspinSettings.KEY_APPROVED)) {
            String id = idOf(entry);
            if (id != null && sequenceOf(entry) >= 0) {
                ids.add(id);
            }
        }
        java.util.Collections.sort(ids);
        return ids;
    }

    public synchronized void revoke(String clientId) {
        Set<String> entries = new HashSet<>(store.get(SendspinSettings.KEY_APPROVED));
        boolean changed = false;
        for (String entry : store.get(SendspinSettings.KEY_APPROVED)) {
            if (clientId != null && clientId.equals(idOf(entry))) {
                entries.remove(entry);
                changed = true;
            }
        }
        if (changed) {
            store.put(SendspinSettings.KEY_APPROVED, entries);
        }
    }
}
