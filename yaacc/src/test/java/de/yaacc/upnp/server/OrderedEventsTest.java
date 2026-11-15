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

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The order in which the harness records the events a subscriber gets: by sequence number, per subscription. Plain JVM. */
public class OrderedEventsTest {

    private final List<String> sids = new ArrayList<>();
    private final List<Long> seqs = new ArrayList<>();

    private void record(String sid, long seq) {
        int at = OrderedEvents.insertIndex(sids, seqs, sid, seq);
        sids.add(at, sid);
        seqs.add(at, seq);
    }

    @Test
    public void eventsInOrderAreAppended() {
        record("a", 0);
        record("a", 1);
        record("a", 2);

        assertEquals(Arrays.asList(0L, 1L, 2L), seqs);
    }

    @Test
    public void anEventThatArrivesLateIsPutBeforeTheNewerOne() {
        record("a", 0);
        record("a", 2); // the STOPPED event, sent first by the pool
        record("a", 1); // the PLAYING event, sent a moment later although made earlier

        assertEquals("the last one is the newest", Arrays.asList(0L, 1L, 2L), seqs);
    }

    @Test
    public void severalSwapsEndInSequenceOrder() {
        for (long seq : new long[]{3, 0, 2, 5, 1, 4}) {
            record("a", seq);
        }

        assertEquals(Arrays.asList(0L, 1L, 2L, 3L, 4L, 5L), seqs);
    }

    @Test
    public void subscriptionsAreOrderedEachOnItsOwn() {
        record("a", 5);
        record("b", 0);
        record("a", 4);
        record("b", 1);

        // within one subscription the numbers rise
        long lastA = -1;
        long lastB = -1;
        for (int i = 0; i < sids.size(); i++) {
            if (sids.get(i).equals("a")) {
                org.junit.Assert.assertTrue(seqs.get(i) > lastA);
                lastA = seqs.get(i);
            } else {
                org.junit.Assert.assertTrue(seqs.get(i) > lastB);
                lastB = seqs.get(i);
            }
        }
    }

    @Test
    public void anEventOfANewSubscriptionGoesToTheEnd() {
        record("a", 3);

        record("b", 0);

        assertEquals(Arrays.asList("a", "b"), sids);
    }
}
