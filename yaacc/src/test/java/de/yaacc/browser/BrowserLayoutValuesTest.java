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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pins the values of the "Layout" preference (res/values/browser_layout.xml and the default in
 * res/xml/preference.xml) to the constants {@link BrowserLayoutChoice} understands, so renaming
 * one side cannot silently turn the setting into "always classic". Plain JVM: the resource files
 * are read from the module directory Gradle runs unit tests in.
 */
public class BrowserLayoutValuesTest {

    private static String read(String relativePath) throws Exception {
        File file = new File(relativePath);
        assertTrue("missing " + file.getAbsolutePath(), file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void preferenceValuesAreTheStoredConstants() throws Exception {
        String xml = read("src/main/res/values/browser_layout.xml");
        Matcher array = Pattern.compile(
                "<string-array name=\"browser_layout_values\"[^>]*>(.*?)</string-array>",
                Pattern.DOTALL).matcher(xml);
        assertTrue(array.find());
        List<String> values = new ArrayList<>();
        Matcher item = Pattern.compile("<item>(.*?)</item>").matcher(array.group(1));
        while (item.find()) {
            values.add(item.group(1).trim());
        }
        assertEquals(List.of(BrowserLayoutChoice.STORED_CLASSIC, BrowserLayoutChoice.STORED_NEW), values);
    }

    @Test
    public void preferenceDefaultIsTheDefaultStoredValue() throws Exception {
        String xml = read("src/main/res/xml/preference.xml");
        Matcher pref = Pattern.compile(
                "<ListPreference[^>]*android:defaultValue=\"([^\"]*)\"[^>]*settings_browser_layout_key",
                Pattern.DOTALL).matcher(xml);
        assertTrue(pref.find());
        assertEquals(BrowserLayoutChoice.DEFAULT_STORED_VALUE, pref.group(1));
    }
}
