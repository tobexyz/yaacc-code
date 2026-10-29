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

import java.io.BufferedReader;
import java.io.StringReader;

/** The log viewer keeps only the last lines of a large logcat. Plain JVM. */
public class LogTailTest {

    private static BufferedReader reader(String text) {
        return new BufferedReader(new StringReader(text));
    }

    @Test
    public void aShortLogIsKeptWhole() throws Exception {
        assertEquals("a\nb\nc\n", LogTail.read(reader("a\nb\nc\n"), 10, 100));
    }

    @Test
    public void onlyTheLastLinesAreKept() throws Exception {
        assertEquals("3\n4\n5\n", LogTail.read(reader("1\n2\n3\n4\n5\n"), 3, 100));
    }

    @Test
    public void anEmptyStreamGivesAnEmptyText() throws Exception {
        assertEquals("", LogTail.read(reader(""), 3, 100));
    }

    @Test
    public void aLongLineIsCutWithAnEllipsis() throws Exception {
        assertEquals("abcde…\nxy\n", LogTail.read(reader("abcdefghij\nxy\n"), 5, 5));
    }

    @Test
    public void aHugeLogStaysWithinTheLimit() throws Exception {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 200_000; i++) {
            big.append("line ").append(i).append('\n');
        }
        String kept = LogTail.read(reader(big.toString()), 4000, 1000);
        assertEquals(4000, kept.split("\n").length);
        assertEquals("line 199999", kept.substring(kept.lastIndexOf("line ")).trim());
    }

    @Test
    public void aLimitBelowOneStillKeepsTheLastLine() throws Exception {
        assertEquals("b\n", LogTail.read(reader("a\nb\n"), 0, 100));
    }
}
