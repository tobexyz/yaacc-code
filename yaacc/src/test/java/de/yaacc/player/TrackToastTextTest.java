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
package de.yaacc.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The title of a track as it is shown in a toast: it comes from a server, so it is cleaned and cut. */
public class TrackToastTextTest {

    @Test
    public void aNormalTitleIsKept() {
        assertEquals("By The Way", TrackToastText.title("By The Way"));
    }

    @Test
    public void newlinesAndControlCharactersAreGone() {
        String shown = TrackToastText.title("Line one\nLine two\r\n\tTabbed\u0000\u0007\u001b[31m");

        assertFalse(shown, shown.contains("\n"));
        assertFalse(shown, shown.contains("\r"));
        assertFalse(shown, shown.contains("\t"));
        for (int i = 0; i < shown.length(); i++) {
            assertFalse("control character at " + i, Character.isISOControl(shown.charAt(i)));
        }
        assertTrue(shown, shown.startsWith("Line one Line two Tabbed"));
    }

    @Test
    public void invisibleAndBidiCharactersAreGone() {
        assertEquals("abc", TrackToastText.title("a​b‮c⁦"));
    }

    @Test
    public void aLongTitleIsCutAndMarked() {
        StringBuilder long200 = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            long200.append('x');
        }

        String shown = TrackToastText.title(long200.toString());

        assertEquals(TrackToastText.MAX_CODE_POINTS + 1, shown.codePointCount(0, shown.length()));
        assertTrue(shown.endsWith("…"));
    }

    @Test
    public void aTitleOfExactlyTheLimitIsNotMarked() {
        StringBuilder exact = new StringBuilder();
        for (int i = 0; i < TrackToastText.MAX_CODE_POINTS; i++) {
            exact.append('y');
        }

        assertEquals(exact.toString(), TrackToastText.title(exact.toString()));
    }

    @Test
    public void aCutNeverSplitsASurrogatePair() {
        StringBuilder emojis = new StringBuilder();
        for (int i = 0; i < TrackToastText.MAX_CODE_POINTS + 5; i++) {
            emojis.appendCodePoint(0x1F3B5);
        }

        String shown = TrackToastText.title(emojis.toString());

        for (int i = 0; i < shown.length(); i++) {
            if (Character.isHighSurrogate(shown.charAt(i))) {
                assertTrue("pair at " + i, i + 1 < shown.length() && Character.isLowSurrogate(shown.charAt(i + 1)));
            }
        }
        assertEquals(TrackToastText.MAX_CODE_POINTS + 1, shown.codePointCount(0, shown.length()));
    }

    @Test
    public void nothingVisibleGivesTheQuestionMarkAndNullToo() {
        assertEquals("?", TrackToastText.title(null));
        assertEquals("?", TrackToastText.title(""));
        assertEquals("?", TrackToastText.title(" \n\t​ "));
    }

    @Test
    public void theLimitIsShortEnoughForAToast() {
        assertTrue(TrackToastText.MAX_CODE_POINTS <= 80);
    }
    // ---- review S6: an address is never shown

    @Test
    public void anAddressAsTheTitleIsNotShown() {
        assertEquals("?", TrackToastText.title("http://192.168.1.5:8080/media/a.mp3?token=secret"));
        assertEquals("?", TrackToastText.title("HTTPS://host/x"));
        assertEquals("?", TrackToastText.title("content://media/external/audio/media/42"));
        assertEquals("?", TrackToastText.title("file:///sdcard/Music/a.mp3"));
        assertEquals("?", TrackToastText.title("  rtsp://host/stream  "));
    }

    @Test
    public void aTitleThatOnlyMentionsAnAddressWordIsKept() {
        assertEquals("Live at http", TrackToastText.title("Live at http"));
        assertEquals("Re: Mix", TrackToastText.title("Re: Mix"));
        assertEquals("C:\\Music", TrackToastText.title("C:\\Music"));
    }

    @Test
    public void looksLikeAnAddressIsFalseForNormalTitles() {
        assertFalse(TrackToastText.looksLikeAddress(null));
        assertFalse(TrackToastText.looksLikeAddress(""));
        assertFalse(TrackToastText.looksLikeAddress("By The Way"));
        assertTrue(TrackToastText.looksLikeAddress("http://x"));
        assertTrue(TrackToastText.looksLikeAddress("a title with://inside"));
    }
}
