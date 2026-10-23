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

/**
 * Shared, Android-framework-free helper for computing a
 * {@code GridLayoutManager} span count from an available width and a
 * target card width, both in pixels.
 * <p>
 * Deliberately has no {@code android.*} imports - same testability
 * precedent as
 * {@code de.yaacc.upnp.server.contentdirectory.SortSupport} - so it can be
 * unit-tested on the plain JVM without Robolectric.
 */
public class CardGridSpanCount {

    private CardGridSpanCount() {
        // utility class
    }

    /**
     * Computes how many card columns fit in the given available width,
     * never fewer than 2 (so a very narrow or multi-window width still
     * gets a usable 2-column grid instead of collapsing to 1).
     *
     * @param availableWidthPx  the available width, in pixels (e.g. the
     *                          {@code RecyclerView}'s current width)
     * @param targetCardWidthPx the target width of a single card, in
     *                          pixels
     * @return the span count to pass to
     * {@code GridLayoutManager#setSpanCount(int)}, always &gt;= 2
     */
    public static int computeSpanCount(int availableWidthPx, int targetCardWidthPx) {
        return Math.max(2, availableWidthPx / targetCardWidthPx);
    }
}
