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
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import org.fourthline.cling.support.model.item.Item;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for {@link ContentListFragment#rotateToStart(List, Item)}, the
 * pure list-rotation helper extracted from
 * {@link ContentListFragment#playAllChildsOfParentFrom(org.fourthline.cling.support.model.DIDLObject)}
 * as part of fixing issue play-all-ignores-sort-order ("play all" ignoring
 * the active client-side sort order). No Android/Cling-framework runtime
 * behavior is exercised here -- just plain list rotation over simple cling
 * model objects, which are safe to construct directly on a JVM.
 */
public class ContentListFragmentPlayAllRotationTest {

    private static Item item(String id, String title) {
        return new Item(id, "0", title, "creator", (org.fourthline.cling.support.model.DIDLObject.Class) null);
    }

    @Test
    public void rotatesSoTargetItemIsFirstPreservingRelativeOrderOfTheRest() {
        Item a = item("1", "A");
        Item b = item("2", "B");
        Item c = item("3", "C");
        Item d = item("4", "D");
        List<Item> items = Arrays.asList(a, b, c, d);

        List<Item> rotated = ContentListFragment.rotateToStart(items, c);

        assertEquals(Arrays.asList(c, d, a, b), rotated);
    }

    @Test
    public void returnsUnchangedCopyWhenTargetIsAlreadyFirst() {
        Item a = item("1", "A");
        Item b = item("2", "B");
        List<Item> items = Arrays.asList(a, b);

        List<Item> rotated = ContentListFragment.rotateToStart(items, a);

        assertEquals(Arrays.asList(a, b), rotated);
        assertNotSame("should return a copy, not the same list instance", items, rotated);
    }

    @Test
    public void returnsUnchangedCopyWhenTargetIsNull() {
        Item a = item("1", "A");
        Item b = item("2", "B");
        List<Item> items = Arrays.asList(a, b);

        List<Item> rotated = ContentListFragment.rotateToStart(items, null);

        assertEquals(Arrays.asList(a, b), rotated);
    }

    @Test
    public void returnsUnchangedCopyWhenTargetNotFound() {
        Item a = item("1", "A");
        Item b = item("2", "B");
        Item notInList = item("99", "Not In List");
        List<Item> items = Arrays.asList(a, b);

        List<Item> rotated = ContentListFragment.rotateToStart(items, notInList);

        assertEquals(Arrays.asList(a, b), rotated);
    }

    @Test
    public void handlesSingleElementList() {
        Item a = item("1", "A");
        List<Item> items = Arrays.asList(a);

        List<Item> rotated = ContentListFragment.rotateToStart(items, a);

        assertEquals(Arrays.asList(a), rotated);
    }

    @Test
    public void returnsEmptyListWhenItemsIsNull() {
        List<Item> rotated = ContentListFragment.rotateToStart(null, item("1", "A"));

        assertTrue(rotated.isEmpty());
    }

    @Test
    public void rotatesWhenTargetIsLastElement() {
        Item a = item("1", "A");
        Item b = item("2", "B");
        Item c = item("3", "C");
        List<Item> items = Arrays.asList(a, b, c);

        List<Item> rotated = ContentListFragment.rotateToStart(items, c);

        assertEquals(Arrays.asList(c, a, b), rotated);
    }
}
