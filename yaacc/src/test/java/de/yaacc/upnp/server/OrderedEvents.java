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
package de.yaacc.upnp.server;

import java.util.List;

/**
 * Where an event that a subscriber receives goes in the list of what it received. A real control point orders the events of
 * a subscription by their {@code SEQ} header (UPnP device architecture, GENA): the events are sent from a pool, so two that
 * are made microseconds apart may arrive in the other order, and the control point must (and does) take the one with the
 * higher number as the newer. The test harness records them the same way: by the number of the subscription, not by the
 * moment they arrived, so that "the last event" is the last one that was made. Plain Java.
 */
final class OrderedEvents {

    private OrderedEvents() {
    }

    /**
     * @param sids the subscription ids of the recorded events, in list order
     * @param seqs their sequence numbers, same order
     * @return the index at which an event of subscription {@code sid} with number {@code seq} goes: before the first recorded
     * event of the same subscription with a higher number, else at the end
     */
    static int insertIndex(List<String> sids, List<Long> seqs, String sid, long seq) {
        for (int i = 0; i < sids.size(); i++) {
            if (sids.get(i).equals(sid) && seqs.get(i) > seq) {
                return i;
            }
        }
        return sids.size();
    }
}
