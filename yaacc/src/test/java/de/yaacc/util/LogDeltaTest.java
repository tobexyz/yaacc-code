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
package de.yaacc.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** How the log viewer takes a new read into the shown text. Plain JVM. */
public class LogDeltaTest {

    @Test
    public void theFirstReadReplacesTheEmptyView() {
        LogDelta delta = LogDelta.between(null, "a\nb\n", false);
        assertEquals(LogDelta.Kind.REPLACE, delta.kind());
        assertEquals("a\nb\n", delta.text());
    }

    @Test
    public void newLinesAtTheEndAreAppended() {
        LogDelta delta = LogDelta.between("a\nb\n", "a\nb\nc\n", false);
        assertEquals(LogDelta.Kind.APPEND, delta.kind());
        assertEquals("c\n", delta.text());
    }

    @Test
    public void anUnchangedLogChangesNothing() {
        assertEquals(LogDelta.Kind.NONE, LogDelta.between("a\n", "a\n", false).kind());
    }

    @Test
    public void aWindowThatSlidesOnAppendsOnlyTheNewLines() {
        String shown = "10:00:01 a\n10:00:02 b\n10:00:03 c\n10:00:04 d\n";
        LogDelta delta = LogDelta.between(shown, "10:00:03 c\n10:00:04 d\n10:00:05 e\n10:00:06 f\n", false);
        assertEquals(LogDelta.Kind.APPEND, delta.kind());
        assertEquals("10:00:05 e\n10:00:06 f\n", delta.text());
    }

    @Test
    public void aWindowThatSlidesWithoutNewLinesChangesNothing() {
        String shown = "10:00:01 a\n10:00:02 b\n10:00:03 c\n";
        assertEquals(LogDelta.Kind.NONE, LogDelta.between(shown, "10:00:02 b\n10:00:03 c\n", false).kind());
    }

    @Test
    public void aSlidingWindowWorksForAShortLogToo() {
        LogDelta delta = LogDelta.between("a\nb\n", "b\nc\n", false);
        assertEquals(LogDelta.Kind.APPEND, delta.kind());
        assertEquals("c\n", delta.text());
    }

    @Test
    public void lastLinesAreTheEndOfTheText() {
        assertEquals("b\nc\n", LogDelta.lastLines("a\nb\nc\n", 2));
        assertEquals("a\nb\n", LogDelta.lastLines("a\nb\n", 3));
        assertEquals("b\nc", LogDelta.lastLines("a\nb\nc", 2));
        assertEquals("", LogDelta.lastLines("", 2));
    }

    @Test
    public void aLogThatWasClearedOrReplacedWholesaleReplacesTheText() {
        assertEquals(LogDelta.Kind.REPLACE, LogDelta.between("a\nb\n", "x\ny\n", false).kind());
        LogDelta cleared = LogDelta.between("a\nb\n", "", false);
        assertEquals(LogDelta.Kind.REPLACE, cleared.kind());
        assertEquals("", cleared.text());
    }

    @Test
    public void nothingChangesWhileTheUserHasTextSelected() {
        assertEquals(LogDelta.Kind.NONE, LogDelta.between("a\n", "a\nb\n", true).kind());
        assertEquals(LogDelta.Kind.NONE, LogDelta.between("a\n", "x\n", true).kind());
    }

    @Test
    public void aNullReadChangesNothing() {
        assertEquals(LogDelta.Kind.NONE, LogDelta.between("a\n", null, false).kind());
    }

    @Test
    public void anEmptyShownTextIsReplacedNotAppended() {
        assertEquals(LogDelta.Kind.REPLACE, LogDelta.between("", "a\n", false).kind());
    }
}
