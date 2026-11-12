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
package de.yaacc.multiroom.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EventTraceTest {

    @Test
    public void keepsTheLatestEventsOldestFirstAndCountsWhatWasOverwritten() {
        EventTrace t = new EventTrace(1_000_000L);
        for (int i = 0; i < EventTrace.CAPACITY + 50; i++) {
            t.add(1_000_000L + i * 1000L, TraceKind.DRIFT, i, 1, 2);
        }
        assertEquals(EventTrace.CAPACITY, t.size());
        assertEquals(EventTrace.CAPACITY + 50, t.total());
        assertEquals(50, t.overwritten());
        String text = t.text();
        assertTrue(text, text.startsWith("(50 older events left out)"));
        String[] lines = text.split("\n");
        assertEquals(EventTrace.CAPACITY + 1, lines.length);
        assertTrue(lines[1], lines[1].contains("drift us=50"));
        assertTrue(lines[lines.length - 1], lines[lines.length - 1].contains("drift us=" + (EventTrace.CAPACITY + 49)));
    }

    @Test
    public void showsTheTimeSinceTheStartInMillisecondsAndLabelledNumbers() {
        EventTrace t = new EventTrace(5_000_000L);
        t.add(5_000_000L + 1_234_567L, TraceKind.FLUSH, FlushReason.HARD_RESYNC.ordinal(), 19_200, 395_000);
        String text = t.text();
        assertTrue(text, text.contains("1234 ms"));
        assertTrue(text, text.contains("flush"));
        assertTrue(text, text.contains("reason=hard_resync"));
        assertTrue(text, text.contains("written frames=19200"));
        assertTrue(text, text.contains("drift us=395000"));
    }

    @Test
    public void anEmptyTraceIsEmptyText() {
        EventTrace t = new EventTrace(0);
        assertEquals(0, t.size());
        assertEquals("", t.text());
        assertFalse(t.text().contains("older"));
    }

    @Test
    public void memoryIsBoundedWhateverIsAdded() {
        EventTrace t = new EventTrace(0);
        for (int i = 0; i < 100_000; i++) {
            t.add(i, TraceKind.QUEUE, i, i, i);
        }
        assertEquals(EventTrace.CAPACITY, t.size());
        assertTrue(t.text().length() < 40_000);
    }

    @Test
    public void everyKindHasALabelAndNoFieldIsWithoutAName() {
        for (TraceKind kind : TraceKind.values()) {
            assertFalse(kind.name(), kind.label().isEmpty());
        }
    }
}
