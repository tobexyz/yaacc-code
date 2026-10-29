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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The receivers of the last confirmed pick. Plain JVM with a mocked preferences object. */
public class LastPickedReceiversTest {

    @Test
    public void nothingStoredGivesAnEmptySet() {
        SharedPreferences preferences = mock(SharedPreferences.class);
        assertTrue(LastPickedReceivers.load(preferences).isEmpty());
    }

    @Test
    public void theStoredSetIsReturnedAsACopy() {
        SharedPreferences preferences = mock(SharedPreferences.class);
        Set<String> stored = new HashSet<>(List.of("tv", "radio"));
        when(preferences.getStringSet(LastPickedReceivers.KEY, null)).thenReturn(stored);

        Set<String> loaded = LastPickedReceivers.load(preferences);

        assertEquals(stored, loaded);
        assertNotSame(stored, loaded);
    }

    @Test
    public void aValueOfAnotherTypeStartsWithNothing() {
        SharedPreferences preferences = mock(SharedPreferences.class);
        when(preferences.getStringSet(LastPickedReceivers.KEY, null)).thenThrow(new ClassCastException());
        assertTrue(LastPickedReceivers.load(preferences).isEmpty());
    }

    @Test
    public void aConfirmedPickIsStored() {
        SharedPreferences preferences = mock(SharedPreferences.class);
        SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
        when(preferences.edit()).thenReturn(editor);
        when(editor.putStringSet(anyString(), any())).thenReturn(editor);

        LastPickedReceivers.save(preferences, List.of("tv"));

        verify(editor).putStringSet(LastPickedReceivers.KEY, Set.of("tv"));
        verify(editor).apply();
    }

    @Test
    public void anEmptyPickKeepsWhatIsStored() {
        SharedPreferences preferences = mock(SharedPreferences.class);
        LastPickedReceivers.save(preferences, List.of());
        LastPickedReceivers.save(preferences, null);
        verify(preferences, never()).edit();
    }
}
