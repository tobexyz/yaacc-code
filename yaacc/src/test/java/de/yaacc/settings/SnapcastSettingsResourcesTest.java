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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.yaacc.multiroom.SnapcastSettings;
import de.yaacc.multiroom.ui.SnapcastServerListModel;

/**
 * The resources of the Snapcast rows, sheet and settings (task 5.1 and 5.2): every new text exists in the seven locales
 * with the same format arguments as the English one, the discovery switch is declared on by default under the key the
 * code reads, no new resource has a color of its own, and every outcome of the server list has its own message. Plain
 * JVM: the resource files are read from the module directory Gradle runs unit tests in.
 */
public class SnapcastSettingsResourcesTest {

    private static final String[] LOCALES = {"values", "values-de", "values-es", "values-fr", "values-nl", "values-pt", "values-zh"};

    private static String read(String relativePath) throws Exception {
        File file = new File(relativePath);
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

    private static List<String> newNames(String file, String prefix) throws Exception {
        List<String> names = new ArrayList<>();
        for (String name : strings("values", file).keySet()) {
            if (name.startsWith(prefix)) {
                names.add(name);
            }
        }
        return names;
    }

    /** The format arguments of a text: %1$s, %2$d, %% ... */
    private static List<String> formatArguments(String text) {
        List<String> arguments = new ArrayList<>();
        Matcher m = Pattern.compile("%(\\d+\\$)?[sd%]").matcher(text);
        while (m.find()) {
            arguments.add(m.group());
        }
        java.util.Collections.sort(arguments);
        return arguments;
    }

    private static final String[] UI_PREFIXES = {"snapcast_row_", "snapcast_sheet_", "snapcast_member_", "snapcast_rename_",
            "snapcast_latency_", "snapcast_action_", "snapcast_servers_"};

    private static List<String> uiNames() throws Exception {
        List<String> names = new ArrayList<>();
        for (String prefix : UI_PREFIXES) {
            names.addAll(newNames("strings.xml", prefix));
        }
        return names;
    }

    @Test
    public void thereAreTextsForEveryPart() throws Exception {
        List<String> names = uiNames();
        for (String prefix : UI_PREFIXES) {
            boolean any = false;
            for (String name : names) {
                any |= name.startsWith(prefix);
            }
            assertTrue(prefix, any);
        }
        assertTrue(names.size() >= 50);
    }

    @Test
    public void everyUiTextExistsInEveryLocaleWithTheSameArguments() throws Exception {
        for (String name : uiNames()) {
            String english = strings("values", "strings.xml").get(name);
            for (String locale : LOCALES) {
                String text = strings(locale, "strings.xml").get(name);
                assertTrue(locale + " lacks " + name, text != null && !text.trim().isEmpty());
                assertEquals(locale + " " + name, formatArguments(english), formatArguments(text));
            }
        }
    }

    @Test
    public void theSettingsTextsExistInEveryLocaleWithTheSameArguments() throws Exception {
        List<String> names = newNames("setting_strings.xml", "settings_snapcast_");
        for (String name : new String[]{"settings_snapcast_discovery_title", "settings_snapcast_discovery_summary",
                "settings_snapcast_servers_title", "settings_snapcast_servers_summary", "settings_snapcast_about_title",
                "settings_snapcast_about_summary"}) {
            assertTrue(name, names.contains(name));
            String english = strings("values", "setting_strings.xml").get(name);
            for (String locale : LOCALES) {
                String text = strings(locale, "setting_strings.xml").get(name);
                assertTrue(locale + " lacks " + name, text != null && !text.trim().isEmpty());
                assertEquals(locale + " " + name, formatArguments(english), formatArguments(text));
            }
        }
    }

    @Test
    public void aTranslationIsNotJustTheEnglishTextExceptForWordsThatAreTheSame() throws Exception {
        Set<String> sameWordsAllowed = new HashSet<>(java.util.Arrays.asList("snapcast_sheet_members", "snapcast_sheet_stream",
                "snapcast_row_server", "snapcast_row_stream", "snapcast_row_volume"));
        for (String name : uiNames()) {
            if (sameWordsAllowed.contains(name)) {
                continue;
            }
            String english = strings("values", "strings.xml").get(name);
            int different = 0;
            for (String locale : LOCALES) {
                if (!locale.equals("values") && !strings(locale, "strings.xml").get(name).equals(english)) {
                    different++;
                }
            }
            assertTrue(name + " is untranslated in most locales", different >= 4);
        }
    }

    @Test
    public void theDiscoverySwitchIsOnByDefaultUnderTheKeyTheCodeReads() throws Exception {
        assertEquals(SnapcastSettings.KEY_DISCOVERY, strings("values", "setting_strings.xml").get("settings_snapcast_discovery"));
        String xml = read("src/main/res/xml/preference.xml");
        Matcher m = Pattern.compile("<CheckBoxPreference[^>]*?android:defaultValue=\"(\\w+)\"[^>]*?android:key=\"@string/settings_snapcast_discovery\"",
                Pattern.DOTALL).matcher(xml);
        assertTrue("the preference is declared", m.find());
        assertEquals("true", m.group(1));
        assertTrue(SnapcastSettings.DISCOVERY_DEFAULT);
    }

    @Test
    public void theServerListAndTheExplanationAreInTheSnapcastCategory() throws Exception {
        String xml = read("src/main/res/xml/preference.xml");
        int category = xml.indexOf("@string/settings_snapcast_category");
        int end = xml.indexOf("</PreferenceCategory>", category);
        String block = xml.substring(category, end);

        assertTrue(block.contains("@string/settings_snapcast_servers"));
        assertTrue(block.contains("@string/settings_snapcast_about_summary"));
        assertTrue(block.contains("@string/settings_snapcast_discovery_summary"));
        assertTrue("the experimental play option is still there", block.contains("@string/settings_snapcast_play_experimental"));
    }

    @Test
    public void theExplanationsSayWhatTheServerRequirementAndTheDiscoveryDo() throws Exception {
        Map<String, String> english = strings("values", "setting_strings.xml");

        assertTrue(english.get("settings_snapcast_about_summary").contains("existing Snapcast server"));
        assertTrue(english.get("settings_snapcast_about_summary").contains("reachable on your network"));
        assertTrue(english.get("settings_snapcast_discovery_summary").contains("contacts every server"));
    }

    @Test
    public void theSummaryOfTheDiscoverySwitchSaysHonestlyWhatItDoesWhenItIsOnInEveryLocale() throws Exception {
        // on by default (2026-11-14): the summary says that it is on, that it asks on the Wi-Fi, that it contacts every
        // server and that an announcement is not authenticated; it no longer talks of "leaving it off" as the normal state
        String[][] words = {
                {"values", "on by default", "Wi-Fi", "contacts every server", "not authenticated"},
                {"values-de", "standardmäßig an", "WLAN", "jeder Server kontaktiert", "nicht authentifiziert"},
                {"values-es", "activada de forma predeterminada", "Wi-Fi", "se contacta con todos los servidores", "no están autenticados"},
                {"values-fr", "activée par défaut", "Wi-Fi", "contacte tous les serveurs", "ne sont pas authentifiées"},
                {"values-nl", "standaard aan", "wifi", "contact gemaakt met elke server", "niet geauthenticeerd"},
                {"values-pt", "ativada por predefinição", "Wi-Fi", "contacta todos os servidores", "não são autenticados"},
                {"values-zh", "默认开启", "Wi-Fi", "连接", "未经身份验证"}};
        for (String[] row : words) {
            String text = strings(row[0], "setting_strings.xml").get("settings_snapcast_discovery_summary");
            for (int i = 1; i < row.length; i++) {
                assertTrue(row[0] + " summary lacks \"" + row[i] + "\": " + text, text.contains(row[i]));
            }
        }
    }

    @Test
    public void everyOutcomeOfTheServerListHasAMessageAndTheProblemsDiffer() {
        Set<Integer> messages = new HashSet<>();
        for (SnapcastServerListModel.Outcome outcome : SnapcastServerListModel.Outcome.values()) {
            int id = SnapcastServersDialog.messageFor(outcome);
            assertNotEquals(outcome.name(), 0, id);
            if (outcome != SnapcastServerListModel.Outcome.ADDED) {
                messages.add(id);
            }
        }
        assertEquals(SnapcastServerListModel.Outcome.values().length - 1, messages.size());
    }

    @Test
    public void theNewDrawablesUseNoColorOfTheirOwn() throws Exception {
        for (String name : new String[]{"ic_snapcast_more", "ic_snapcast_remove"}) {
            String xml = read("src/main/res/drawable/" + name + ".xml");
            assertFalse(name, Pattern.compile("#[0-9A-Fa-f]{3,8}\\b").matcher(xml).find());
            assertTrue(name + " is tinted by the theme", xml.contains("?attr/colorControlNormal"));
        }
    }

    @Test
    public void noNewStringResourceHasAColorOrMarkupThatLintCouldTakeForOne() throws Exception {
        for (String name : uiNames()) {
            for (String locale : LOCALES) {
                String text = strings(locale, "strings.xml").get(name);
                assertFalse(locale + " " + name, text.contains("#") && Pattern.compile("#[0-9A-Fa-f]{6}").matcher(text).find());
                assertFalse(locale + " " + name + " has an unescaped apostrophe", Pattern.compile("(?<!\\\\)'").matcher(text).find());
            }
        }
    }

    @Test
    public void theNoteBesideTheReportButtonSaysTheReportCarriesThePhoneModel() throws Exception {
        // Group 11Cb S8: the report has the device model and the API level, the note says so in every language
        java.util.Map<String, String> word = new java.util.LinkedHashMap<>();
        word.put("values", "phone model");
        word.put("values-de", "Telefonmodell");
        word.put("values-es", "modelo del teléfono");
        word.put("values-fr", "modèle du téléphone");
        word.put("values-nl", "telefoonmodel");
        word.put("values-pt", "modelo do telemóvel");
        word.put("values-zh", "手机型号");
        for (java.util.Map.Entry<String, String> entry : word.entrySet()) {
            String note = strings(entry.getKey(), "strings.xml").get("snapcast_servers_discovery_note");
            assertTrue(entry.getKey() + ": " + note, note != null && note.contains(entry.getValue()));
        }
    }
}
