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
        assertTrue(t.text().length() < 80_000);
    }

    @Test
    public void everyKindHasALabelAndNoFieldIsWithoutAName() {
        for (TraceKind kind : TraceKind.values()) {
            assertFalse(kind.name(), kind.label().isEmpty());
        }
    }

    @Test
    public void aLostConnectionShowsItsReasonAndOurOwnLimitInWords() {
        EventTrace t = new EventTrace(0);
        t.add(1_000_000L, TraceKind.CONNECTION_LOST,
                de.yaacc.multiroom.protocol.SnapcastConnection.LossReason.TIMEOUT.ordinal(), 1);
        t.add(2_000_000L, TraceKind.CONNECTION_LOST,
                de.yaacc.multiroom.protocol.SnapcastConnection.LossReason.SERVER_CLOSED.ordinal(), 0);
        String text = t.text();
        assertTrue(text, text.contains("why=timeout  own limit=idle limit"));
        assertTrue(text, text.contains("why=server_closed  own limit=no (server or network)"));
    }

    @Test
    public void theUnderrunVerdictIsInWords() {
        EventTrace t = new EventTrace(0);
        t.add(0, TraceKind.UNDERRUN_JUDGED, 2, 3, 118);
        t.add(1000, TraceKind.UNDERRUN_JUDGED, 1, 5, 118);
        String text = t.text();
        assertTrue(text, text.contains("verdict=thin cushion (position fine): write-ahead raised"));
        assertTrue(text, text.contains("verdict=output dry (position stalled or empty): flush"));
    }

    @Test
    public void rareImportantEventsAreKeptInTheirOwnRingWhenTheMainRingScrolls() {
        EventTrace t = new EventTrace(1_000_000L);
        t.add(1_001_000L, TraceKind.FLAC_SHIFT, 26, 1, 2);
        t.add(1_002_000L, TraceKind.FLUSH, 0, 1, 2);
        for (int i = 0; i < 5 * EventTrace.CAPACITY; i++) {
            t.add(1_003_000L + i * 1000L, TraceKind.DRIFT, i, 1, 2); // busy and unimportant
        }
        assertFalse(t.text().contains("flac stream shifted"));
        assertEquals(2, t.importantSize());
        String text = t.importantText();
        assertTrue(text, text.contains("flac stream shifted against its timestamps, silence inserted (ms)  ms=26  fills so far=1"));
        assertTrue(text, text.contains("reason (1 seen at once, 2 seen over windows)=2"));
        assertTrue(text, text.indexOf("flac stream shifted") < text.indexOf("flush"));
        assertFalse(text, text.contains(" ms  drift  "));
    }

    @Test
    public void theImportantRingIsBoundedAndDropsTheOldest() {
        EventTrace t = new EventTrace(0L);
        for (int i = 0; i < EventTrace.IMPORTANT_CAPACITY + 30; i++) {
            t.add(i * 1000L, TraceKind.FLAC_SHIFT, i, i, 1);
        }
        assertEquals(EventTrace.IMPORTANT_CAPACITY, t.importantSize());
        String text = t.importantText();
        assertTrue(text, text.startsWith("(30 older important events left out)"));
        assertTrue(text, text.contains("ms=30  ") && !text.contains("ms=29  "));
        assertTrue(text, text.contains("ms=" + (EventTrace.IMPORTANT_CAPACITY + 29) + "  "));
    }

    @Test
    public void aPadOrSkipWhilePlayingIsImportantOnlyAbove2Milliseconds() {
        EventTrace t = new EventTrace(0L);
        t.add(1000L, TraceKind.STEP_PLAYING, 96, 0, 2_000L);
        assertEquals(0, t.importantSize());
        t.add(2000L, TraceKind.STEP_PLAYING, 0, 192, -2_001L);
        assertEquals(1, t.importantSize());
        t.add(3000L, TraceKind.STEP_PLAYING, 0, 192, 9_000L);
        assertEquals(2, t.importantSize());
    }

    @Test
    public void everyKindOfTheListIsKept() {
        for (TraceKind kind : new TraceKind[]{TraceKind.FLAC_SHIFT, TraceKind.FLAC_FILL_DETAIL, TraceKind.FLAC_REANCHOR,
                TraceKind.STREAM_RESUMED, TraceKind.FLUSH, TraceKind.HARD_LATE, TraceKind.HOLE_MARKED, TraceKind.HOLE_RECOVERED,
                TraceKind.HEADER_ACCEPTED, TraceKind.DECODER_RESET, TraceKind.EPOCH_CHANGE, TraceKind.WRITE_AHEAD_RAISED,
                TraceKind.CONNECTION_LOST, TraceKind.SESSION_END}) {
            EventTrace t = new EventTrace(0L);
            t.add(1000L, kind, 1, 2, 3);
            assertEquals(kind.name(), 1, t.importantSize());
        }
        EventTrace t = new EventTrace(0L);
        t.add(1000L, TraceKind.POSITION, 1, 2, 3);
        t.add(1000L, TraceKind.DRIFT, 1, 2, 3);
        assertEquals(0, t.importantSize());
    }
}
