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

/**
 * The header row of a section of the top-level page: one item, hideable (the Players section is hidden while no player runs), with or
 * without refresh button and line above it.
 */
public class SectionHeaderAdapterTest {

    @Test
    public void aHeaderIsOneItemAndVisibleFromTheStart() {
        SectionHeaderAdapter header = new SectionHeaderAdapter(1, 2, null, false);

        assertEquals(1, header.getItemCount());
        assertTrue(header.isVisible());
    }

    @Test
    public void aHiddenHeaderHasNoItem() throws Exception {
        SectionHeaderAdapter header = new SectionHeaderAdapter(1, 2, null, true);
        AdapterTestSupport.giveObserverList(header);

        header.setVisible(false);
        assertEquals(0, header.getItemCount());
        assertFalse(header.isVisible());

        header.setVisible(false); // no change, nothing to notify
        header.setVisible(true);
        assertEquals(1, header.getItemCount());
    }

    @Test
    public void onlyTheServersHeaderHasTheRefreshButton() {
        assertTrue(new SectionHeaderAdapter(1, 2, () -> { }, false).hasRefresh());
        assertFalse(new SectionHeaderAdapter(1, 2, null, true).hasRefresh());
    }

    @Test
    public void theLineAboveIsAskedFor() {
        assertFalse(new SectionHeaderAdapter(1, 2, null, false).hasDividerAbove());
        assertTrue(new SectionHeaderAdapter(1, 2, null, true).hasDividerAbove());
    }
}
