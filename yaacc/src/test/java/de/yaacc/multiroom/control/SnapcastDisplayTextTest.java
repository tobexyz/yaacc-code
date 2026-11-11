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

    @Test
    public void zeroWidthAndOtherInvisibleFormatCharactersAreReplaced() {
        // a hostile server names its group like a legitimate one plus an invisible character (security review S15, S20)
        assertEquals("a?b?c?d", SnapcastDisplayText.of("a\u200Bb\u200Cc\u200Dd", 64));
        assertEquals("a?b?c?d?e?", SnapcastDisplayText.of("a\u2060b\u2061c\u2062d\u2063e\u2064", 64));
        assertEquals("so?ft", SnapcastDisplayText.of("so\u00ADft", 64));
        assertEquals("a?b", SnapcastDisplayText.of("a\u180Eb", 64));
        assertEquals("a?b", SnapcastDisplayText.of("a\uFEFFb", 64));
        assertEquals("a?b?c", SnapcastDisplayText.of("a\uFFFEb\uFFFFc", 64));
        assertEquals("a?b", SnapcastDisplayText.of("a\uFFF9b", 64));
    }

    @Test
    public void aNameWithAnInvisibleCharacterDoesNotLookLikeTheNameWithout() {
        assertFalse(SnapcastDisplayText.of("Kitchen", 64).equals(SnapcastDisplayText.of("Kitchen\u200B", 64)));
        assertEquals("Kitchen?", SnapcastDisplayText.of("Kitchen\u200B", 64));
    }

    @Test
    public void textInOtherScriptsAndEmojiIsKept() {
        assertEquals("Küche", SnapcastDisplayText.of("Küche", 64));
        assertEquals("客厅", SnapcastDisplayText.of("客厅", 64));
        assertEquals("Salón", SnapcastDisplayText.of("Salón", 64));
        assertEquals("a" + EMOJI + "b", SnapcastDisplayText.of("a" + EMOJI + "b", 64));
    }

    @Test
    public void unpairedSurrogatesAreReplaced() {
        assertEquals("a?b", SnapcastDisplayText.of("a\uD800b", 64));
        assertEquals("a?b", SnapcastDisplayText.of("a\uDC00b", 64));
        assertEquals("ab?", SnapcastDisplayText.of("ab\uD83C", 64));
        assertEquals("??", SnapcastDisplayText.of("\uDC00\uD800", 64));
        assertNoLoneSurrogate(SnapcastDisplayText.of("\uD800\uD800" + EMOJI + "\uDC00", 64));
    }

    @Test
    public void tagCharactersAreReplaced() {
        // U+E0041 is a "tag letter A": invisible, used to hide text in a name
        String tag = new String(Character.toChars(0xE0041));
        assertEquals("a?b", SnapcastDisplayText.of("a" + tag + "b", 64));
    }

    @Test
    public void theResultIsNeverLongerThanTheMaximumEvenWithManyReplacements() {
        StringBuilder hostile = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            hostile.append('\u200B').append(EMOJI).append('\uD800');
        }
        for (int max = 0; max <= 60; max++) {
            String cleaned = SnapcastDisplayText.of(hostile.toString(), max);
            assertTrue(cleaned.length() <= max);
            assertNoLoneSurrogate(cleaned);
        }
    }

    @Test
    public void everyCodePointOfTheBasicPlaneGivesAWellFormedResult() {
        for (int c = 0; c <= 0xFFFF; c++) {
            String cleaned = SnapcastDisplayText.of("x" + (char) c + "y", 64);
            assertNoLoneSurrogate(cleaned);
            assertTrue(cleaned.length() == 3);
        }
    }

    @Test
    public void aReasonForAToastLosesFormatCharactersAndIsCutShort() {
        assertEquals("tcp is nota allowed", SnapcastDisplayText.toastReason("tcp is not\u200Ba\u2060 allowed", 80));
        assertEquals("", SnapcastDisplayText.toastReason("\u200B\u200C\uFEFF", 80));
        assertEquals("", SnapcastDisplayText.toastReason(null, 80));
        assertEquals("abc", SnapcastDisplayText.toastReason("abcdef", 3));
        String cutInPair = SnapcastDisplayText.toastReason("ab" + EMOJI + "cd", 3);
        assertEquals("ab" + EMOJI, cutInPair);
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
