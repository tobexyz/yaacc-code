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
package de.yaacc.upnp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;

import org.fourthline.cling.model.meta.Device;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The stored receiver id set is never modified in place (it can be the object
 * {@code SharedPreferences} keeps), and an empty set stays empty: {@link UpnpClient#getReceiverDeviceIds()}
 * hands out a modifiable copy, {@link UpnpClient#getReceiverDevices()} removes the unknown ids from
 * that copy and stores it. A spy over a client that needs no context, a mocked {@code SharedPreferences}
 * that returns an unmodifiable set. Plain JVM.
 */
public class UpnpClientReceiverSelectionTest {

    private static final String KEY = "selected_receivers";

    private UpnpClient client;
    private SharedPreferences preferences;
    private SharedPreferences.Editor editor;

    private static Set<String> setOf(String... ids) {
        return new HashSet<>(Arrays.asList(ids));
    }

    @Before
    public void setUp() {
        Context context = mock(Context.class);
        when(context.getString(anyInt())).thenReturn(KEY);
        preferences = mock(SharedPreferences.class);
        editor = mock(SharedPreferences.Editor.class);
        when(preferences.edit()).thenReturn(editor);
        when(editor.putStringSet(nullable(String.class), any())).thenReturn(editor);
        client = spy(new UpnpClient());
        client.preferences = preferences;
        doReturn(context).when(client).getContext();
    }

    private void stored(Set<String> ids) {
        when(preferences.getStringSet(eq(KEY), any())).thenReturn(ids);
    }

    private void known(String... ids) {
        for (String id : ids) {
            doReturn(mock(Device.class)).when(client).getDevice(id);
        }
    }

    private void unknown(String... ids) {
        for (String id : ids) {
            doReturn(null).when(client).getDevice(id);
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> storedBack() {
        ArgumentCaptor<Set<String>> captor = ArgumentCaptor.forClass(Set.class);
        verify(editor).putStringSet(eq(KEY), captor.capture());
        return captor.getValue();
    }

    @Test
    public void copyIsEqualButNotTheSameObject() {
        Set<String> stored = setOf("a", "b");
        stored(stored);

        Set<String> copy = client.getReceiverDeviceIds();

        assertEquals(stored, copy);
        assertNotSame(stored, copy);
    }

    @Test
    public void changingTheCopyDoesNotChangeTheStoredSet() {
        Set<String> stored = setOf("a", "b");
        stored(stored);

        Set<String> copy = client.getReceiverDeviceIds();
        copy.remove("a");
        copy.add("c");

        assertEquals(setOf("a", "b"), stored);
    }

    @Test
    public void copyOfAnUnmodifiableSetIsModifiable() {
        stored(Collections.unmodifiableSet(setOf("a")));

        Set<String> copy = client.getReceiverDeviceIds();
        copy.add("b");

        assertEquals(setOf("a", "b"), copy);
    }

    @Test
    public void copyOfNullIsEmpty() {
        stored(null);

        assertTrue(client.getReceiverDeviceIds().isEmpty());
    }

    @Test
    public void withoutRemovesOnlyTheUnknownIdsAndLeavesTheStoredSetAlone() {
        Set<String> stored = Collections.unmodifiableSet(setOf("a", "b", "c", "x"));
        stored(stored);
        known("a", "c");
        unknown("b", "x");

        assertEquals(2, client.getReceiverDevices().size());

        assertEquals(setOf("a", "c"), storedBack());
        assertEquals(setOf("a", "b", "c", "x"), stored);
    }

    @Test
    public void withoutNothingKeepsEverything() {
        stored(setOf("a"));
        known("a");

        assertEquals(1, client.getReceiverDevices().size());

        assertEquals(setOf("a"), client.getReceiverDeviceIds());
        verify(editor, never()).putStringSet(nullable(String.class), anySet());
    }

    @Test
    public void anEmptyStoredSetStaysEmpty() {
        stored(new HashSet<>());

        assertTrue(client.getReceiverDevices().isEmpty());

        assertTrue("not replaced by the local-device default", client.getReceiverDeviceIds().isEmpty());
        verify(editor, never()).putStringSet(nullable(String.class), anySet());
    }

    @Test
    public void removingEverythingGivesAnEmptyModifiableSet() {
        stored(Collections.unmodifiableSet(setOf("a")));
        unknown("a");

        assertTrue(client.getReceiverDevices().isEmpty());

        Set<String> result = storedBack();
        assertTrue(result.isEmpty());
        result.add("b");
        assertEquals(setOf("b"), result);
    }
}
