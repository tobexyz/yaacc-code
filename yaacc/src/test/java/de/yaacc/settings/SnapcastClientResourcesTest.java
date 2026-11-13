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
package de.yaacc.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.yaacc.multiroom.client.SnapcastClientSpike;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The resources of the client spike: its texts exist in the seven locales with the same format arguments, the switch is
 * declared off by default under the key the code reads, and the manifest declares the foreground service with the
 * right type and no more permissions than it needs. Plain JVM: the files are read from the module directory.
 */
public class SnapcastClientResourcesTest {

    private static final String[] LOCALES = {"values", "values-de", "values-es", "values-fr", "values-nl", "values-pt", "values-zh"};

    private static String read(String path) throws Exception {
        File file = new File(path);
        assertTrue("missing " + file.getAbsolutePath(), file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static Map<String, String> strings(String locale, String file) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        Matcher m = Pattern.compile("<string name=\"([^\"]+)\"[^>]*>([^<]*)</string>")
                .matcher(read("src/main/res/" + locale + "/" + file));
        while (m.find()) {
            assertFalse(locale + " defines " + m.group(1) + " twice", result.containsKey(m.group(1)));
            result.put(m.group(1), m.group(2));
        }
        return result;
    }

    private static List<String> arguments(String text) {
        List<String> found = new ArrayList<>();
        Matcher m = Pattern.compile("%(\\d+\\$)?[sd%]").matcher(text);
        while (m.find()) {
            found.add(m.group());
        }
        java.util.Collections.sort(found);
        return found;
    }

    private void checkAllLocales(String file, String prefix, int atLeast) throws Exception {
        List<String> names = new ArrayList<>();
        for (String name : strings("values", file).keySet()) {
            if (name.startsWith(prefix) && !strings("values", file).get(name).equals(name)) {
                names.add(name);
            }
        }
        assertTrue(prefix + " texts: " + names.size(), names.size() >= atLeast);
        for (String name : names) {
            String english = strings("values", file).get(name);
            for (String locale : LOCALES) {
                String text = strings(locale, file).get(name);
                assertTrue(locale + " lacks " + name, text != null && !text.trim().isEmpty());
                assertEquals(locale + " " + name, arguments(english), arguments(text));
            }
        }
    }

    @Test
    public void theDialogAndNotificationTextsExistInEveryLocale() throws Exception {
        checkAllLocales("strings.xml", "snapcast_client_", 15);
    }

    @Test
    public void theSettingsTextsExistInEveryLocale() throws Exception {
        checkAllLocales("setting_strings.xml", "settings_snapcast_client_", 4);
    }

    @Test
    public void theSwitchIsOffByDefaultUnderTheKeyTheCodeReads() throws Exception {
        assertEquals(SnapcastClientSpike.KEY_ENABLED, strings("values", "setting_strings.xml").get("settings_snapcast_client_spike"));
        Matcher m = Pattern.compile("<CheckBoxPreference[^>]*?android:defaultValue=\"(\\w+)\"[^>]*?android:key=\"@string/settings_snapcast_client_spike\"",
                Pattern.DOTALL).matcher(read("src/main/res/xml/preference.xml"));
        assertTrue("the preference is declared", m.find());
        assertEquals("false", m.group(1));
        assertFalse(SnapcastClientSpike.ENABLED_DEFAULT);
    }

    @Test
    public void theTestScreenEntryIsInTheSnapcastCategory() throws Exception {
        String xml = read("src/main/res/xml/preference.xml");
        int category = xml.indexOf("@string/settings_snapcast_category");
        String block = xml.substring(category, xml.indexOf("</PreferenceCategory>", category));
        assertTrue(block.contains("@string/settings_snapcast_client_spike\""));
        assertTrue(block.contains("@string/settings_snapcast_client_spike_open\""));
    }

    @Test
    public void theServiceIsDeclaredAsAMediaPlaybackServiceThatIsNotExported() throws Exception {
        String manifest = read("src/main/AndroidManifest.xml");
        int at = manifest.indexOf(".multiroom.client.SnapcastClientService");
        assertTrue(at > 0);
        String declaration = manifest.substring(manifest.lastIndexOf("<service", at), manifest.indexOf("/>", at));
        assertTrue(declaration.contains("android:foregroundServiceType=\"mediaPlayback\""));
        assertTrue(declaration.contains("android:exported=\"false\""));
        assertFalse("no intent filter: only the app starts it", declaration.contains("intent-filter"));
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"));
        assertTrue(manifest.contains("android.permission.WAKE_LOCK"));
        assertTrue(manifest.contains("android.permission.CHANGE_WIFI_STATE"));
    }
}
