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
package de.yaacc.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link DisplayName}: the name of a shared file comes from another app and is
 * shown to the user before anything is played, so it is cleaned. Plain JVM. Invisible characters
 * are written as escapes on purpose.
 */
public class DisplayNameTest {

    private static final String FALLBACK = "a shared file";

    // ---- clean

    @Test
    public void nullAndEmptyBecomeEmpty() {
        assertEquals("", DisplayName.clean(null));
        assertEquals("", DisplayName.clean(""));
        assertEquals("", DisplayName.clean("   \t\n"));
    }

    @Test
    public void anOrdinaryNameIsKept() {
        assertEquals("Symphony No. 5.mp3", DisplayName.clean("Symphony No. 5.mp3"));
        assertEquals("Söng – 日本語.flac", DisplayName.clean("Söng – 日本語.flac"));
    }

    @Test
    public void whitespaceRunsCollapseAndTheEndsAreTrimmed() {
        assertEquals("a b c", DisplayName.clean("  a \t\n b\r\n  c  "));
    }

    @Test
    public void controlCharactersAreRemoved() {
        assertEquals("ab", DisplayName.clean("a\u0000b"));
        assertEquals("ab", DisplayName.clean("a\u0007\u001bb"));
        assertEquals("ab", DisplayName.clean("a\u007fb"));
        assertEquals("ab", DisplayName.clean("a\u0090b"));
    }

    @Test
    public void bidiControlsAreRemoved() {
        // right-to-left override that makes "exe.mp3" look like "3pm.exe"
        assertEquals("song3pm.exe", DisplayName.clean("song‮3pm.exe"));
        assertEquals("ab", DisplayName.clean("a‪‫‬‭‮b"));
        assertEquals("ab", DisplayName.clean("a⁦⁧⁨⁩b"));
        assertEquals("ab", DisplayName.clean("a‎‏؜b"));
    }

    @Test
    public void zeroWidthCharactersAreRemoved() {
        assertEquals("ab", DisplayName.clean("a​b"));
        assertEquals("ab", DisplayName.clean("a⁠b"));
        assertEquals("ab", DisplayName.clean("a﻿b"));
    }

    @Test
    public void aZeroWidthJoinerIsKeptSoEmojiSequencesSurvive() {
        String family = "👨‍👩‍👧";

        assertEquals(family, DisplayName.clean(family));
    }

    @Test
    public void otherFormatCharactersAreRemoved() {
        // soft hyphen, Mongolian vowel separator, invisible math operators, deprecated format controls
        assertEquals("ab", DisplayName.clean("a\u00ADb"));
        assertEquals("ab", DisplayName.clean("a\u180Eb"));
        assertEquals("ab", DisplayName.clean("a\u2061\u2062\u2063\u2064b"));
        assertEquals("ab", DisplayName.clean("a\u206A\u206B\u206C\u206D\u206E\u206Fb"));
        // tag characters (a supplementary-plane run, two chars each)
        assertEquals("ab", DisplayName.clean("a\uDB40\uDC41\uDB40\uDC7Fb"));
    }

    @Test
    public void blankLettersAreRemoved() {
        assertEquals("ab", DisplayName.clean("a\u3164b"));
        assertEquals("ab", DisplayName.clean("a\u115Fb"));
        assertEquals("ab", DisplayName.clean("a\u1160b"));
        assertEquals("ab", DisplayName.clean("a\uFFA0b"));
    }

    @Test
    public void aNonJoinerIsKeptBecauseSomeScriptsNeedIt() {
        // Persian: "mi-khaham" is written with a zero-width non-joiner
        String persian = "\u0645\u06CC\u200C\u062E\u0648\u0627\u0647\u0645";

        assertEquals(persian, DisplayName.clean(persian));
    }

    @Test
    public void variationSelectorsAndOrdinaryLettersAreKept() {
        assertEquals("\u2764\uFE0F caf\u00E9 \u65E5\u672C\u8A9E", DisplayName.clean("\u2764\uFE0F caf\u00E9 \u65E5\u672C\u8A9E"));
    }

    @Test
    public void aNameMadeOnlyOfBlankLettersAndFormatCharactersBecomesEmpty() {
        assertEquals("", DisplayName.clean("\u3164\u115F\u1160\uFFA0\u00AD\u2062\uDB40\uDC41"));
    }

    @Test
    public void forShareFallsBackForAnInvisibleOnlyName() {
        assertEquals(FALLBACK, DisplayName.forShare("\u3164\u3164", FALLBACK));
        assertEquals(FALLBACK, DisplayName.forShare("\u00AD\u2063\uFFA0", FALLBACK));
        assertEquals(FALLBACK, DisplayName.forShare("\uDB40\uDC20", FALLBACK));
    }

    @Test
    public void aNameOfInvisibleCharactersOnlyBecomesEmpty() {
        assertEquals("", DisplayName.clean("‮​\u0000﻿ ⁦"));
    }

    @Test
    public void markupIsNotInterpretedOrStripped() {
        assertEquals("<b>x</b> &amp; y", DisplayName.clean("<b>x</b>\n&amp; y"));
    }

    @Test
    public void theLengthIsCappedAtTwoHundredChars() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append('a');
        }

        assertEquals(200, DisplayName.MAX_LENGTH);
        assertEquals(200, DisplayName.clean(sb.toString()).length());
        assertEquals(200, DisplayName.clean(sb.substring(0, 200)).length());
        assertEquals(199, DisplayName.clean(sb.substring(0, 199)).length());
    }

    @Test
    public void theCapNeverSplitsASurrogatePair() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 199; i++) {
            sb.append('a');
        }
        sb.append("😀😀");

        String cleaned = DisplayName.clean(sb.toString());

        assertEquals(199, cleaned.length());
        assertFalse(Character.isHighSurrogate(cleaned.charAt(cleaned.length() - 1)));
    }

    @Test
    public void aLoneSurrogateDoesNotThrow() {
        assertEquals("a\ud83db", DisplayName.clean("a\ud83db"));
        assertEquals("a\ude00b", DisplayName.clean("a\ude00b"));
    }

    @Test
    public void theCapCountsTheSeparatorSpaces() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 150; i++) {
            sb.append("a ");
        }

        String cleaned = DisplayName.clean(sb.toString());

        assertTrue(cleaned.length() <= 200);
        assertFalse(cleaned.endsWith(" "));
    }

    @Test
    public void cleaningTwiceChangesNothing() {
        String once = DisplayName.clean("  x‮ y​\n\nz  ");

        assertEquals(once, DisplayName.clean(once));
    }

    // ---- forShare

    @Test
    public void forShareReturnsTheCleanedProviderName() {
        assertEquals("holiday.mp4", DisplayName.forShare("  holiday‮.mp4 \n", FALLBACK));
    }

    @Test
    public void forShareFallsBackForNullAndEmpty() {
        assertEquals(FALLBACK, DisplayName.forShare(null, FALLBACK));
        assertEquals(FALLBACK, DisplayName.forShare("", FALLBACK));
        assertEquals(FALLBACK, DisplayName.forShare(" \t ", FALLBACK));
    }

    @Test
    public void forShareFallsBackWhenNothingVisibleIsLeft() {
        assertEquals(FALLBACK, DisplayName.forShare("‮​\u0000", FALLBACK));
    }

    @Test
    public void forShareCleansTheFallbackToo() {
        assertEquals("a shared file", DisplayName.forShare(null, "  a‮ shared\nfile "));
    }

    @Test
    public void forShareIsNeverEmpty() {
        assertFalse(DisplayName.forShare(null, null).isEmpty());
        assertFalse(DisplayName.forShare("", "").isEmpty());
        assertFalse(DisplayName.forShare("​", "‮").isEmpty());
    }

    @Test
    public void forShareCapsTheNameAtTwoHundredChars() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            sb.append('x');
        }

        assertEquals(200, DisplayName.forShare(sb.toString(), FALLBACK).length());
    }
}
