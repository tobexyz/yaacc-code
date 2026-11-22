/*
 *
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
package de.yaacc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.res.Resources;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/** The debugging aid for "Invalid resource ID 0x00000000": it reports the id 0 only, a bounded number of times, and wraps only at level V. */
public class ResourceIdTraceTest {

    private final List<String> messages = new ArrayList<>();
    private final List<Throwable> stacks = new ArrayList<>();
    private BiConsumer<String, Throwable> original;

    @Before
    public void setUp() {
        original = ResourceIdTrace.sink;
        ResourceIdTrace.sink = (message, stack) -> {
            messages.add(message);
            stacks.add(stack);
        };
        ResourceIdTrace.reset();
    }

    @After
    public void tearDown() {
        ResourceIdTrace.sink = original;
        ResourceIdTrace.reset();
    }

    @Test
    public void theIdZeroIsReportedWithTheMethodAndAStack() {
        ResourceIdTrace.note("getResourceEntryName", 0);

        assertEquals(1, messages.size());
        assertTrue(messages.get(0).contains("getResourceEntryName"));
        assertTrue(messages.get(0).contains("0x00000000"));
        assertNotNull(stacks.get(0));
        assertTrue("the stack names the caller", stacks.get(0).getStackTrace().length > 0);
    }

    @Test
    public void everyOtherIdIsIgnored() {
        ResourceIdTrace.note("getString", 0x7f010001);
        ResourceIdTrace.note("getString", -1);

        assertTrue(messages.isEmpty());
    }

    @Test
    public void aLoopDoesNotFloodTheLog() {
        for (int i = 0; i < 500; i++) {
            ResourceIdTrace.note("getText", 0);
        }

        assertEquals(ResourceIdTrace.MAX_REPORTS, messages.size());
        assertTrue(messages.get(messages.size() - 1).contains("last report"));
        ResourceIdTrace.reset();
        ResourceIdTrace.note("getText", 0);
        assertEquals(ResourceIdTrace.MAX_REPORTS + 1, messages.size());
    }

    @Test
    public void theResourcesAreNotWrappedUnlessTheLevelIsVerbose() {
        Resources base = Mockito.mock(Resources.class);
        YaaccLogger.setLogLevel("E");

        assertSame(base, ResourceIdTrace.of(base));
        assertNull(ResourceIdTrace.of(null));
    }

    @Test
    public void theViewsWithTheIdZeroAreFoundWithTheirPathOfClasses() {
        android.view.ViewGroup root = Mockito.mock(android.view.ViewGroup.class);
        android.view.ViewGroup list = Mockito.mock(android.view.ViewGroup.class);
        android.view.View zero = Mockito.mock(android.view.View.class);
        android.view.View other = Mockito.mock(android.view.View.class);
        Mockito.when(root.getId()).thenReturn(5);
        Mockito.when(list.getId()).thenReturn(7);
        Mockito.when(zero.getId()).thenReturn(0);
        Mockito.when(other.getId()).thenReturn(-1);
        Mockito.when(root.getChildCount()).thenReturn(1);
        Mockito.when(root.getChildAt(0)).thenReturn(list);
        Mockito.when(list.getChildCount()).thenReturn(2);
        Mockito.when(list.getChildAt(0)).thenReturn(other);
        Mockito.when(list.getChildAt(1)).thenReturn(zero);

        List<String> found = ResourceIdTrace.findViewsWithIdZero(root, 10);

        assertEquals(1, found.size());
        assertTrue(found.get(0).startsWith("view with id 0: "));
        assertTrue("the path has the root, the list and the view", found.get(0).split(" > ").length == 3);
    }

    @Test
    public void theSearchIsBoundedAndSurvivesAnEmptyTree() {
        assertTrue(ResourceIdTrace.findViewsWithIdZero(null, 10).isEmpty());
        android.view.ViewGroup root = Mockito.mock(android.view.ViewGroup.class);
        android.view.View zero = Mockito.mock(android.view.View.class);
        Mockito.when(root.getId()).thenReturn(0);
        Mockito.when(zero.getId()).thenReturn(0);
        Mockito.when(root.getChildCount()).thenReturn(50);
        Mockito.when(root.getChildAt(Mockito.anyInt())).thenReturn(zero);

        assertEquals(3, ResourceIdTrace.findViewsWithIdZero(root, 3).size());
    }
}
