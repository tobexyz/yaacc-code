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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Red-phase (TDD) tests for the card grid view's
 * {@link CardGridSpanCount} utility, written before the implementation.
 * <p>
 * {@code CardGridSpanCount} is deliberately pure JDK (no {@code android.*}
 * imports) so it can be unit-tested on the JVM without Robolectric or any
 * Android framework dependency - see
 * {@code de.yaacc.upnp.server.contentdirectory.SortSupport} for the same
 * precedent elsewhere in this project.
 * <p>
 * Pixel values below are derived from a documented dp-&gt;px density
 * assumption rather than unexplained magic numbers: at 2x density,
 * 1dp = 2px, so 160dp = 320px.
 */
public class CardGridSpanCountTest {

    // Target card width used throughout: 160dp at 2x density = 320px.
    private static final int TARGET_CARD_WIDTH_PX = 320;

    @Test
    public void narrowPhonePortraitWidthYieldsExactlyTwo() {
        // A typical phone portrait width at 2x density, e.g. 360dp = 720px.
        // 720 / 320 = 2 (floor), matching the "2 per row on a normal phone
        // in portrait" goal of the card grid view.
        int availableWidthPx = 720;

        int spanCount = CardGridSpanCount.computeSpanCount(availableWidthPx, TARGET_CARD_WIDTH_PX);

        assertEquals(2, spanCount);
    }

    @Test
    public void widthJustUnderThreeCardsStillYieldsTwoFloorNotRound() {
        // Just under 3 * targetCardWidthPx (3 * 320 = 960) should still
        // floor down to 2, not round up to 3.
        int availableWidthPx = 959;

        int spanCount = CardGridSpanCount.computeSpanCount(availableWidthPx, TARGET_CARD_WIDTH_PX);

        assertEquals(2, spanCount);
    }

    @Test
    public void wideTabletWidthYieldsMoreThanTwo() {
        // A 10" tablet landscape width at 2x density, e.g. 1280dp = 2560px.
        // 2560 / 320 = 8, well above the 2-column floor.
        int availableWidthPx = 2560;

        int spanCount = CardGridSpanCount.computeSpanCount(availableWidthPx, TARGET_CARD_WIDTH_PX);

        assertTrue("expected more than 2 columns on a wide/tablet width", spanCount > 2);
        assertEquals(8, spanCount);
    }

    @Test
    public void widthSmallerThanTargetCardWidthStillReturnsTwo() {
        // Multi-window/split-screen can shrink available width below a
        // single target card width (e.g. 100dp = 200px); the span count
        // must never drop below the 2-column floor.
        int availableWidthPx = 200;

        int spanCount = CardGridSpanCount.computeSpanCount(availableWidthPx, TARGET_CARD_WIDTH_PX);

        assertEquals(2, spanCount);
    }
}
