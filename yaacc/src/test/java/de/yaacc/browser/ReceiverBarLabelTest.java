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

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * Unit tests for {@link ReceiverBarLabel}, the receiver bar text of the new layout: the names of
 * the receivers that have a running player, or "No receiver playing". Plain JVM.
 */
public class ReceiverBarLabelTest {

    private static final String NONE = "No receiver playing";

    @Test
    public void noPlayingReceiversShowsTheFallbackText() {
        assertEquals(NONE, ReceiverBarLabel.build(Collections.emptyList(), NONE));
        assertEquals(NONE, ReceiverBarLabel.build(null, NONE));
    }

    @Test
    public void singleReceiverShowsItsName() {
        assertEquals("Living room TV",
                ReceiverBarLabel.build(Collections.singletonList("Living room TV"), NONE));
    }

    @Test
    public void severalReceiversAreSortedCaseInsensitivelyAndJoined() {
        assertEquals("kitchen; Living room TV; this device",
                ReceiverBarLabel.build(Arrays.asList("this device", "Living room TV", "kitchen"), NONE));
    }

    @Test
    public void orderDoesNotDependOnInputOrder() {
        assertEquals(
                ReceiverBarLabel.build(Arrays.asList("b", "A", "a", "B"), NONE),
                ReceiverBarLabel.build(Arrays.asList("B", "a", "A", "b"), NONE));
    }

    @Test
    public void blankAndNullNamesAreSkippedAndNamesTrimmed() {
        assertEquals("TV", ReceiverBarLabel.build(Arrays.asList(null, "  ", " TV "), NONE));
        assertEquals(NONE, ReceiverBarLabel.build(Arrays.asList(null, ""), NONE));
    }

    @Test
    public void severalPlayersOnOneReceiverShowTheReceiverOnce() {
        assertEquals("TV", ReceiverBarLabel.build(Arrays.asList("TV", "TV", " TV "), NONE));
        assertEquals("Speaker; TV",
                ReceiverBarLabel.build(Arrays.asList("TV", "Speaker", "TV"), NONE));
    }

    @Test
    public void namesThatDifferOnlyInCaseAreDifferentReceivers() {
        assertEquals("TV; tv", ReceiverBarLabel.build(Arrays.asList("tv", "TV"), NONE));
    }

    @Test
    public void namesAreCleanedLikeEveryOtherLanText() {
        // line break, tab, bidi override and zero-width characters are removed or folded
        assertEquals("Living room TV",
                ReceiverBarLabel.build(Collections.singletonList("Living\n\troom \u202E\u200BTV"), NONE));
    }

    @Test
    public void aNameThatCleansToNothingIsSkipped() {
        assertEquals(NONE, ReceiverBarLabel.build(Arrays.asList("\u202E", "\u200B\n"), NONE));
        assertEquals("TV", ReceiverBarLabel.build(Arrays.asList("\u202E", "TV"), NONE));
    }

    @Test
    public void namesThatAreEqualAfterCleaningAreMerged() {
        assertEquals("TV", ReceiverBarLabel.build(Arrays.asList("TV", "T\u200BV", "TV\n"), NONE));
    }

    @Test
    public void aVeryLongNameIsCapped() {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            longName.append('x');
        }
        String label = ReceiverBarLabel.build(Collections.singletonList(longName.toString()), NONE);
        assertEquals(MiniPlayerPresenter.MAX_TEXT_LENGTH, label.length());
    }

    @Test
    public void markupInDeviceNamesIsPassedThroughVerbatim() {
        // Rendered as plain TextView text; the label must not alter or interpret it.
        assertEquals("<b>%s</b>", ReceiverBarLabel.build(Collections.singletonList("<b>%s</b>"), NONE));
    }

    @Test
    public void theFallbackTextIsReturnedUnchanged() {
        assertEquals("  %s <i> ", ReceiverBarLabel.build(null, "  %s <i> "));
    }
}
