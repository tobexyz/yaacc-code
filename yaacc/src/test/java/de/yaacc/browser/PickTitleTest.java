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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link PickTitle}: the line under "Play on..." that names what a shared item is.
 * The name comes from another app's provider, so it only ever reaches the screen through
 * {@link MiniPlayerPresenter#cleanText(String)}, and a name that is empty after cleaning is replaced
 * by a fixed text. Plain JVM.
 */
public class PickTitleTest {

    private static final String FALLBACK = "Shared file";

    @Test
    public void aPlainNameIsShownAsIs() {
        assertEquals("Song.mp3", PickTitle.nameOrFallback("Song.mp3", FALLBACK));
    }

    @Test
    public void theNameGoesThroughTheMiniPlayerCleaning() {
        String raw = "evil\n‮exe.mp3";

        assertEquals(MiniPlayerPresenter.cleanText(raw), PickTitle.nameOrFallback(raw, FALLBACK));
        assertEquals("evil exe.mp3", PickTitle.nameOrFallback(raw, FALLBACK));
    }

    @Test
    public void aTooLongNameIsCapped() {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            longName.append('x');
        }

        String shown = PickTitle.nameOrFallback(longName.toString(), FALLBACK);

        assertEquals(MiniPlayerPresenter.MAX_TEXT_LENGTH, shown.length());
    }

    @Test
    public void aMissingNameFallsBackToTheFixedText() {
        assertEquals(FALLBACK, PickTitle.nameOrFallback(null, FALLBACK));
        assertEquals(FALLBACK, PickTitle.nameOrFallback("", FALLBACK));
        assertEquals(FALLBACK, PickTitle.nameOrFallback("   ", FALLBACK));
    }

    @Test
    public void aNameThatIsOnlyInvisibleCharactersFallsBackToo() {
        assertEquals(FALLBACK, PickTitle.nameOrFallback("‮​\n\t", FALLBACK));
    }

    @Test
    public void theFallbackIsNotCleanedAway() {
        assertEquals(FALLBACK, PickTitle.nameOrFallback(null, FALLBACK));
    }

    @Test
    public void noFallbackAndNoNameGivesAnEmptyString() {
        assertEquals("", PickTitle.nameOrFallback(null, null));
    }

    @Test
    public void onlyAShareHasASubtitle() {
        assertNull(PickTitle.subtitle(false, "Song.mp3", FALLBACK));
        assertEquals("Song.mp3", PickTitle.subtitle(true, "Song.mp3", FALLBACK));
        assertEquals(FALLBACK, PickTitle.subtitle(true, null, FALLBACK));
        assertTrue(PickTitle.subtitle(true, "\n", "").isEmpty());
    }
}
