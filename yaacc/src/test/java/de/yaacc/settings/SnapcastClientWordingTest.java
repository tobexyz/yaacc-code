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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The words of the Snapcast client in the settings and its sheet (task 12.22, wording of the owner's finding on issue 319).
 * The three entries mean: the FIRST enables the feature at all (title <i>Snapcast client</i>), the SECOND says whether the
 * client is connected (<i>Connected to the Snapcast server</i>), the THIRD opens the debug sheet (<i>Snapcast client
 * details</i>). None of the texts calls it a listen test or a spike any more; the internal key names keep their old
 * spelling and are not user-visible. Plain JVM: the resource files are read from the module directory.
 */
public class SnapcastClientWordingTest {

    private static final String[] LOCALES = {"values", "values-de", "values-es", "values-fr", "values-nl", "values-pt", "values-zh"};

    private static final String FEATURE_TITLE = "settings_snapcast_client_spike_title";
    private static final String STATE_TITLE = "settings_snapcast_client_switched_on_title";
    private static final String DETAILS_TITLE = "settings_snapcast_client_spike_open_title";
    private static final String DIALOG_TITLE = "snapcast_client_title";

    /**
     * Per locale: phrases that no user-visible text of the app may contain at all (the old names of the sheet), and
     * words that no text named after Snapcast may contain (a debug sheet is details, not a test). Compared in lower case.
     * German: "Hörtest", "Test"; Spanish: "prueba", "escucha", "oído"; French: "test", "écoute", "oreille"; Dutch: "test",
     * "luister", "gehoor"; Portuguese: "teste", "audição", "ouvido"; Chinese: "测试", "试听", "试验", "耳朵".
     */
    private static final String[][] BANNED_EVERYWHERE = {
            {"values", "listen test", "listening test", "spike", "by ear"},
            {"values-de", "hörtest", "spike", "nach gehör"},
            {"values-es", "prueba de escucha", "spike", "de oído"},
            {"values-fr", "test d'écoute", "test d\\'écoute", "spike", "à l'oreille", "à l\\'oreille"},
            {"values-nl", "luistest", "luistertest", "spike", "op het gehoor"},
            {"values-pt", "teste de audição", "teste de escuta", "spike", "de ouvido"},
            {"values-zh", "试听", "听测", "试验", "spike", "用耳朵"},
    };
    private static final String[][] BANNED_IN_SNAPCAST_TEXTS = {
            {"values", "test", "listen"},
            {"values-de", "test", "hörtest"},
            {"values-es", "prueba", "escucha", "oído"},
            {"values-fr", "test", "écoute", "oreille"},
            {"values-nl", "test", "luister", "gehoor"},
            {"values-pt", "teste", "audição", "ouvido", "escuta"},
            {"values-zh", "测试", "试听", "试验", "耳朵"},
    };

    private static String read(String path) throws Exception {
        File file = new File(path);
        assertTrue("missing " + file.getAbsolutePath(), file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /** All translatable strings of the two string files of a locale (the key-name entries are not user-visible). */
    private static Map<String, String> userVisible(String locale) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        for (String file : new String[]{"strings.xml", "setting_strings.xml"}) {
            Matcher m = Pattern.compile("<string name=\"([^\"]+)\"([^>]*)>([^<]*)</string>")
                    .matcher(read("src/main/res/" + locale + "/" + file));
            while (m.find()) {
                if (!m.group(2).contains("translatable=\"false\"")) {
                    result.put(m.group(1), m.group(3));
                }
            }
        }
        return result;
    }

    private static String[] row(String[][] table, String locale) {
        for (String[] row : table) {
            if (row[0].equals(locale)) {
                return row;
            }
        }
        throw new AssertionError("no row for " + locale);
    }

    private void checkNoOldWordsIn(String locale) throws Exception {
        Map<String, String> texts = userVisible(locale);
        String[] everywhere = row(BANNED_EVERYWHERE, locale);
        String[] inSnapcast = row(BANNED_IN_SNAPCAST_TEXTS, locale);
        for (Map.Entry<String, String> entry : texts.entrySet()) {
            String lower = entry.getValue().toLowerCase(Locale.ROOT);
            for (int i = 1; i < everywhere.length; i++) {
                assertFalse(locale + " " + entry.getKey() + " still says '" + everywhere[i] + "': " + entry.getValue(),
                        lower.contains(everywhere[i]));
            }
            if (entry.getKey().toLowerCase(Locale.ROOT).contains("snapcast")) {
                for (int i = 1; i < inSnapcast.length; i++) {
                    assertFalse(locale + " " + entry.getKey() + " still says '" + inSnapcast[i] + "': " + entry.getValue(),
                            lower.contains(inSnapcast[i]));
                }
            }
        }
    }

    private void checkThreeTitles(String locale) throws Exception {
        Map<String, String> texts = userVisible(locale);
        String feature = texts.get(FEATURE_TITLE);
        String state = texts.get(STATE_TITLE);
        String details = texts.get(DETAILS_TITLE);
        assertNotNull(locale + " lacks " + FEATURE_TITLE, feature);
        assertNotNull(locale + " lacks " + STATE_TITLE, state);
        assertNotNull(locale + " lacks " + DETAILS_TITLE, details);
        assertFalse(locale + " feature title is empty", feature.trim().isEmpty());
        assertFalse(locale + " state title is empty", state.trim().isEmpty());
        assertFalse(locale + " details title is empty", details.trim().isEmpty());
        assertNotEquals(locale + ": feature and state have the same title", feature, state);
        assertNotEquals(locale + ": feature and details have the same title", feature, details);
        assertNotEquals(locale + ": state and details have the same title", state, details);
        // what the entry says is what the sheet it opens is called
        assertEquals(locale + ": the sheet is titled like the entry that opens it", details, texts.get(DIALOG_TITLE));
        // the two on/off texts of the state differ and the summaries of the three entries are no copy of a title
        assertNotEquals(locale, texts.get("settings_snapcast_client_switched_on_summary_on"),
                texts.get("settings_snapcast_client_switched_on_summary_off"));
        assertNotEquals(locale, feature, texts.get("settings_snapcast_client_spike_summary"));
        assertNotEquals(locale, details, texts.get("settings_snapcast_client_spike_open_summary"));
    }

    // ---- no listen test, no spike in any user-visible text of the seven locales --------------------------------------

    @Test
    public void noUserVisibleTextInAnyLocaleStillSaysListenTestOrSpike() throws Exception {
        for (String locale : LOCALES) {
            checkNoOldWordsIn(locale);
        }
    }

    @Test
    public void theTableOfOldWordsCoversEveryLocaleAndTheGuardCanFail() throws Exception {
        for (String locale : LOCALES) {
            assertTrue(locale, row(BANNED_EVERYWHERE, locale).length > 2);
            assertTrue(locale, row(BANNED_IN_SNAPCAST_TEXTS, locale).length > 2);
        }
        assertEquals(LOCALES.length, BANNED_EVERYWHERE.length);
        assertEquals(LOCALES.length, BANNED_IN_SNAPCAST_TEXTS.length);
        // the first English word list entry matches the wording that the owner found misleading
        assertTrue("Snapcast client listen test".toLowerCase(Locale.ROOT).contains(row(BANNED_EVERYWHERE, "values")[1]));
        assertTrue("Snapcast-Client-Hörtest".toLowerCase(Locale.ROOT).contains(row(BANNED_EVERYWHERE, "values-de")[1]));
    }

    // ---- the three titles exist and differ, per locale ---------------------------------------------------------------

    @Test
    public void english() throws Exception {
        checkThreeTitles("values");
        Map<String, String> texts = userVisible("values");
        assertEquals("Snapcast client", texts.get(FEATURE_TITLE));
        assertEquals("Connected to the Snapcast server", texts.get(STATE_TITLE));
        assertEquals("Snapcast client details", texts.get(DETAILS_TITLE));
        assertEquals("This phone is connected to a Snapcast server and plays as a client.",
                texts.get("settings_snapcast_client_switched_on_summary_on"));
        assertEquals("This phone is not connected to a Snapcast server.",
                texts.get("settings_snapcast_client_switched_on_summary_off"));
        assertTrue(texts.get("settings_snapcast_client_spike_summary").contains("Off by default"));
        assertTrue(texts.get("settings_snapcast_client_spike_summary").contains("unencrypted"));
        assertTrue(texts.get("settings_snapcast_client_spike_open_summary").contains("troubleshooting"));
        assertTrue("the refusal sends the user to the details", texts.get("snapcast_client_refused_no_server")
                .endsWith("in the Snapcast client details."));
    }

    @Test
    public void german() throws Exception {
        checkThreeTitles("values-de");
    }

    @Test
    public void spanish() throws Exception {
        checkThreeTitles("values-es");
    }

    @Test
    public void french() throws Exception {
        checkThreeTitles("values-fr");
    }

    @Test
    public void dutch() throws Exception {
        checkThreeTitles("values-nl");
    }

    @Test
    public void portuguese() throws Exception {
        checkThreeTitles("values-pt");
    }

    @Test
    public void chinese() throws Exception {
        checkThreeTitles("values-zh");
    }

    // ---- the entries stay what they were: keys, order, visibility ----------------------------------------------------

    @Test
    public void theThreeEntriesKeepTheirKeysAndOrder() throws Exception {
        String xml = read("src/main/res/xml/preference.xml");
        int feature = xml.indexOf("@string/settings_snapcast_client_spike\"");
        int state = xml.indexOf("@string/settings_snapcast_client_switched_on\"");
        int details = xml.indexOf("@string/settings_snapcast_client_spike_open\"");
        assertTrue(feature > 0 && state > feature && details > state);
        assertTrue(xml.contains("android:title=\"@string/" + FEATURE_TITLE + "\""));
        assertTrue(xml.contains("android:title=\"@string/" + STATE_TITLE + "\""));
        assertTrue(xml.contains("android:title=\"@string/" + DETAILS_TITLE + "\""));
        String keys = read("src/main/res/values/setting_strings.xml");
        assertTrue(keys.contains("<string name=\"settings_snapcast_client_spike\" translatable=\"false\">settings_snapcast_client_spike</string>"));
        assertTrue(keys.contains("<string name=\"settings_snapcast_client_switched_on\" translatable=\"false\">settings_snapcast_client_switched_on</string>"));
        assertTrue(keys.contains("<string name=\"settings_snapcast_client_spike_open\" translatable=\"false\">settings_snapcast_client_spike_open</string>"));
    }
}
