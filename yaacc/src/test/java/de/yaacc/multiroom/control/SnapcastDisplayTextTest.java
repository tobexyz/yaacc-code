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
package de.yaacc.multiroom.control;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Text from the network is cleaned and cut for display; the cut never leaves half of a surrogate pair. */
public class SnapcastDisplayTextTest {

    private static final String EMOJI = new String(Character.toChars(0x1F3B5)); // two chars

    @Test
    public void controlCharactersAndBidiControlsBecomeQuestionMarks() {
        assertEquals("a?b?c?d", SnapcastDisplayText.of("a\nb‮c\u0000d", 64));
    }

    @Test
    public void nullIsEmpty() {
        assertEquals("", SnapcastDisplayText.of(null, 10));
    }

    @Test
    public void aShortTextIsKept() {
        assertEquals("Living room", SnapcastDisplayText.of("Living room", 64));
    }

    @Test
    public void theTextIsCutAtTheMaximum() {
        assertEquals("abc", SnapcastDisplayText.of("abcdef", 3));
    }

    @Test
    public void aCutBetweenTheHalvesOfAPairDropsTheWholePair() {
        String cut = SnapcastDisplayText.of("ab" + EMOJI + "cd", 3);

        assertEquals("ab", cut);
        assertNoLoneSurrogate(cut);
    }

    @Test
    public void aPairThatFitsIsKept() {
        assertEquals("ab" + EMOJI, SnapcastDisplayText.of("ab" + EMOJI + "cd", 4));
    }

    @Test
    public void aPairAtTheVeryEndOfAShortTextIsKept() {
        assertEquals("a" + EMOJI, SnapcastDisplayText.of("a" + EMOJI, 64));
    }

    @Test
    public void aMaximumOfOneCannotHoldAPair() {
        assertEquals("", SnapcastDisplayText.of(EMOJI, 1));
    }

    @Test
    public void noMaximumGivesAnEmptyText() {
        assertEquals("", SnapcastDisplayText.of("abc", 0));
        assertEquals("", SnapcastDisplayText.of("abc", -5));
    }

    @Test
    public void everyCutOfARowOfPairsIsWellFormed() {
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            row.append(EMOJI);
        }
        for (int max = 0; max <= 41; max++) {
            String cut = SnapcastDisplayText.of(row.toString(), max);
            assertNoLoneSurrogate(cut);
            assertTrue(cut.length() <= max);
        }
    }

    private static void assertNoLoneSurrogate(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertTrue("lone high surrogate", i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1)));
                i++;
            } else {
                assertFalse("lone low surrogate", Character.isLowSurrogate(c));
            }
        }
    }
}
