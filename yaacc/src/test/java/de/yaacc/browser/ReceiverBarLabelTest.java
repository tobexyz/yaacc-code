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
 * Unit tests for {@link ReceiverBarLabel}, the Content tab receiver bar text.
 * Plain JVM.
 */
public class ReceiverBarLabelTest {

    private static final String NONE = "No receiver selected";

    @Test
    public void noReceiversShowsTheNoneSelectedText() {
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
    public void duplicateNamesAreKeptBecauseTheyAreDistinctDevices() {
        assertEquals("TV; TV", ReceiverBarLabel.build(Arrays.asList("TV", "TV"), NONE));
    }

    @Test
    public void markupInDeviceNamesIsPassedThroughVerbatim() {
        // Rendered as plain TextView text; the label must not alter or interpret it.
        assertEquals("<b>%s</b>", ReceiverBarLabel.build(Collections.singletonList("<b>%s</b>"), NONE));
    }
}
