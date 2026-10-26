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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.SharedPreferences;

import org.junit.Test;

/**
 * A fresh install gets the new layout from the preference default; an install that was updated from
 * a version without the "Layout" setting has no value after the defaults ran and must keep classic.
 */
public class BrowserLauncherDefaultLayoutTest {

    private static final String KEY = "settings_browser_layout_key";

    @Test
    public void aFreshInstallKeepsTheStoredDefault() {
        SharedPreferences preferences = mock(SharedPreferences.class);
        when(preferences.contains(KEY)).thenReturn(true);

        assertFalse(BrowserLauncher.keepClassicForUpdatedInstall(preferences, KEY));

        verify(preferences, never()).edit();
    }

    @Test
    public void anUpdatedInstallWithoutAValueKeepsClassic() {
        SharedPreferences preferences = mock(SharedPreferences.class);
        SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
        when(preferences.contains(KEY)).thenReturn(false);
        when(preferences.edit()).thenReturn(editor);
        when(editor.putString(anyString(), any())).thenReturn(editor);

        assertTrue(BrowserLauncher.keepClassicForUpdatedInstall(preferences, KEY));

        verify(editor).putString(KEY, BrowserLayoutChoice.STORED_CLASSIC);
        verify(editor).apply();
    }

    @Test
    public void aStoredChoiceIsNeverOverwritten() {
        SharedPreferences preferences = mock(SharedPreferences.class);
        when(preferences.contains(KEY)).thenReturn(true);

        BrowserLauncher.keepClassicForUpdatedInstall(preferences, KEY);

        verify(preferences, never()).edit();
    }
}
