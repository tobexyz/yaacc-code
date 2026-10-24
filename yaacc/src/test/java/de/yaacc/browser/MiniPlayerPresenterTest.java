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
package de.yaacc.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import de.yaacc.browser.DeviceSessionGrouper.PlayerRef;
import de.yaacc.browser.MiniPlayerPresenter.TransportAction;

/**
 * Unit tests for {@link MiniPlayerPresenter}, the Android-free decisions behind the
 * mini-player bar: whether it shows, what the play/pause button does, and how the
 * untrusted title / receiver name strings are cleaned before they reach a TextView.
 * Plain JVM.
 */
public class MiniPlayerPresenterTest {

    // ---- visibility ----------------------------------------------------------------------

    @Test
    public void barIsHiddenWithoutAFeaturedSession() {
        assertFalse(MiniPlayerPresenter.isVisible(null));
    }

    @Test
    public void barShowsForAPlayingSession() {
        assertTrue(MiniPlayerPresenter.isVisible(new PlayerRef(1, "uuid:tv", true, 1)));
    }

    @Test
    public void barShowsForAPausedSession() {
        assertTrue(MiniPlayerPresenter.isVisible(new PlayerRef(1, "uuid:tv", false, 1)));
    }

    // ---- play / pause button -------------------------------------------------------------

    @Test
    public void toggleButtonPausesWhilePlaying() {
        assertEquals(TransportAction.PAUSE, MiniPlayerPresenter.toggleAction(true));
    }

    @Test
    public void toggleButtonPlaysWhileNotPlaying() {
        assertEquals(TransportAction.PLAY, MiniPlayerPresenter.toggleAction(false));
    }

    // ---- text from LAN devices -----------------------------------------------------------

    @Test
    public void nullTextBecomesEmpty() {
        assertEquals("", MiniPlayerPresenter.cleanText(null));
    }

    @Test
    public void plainTextIsKeptButTrimmed() {
        assertEquals("Symphony No. 5", MiniPlayerPresenter.cleanText("  Symphony No. 5 \n"));
    }

    @Test
    public void htmlLooksLikeTextAndIsNotInterpreted() {
        // setText only: the markup stays literal, the cleaner must not touch it
        assertEquals("<b>Song</b> &amp; <a href=\"http://x\">y</a>",
                MiniPlayerPresenter.cleanText("<b>Song</b> &amp; <a href=\"http://x\">y</a>"));
    }

    @Test
    public void lineBreaksAndTabsBecomeSingleSpaces() {
        assertEquals("one two three", MiniPlayerPresenter.cleanText("one\r\ntwo\tthree"));
    }

    @Test
    public void controlCharactersAreRemoved() {
        assertEquals("ab", MiniPlayerPresenter.cleanText("a\u0000\u0007\u001bb\u007f"));
    }

    @Test
    public void bidiOverridesAndIsolatesAreRemoved() {
        // U+202E would reverse the text that follows; U+2066..2069 are isolates, U+200E/F marks
        assertEquals("evil.mp3", MiniPlayerPresenter.cleanText("\u202eevil\u202c\u2066.\u2069m\u200ep\u200f3\u061c"));
    }

    @Test
    public void ordinaryNonLatinTextSurvives() {
        assertEquals("音乐 – Café ✓", MiniPlayerPresenter.cleanText("音乐 – Café ✓"));
    }

    @Test
    public void overlongTextIsCutAtTheLimit() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < MiniPlayerPresenter.MAX_TEXT_LENGTH + 50; i++) {
            longText.append('x');
        }
        assertEquals(MiniPlayerPresenter.MAX_TEXT_LENGTH, MiniPlayerPresenter.cleanText(longText.toString()).length());
    }

    @Test
    public void cutNeverSplitsASurrogatePair() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < MiniPlayerPresenter.MAX_TEXT_LENGTH - 1; i++) {
            longText.append('x');
        }
        longText.append("🎵"); // one emoji, two chars, crossing the limit
        String cleaned = MiniPlayerPresenter.cleanText(longText.toString());
        assertFalse(Character.isHighSurrogate(cleaned.charAt(cleaned.length() - 1)));
        assertTrue(cleaned.length() <= MiniPlayerPresenter.MAX_TEXT_LENGTH);
    }

    // ---- title ---------------------------------------------------------------------------

    @Test
    public void titleIsShownWhenPresent() {
        assertEquals("Track 1", MiniPlayerPresenter.title("Track 1", "yaacc player"));
    }

    @Test
    public void blankTitleFallsBackToThePlayerName() {
        assertEquals("yaacc player", MiniPlayerPresenter.title("  ", "yaacc player"));
        assertEquals("yaacc player", MiniPlayerPresenter.title(null, "yaacc player"));
    }

    @Test
    public void titleAndFallbackBothMissingIsEmpty() {
        assertEquals("", MiniPlayerPresenter.title(null, null));
    }

    @Test
    public void titleMadeOnlyOfControlCharactersCountsAsBlank() {
        assertEquals("fallback", MiniPlayerPresenter.title("\u202e\u0000", "fallback"));
    }

    // ---- receiver label ------------------------------------------------------------------

    @Test
    public void resolvedReceiverNameWins() {
        assertEquals("Living room TV", MiniPlayerPresenter.receiverLabel("Living room TV", "player 7"));
    }

    @Test
    public void unresolvedReceiverFallsBackToThePlayerName() {
        assertEquals("player 7", MiniPlayerPresenter.receiverLabel(null, "player 7"));
        assertEquals("player 7", MiniPlayerPresenter.receiverLabel("   ", "player 7"));
    }

    @Test
    public void noNameAtAllIsEmpty() {
        assertEquals("", MiniPlayerPresenter.receiverLabel(null, null));
    }

    @Test
    public void receiverNameIsCleaned() {
        assertEquals("TV evil", MiniPlayerPresenter.receiverLabel("TV\n\u202eevil", null));
    }

    @Test
    public void nextLineBecomesASpace() {
        assertEquals("a b", MiniPlayerPresenter.cleanText("a\u0085b"));
    }

    @Test
    public void zeroWidthCharactersAreRemoved() {
        // zero width space, word joiner, byte order mark: invisible, but they make titles differ
        assertEquals("ab", MiniPlayerPresenter.cleanText("a\u200b\u2060\ufeffb"));
    }

    @Test
    public void zeroWidthJoinerInAnEmojiSequenceIsKept() {
        String family = "\ud83d\udc68\u200d\ud83d\udc69\u200d\ud83d\udc67";
        assertEquals(family, MiniPlayerPresenter.cleanText(family));
    }
}
