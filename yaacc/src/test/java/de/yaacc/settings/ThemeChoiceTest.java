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
package de.yaacc.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The three-state theme setting: system, dark, light. Plain JVM. */
public class ThemeChoiceTest {

    private static final String LEGACY = "settings_dark_mode_key";
    private static final String KEY = "settings_theme_key";

    @Test
    public void eachStoredValueMapsToItsNightMode() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, ThemeChoice.nightMode("system"));
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, ThemeChoice.nightMode("dark"));
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, ThemeChoice.nightMode("light"));
    }

    @Test
    public void nullAndUnknownValuesGiveTheDefault() {
        int dflt = ThemeChoice.nightMode(ThemeChoice.DEFAULT_STORED_VALUE);
        assertEquals(dflt, ThemeChoice.nightMode(null));
        assertEquals(dflt, ThemeChoice.nightMode(""));
        assertEquals(dflt, ThemeChoice.nightMode("Dark"));
        assertEquals(dflt, ThemeChoice.nightMode("bogus"));
    }

    @Test
    public void theDefaultStaysDarkLikeTheFormerCheckBox() {
        assertEquals("dark", ThemeChoice.DEFAULT_STORED_VALUE);
    }

    @Test
    public void theFormerCheckBoxMapsToDarkAndSystem() {
        assertEquals("dark", ThemeChoice.fromLegacyDarkMode(true));
        assertEquals("system", ThemeChoice.fromLegacyDarkMode(false));
    }

    private static SharedPreferences prefs(Map<String, Object> contents, SharedPreferences.Editor editor) {
        SharedPreferences preferences = mock(SharedPreferences.class);
        when(preferences.contains(anyString())).thenAnswer(i -> contents.containsKey(i.<String>getArgument(0)));
        when(preferences.getAll()).thenAnswer(i -> new HashMap<>(contents));
        when(preferences.edit()).thenReturn(editor);
        when(editor.putString(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(editor);
        when(editor.remove(anyString())).thenReturn(editor);
        return preferences;
    }

    @Test
    public void anOldCheckedBoxMigratesToDarkAndIsRemoved() {
        SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
        Map<String, Object> contents = new HashMap<>();
        contents.put(LEGACY, true);

        assertTrue(ThemeChoice.migrateLegacyDarkMode(prefs(contents, editor), LEGACY, KEY));

        verify(editor).putString(KEY, "dark");
        verify(editor).remove(LEGACY);
        verify(editor).apply();
    }

    @Test
    public void anOldUncheckedBoxMigratesToSystem() {
        SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
        Map<String, Object> contents = new HashMap<>();
        contents.put(LEGACY, false);

        assertTrue(ThemeChoice.migrateLegacyDarkMode(prefs(contents, editor), LEGACY, KEY));

        verify(editor).putString(KEY, "system");
    }

    @Test
    public void aStoredThemeIsNeverOverwritten() {
        SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
        Map<String, Object> contents = new HashMap<>();
        contents.put(LEGACY, true);
        contents.put(KEY, "light");

        assertFalse(ThemeChoice.migrateLegacyDarkMode(prefs(contents, editor), LEGACY, KEY));

        verify(editor, never()).putString(anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    public void nothingToMigrateOnAFreshInstall() {
        SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);

        assertFalse(ThemeChoice.migrateLegacyDarkMode(prefs(new HashMap<>(), editor), LEGACY, KEY));

        verify(editor, never()).putString(anyString(), org.mockito.ArgumentMatchers.any());
    }

    private static String read(String relativePath) throws Exception {
        File file = new File(relativePath);
        assertTrue("missing " + file.getAbsolutePath(), file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void thePreferenceValuesAndDefaultAreTheStoredConstants() throws Exception {
        String xml = read("src/main/res/values/theme_choice.xml");
        Matcher array = Pattern.compile("<string-array name=\"theme_values\"[^>]*>(.*?)</string-array>",
                Pattern.DOTALL).matcher(xml);
        assertTrue(array.find());
        List<String> values = new ArrayList<>();
        Matcher item = Pattern.compile("<item>(.*?)</item>").matcher(array.group(1));
        while (item.find()) {
            values.add(item.group(1).trim());
        }
        assertEquals(List.of(ThemeChoice.STORED_SYSTEM, ThemeChoice.STORED_DARK, ThemeChoice.STORED_LIGHT), values);

        Matcher pref = Pattern.compile(
                "<ListPreference[^>]*android:defaultValue=\"([^\"]*)\"[^>]*settings_theme_key",
                Pattern.DOTALL).matcher(read("src/main/res/xml/preference.xml"));
        assertTrue(pref.find());
        assertEquals(ThemeChoice.DEFAULT_STORED_VALUE, pref.group(1));
    }

    @Test
    public void everyLocaleHasTheTitleAndTheThreeEntries() throws Exception {
        for (String dir : new String[]{"values", "values-de", "values-es", "values-fr", "values-nl", "values-pt",
                "values-zh"}) {
            String xml = read("src/main/res/" + dir + "/setting_strings.xml");
            for (String name : new String[]{"settings_theme_title", "settings_theme_system",
                    "settings_theme_dark", "settings_theme_light"}) {
                assertTrue(dir + " " + name,
                        Pattern.compile("<string name=\"" + name + "\">[^<]+</string>").matcher(xml).find());
            }
        }
    }
}
