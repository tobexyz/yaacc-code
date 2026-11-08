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
package de.yaacc.upnp.server.contentdirectory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

/** The media paths as a LIKE condition: a path is taken literally, {@code %} and {@code _} in it are no wildcards. */
public class MediaPathFilterTest {

    /** What SQLite's LIKE does with a pattern and the escape character (ASCII case insensitive); enough to show what the patterns mean. */
    private static boolean like(String pattern, String text, char escape) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == escape && i + 1 < pattern.length()) {
                regex.append(Pattern.quote(String.valueOf(pattern.charAt(++i))));
            } else if (c == '%') {
                regex.append(".*");
            } else if (c == '_') {
                regex.append(".");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(text).matches();
    }

    private static List<String> patterns(String... paths) {
        Context context = mock(Context.class);
        SharedPreferences preferences = mock(SharedPreferences.class);
        when(preferences.getBoolean(any(), anyBoolean())).thenReturn(true);
        when(preferences.getStringSet(any(), any())).thenReturn(new HashSet<>(Arrays.asList(paths)));
        try (MockedStatic<PreferenceManager> manager = Mockito.mockStatic(PreferenceManager.class)) {
            manager.when(() -> PreferenceManager.getDefaultSharedPreferences(any())).thenReturn(preferences);
            return MediaPathFilter.getMediaPathesForLikeClause(context);
        }
    }

    @Test
    public void everyConditionNamesTheEscapeCharacter() {
        assertEquals("_data like ? escape '\\'", MediaPathFilter.makeLikeClause("_data", 1));
        assertEquals("_data like ? escape '\\' or _data like ? escape '\\' ", MediaPathFilter.makeLikeClause("_data", 2));
    }

    @Test
    public void thePatternCharactersOfAPathAreEscaped() {
        assertEquals("a\\_b\\%c\\\\d", MediaPathFilter.escapeLike("a_b%c\\d"));
        assertEquals("/storage/emulated/0/Music", MediaPathFilter.escapeLike("/storage/emulated/0/Music"));
        assertEquals("", MediaPathFilter.escapeLike(""));
    }

    @Test
    public void thePatternsAreThePathAnywhereInTheColumn() {
        assertEquals(Arrays.asList("%/My\\_Music%"), patterns("/My_Music"));
        assertEquals(Arrays.asList("%100\\%%"), patterns("100%"));
    }

    @Test
    public void aPathWithAnUnderscoreDoesNotMatchADirectoryThatDiffersAtThatPlace() {
        String pattern = patterns("/storage/emulated/0/My_Music").get(0);

        assertTrue(like(pattern, "/storage/emulated/0/My_Music/a.mp3", '\\'));
        assertFalse(like(pattern, "/storage/emulated/0/MyXMusic/a.mp3", '\\'));
    }

    @Test
    public void aPathWithAPercentSignDoesNotMatchEverything() {
        String pattern = patterns("/storage/emulated/0/100%").get(0);

        assertTrue(like(pattern, "/storage/emulated/0/100%/a.mp3", '\\'));
        assertFalse(like(pattern, "/storage/emulated/0/1000/a.mp3", '\\'));
        assertFalse(like(pattern, "/storage/emulated/0/100", '\\'));
    }

    @Test
    public void aBackslashInAPathIsTakenLiterally() {
        String pattern = patterns("/a\\b").get(0);

        assertTrue(like(pattern, "/x/a\\b/y", '\\'));
        assertFalse(like(pattern, "/x/ab/y", '\\'));
    }

    @Test
    public void withTheFilterOffEveryPathMatches() {
        Context context = mock(Context.class);
        SharedPreferences preferences = mock(SharedPreferences.class);
        when(preferences.getBoolean(any(), anyBoolean())).thenReturn(false);
        try (MockedStatic<PreferenceManager> manager = Mockito.mockStatic(PreferenceManager.class)) {
            manager.when(() -> PreferenceManager.getDefaultSharedPreferences(any())).thenReturn(preferences);

            List<String> patterns = MediaPathFilter.getMediaPathesForLikeClause(context);

            assertEquals(Arrays.asList("%%"), patterns);
            assertTrue(like(patterns.get(0), "/any/path", '\\'));
        }
    }
}
