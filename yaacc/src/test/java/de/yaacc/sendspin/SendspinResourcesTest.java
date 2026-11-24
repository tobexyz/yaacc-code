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
package de.yaacc.sendspin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The resources of the Sendspin settings, rows and messages: every text exists in the seven locales with the same format arguments
 * as the English one, the two preference keys are the ones the code reads, the switch is declared off by default (as the code and the
 * decision of the spec say), and the preference screen uses only resources that exist. Plain JVM: the resource files are read from
 * the module directory Gradle runs unit tests in.
 */
public class SendspinResourcesTest {

    private static final String[] LOCALES = {"values", "values-de", "values-es", "values-fr", "values-nl", "values-pt", "values-zh"};
    private static final String[] FILES = {"strings.xml", "setting_strings.xml"};

    private static String read(String relativePath) throws Exception {
        File file = new File(relativePath);
        assertTrue("missing " + file.getAbsolutePath(), file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /** All strings of the locale (both files) whose name mentions sendspin, name to text. */
    private static Map<String, String> sendspinStrings(String locale) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        for (String file : FILES) {
            Matcher m = Pattern.compile("<string name=\"([^\"]*sendspin[^\"]*)\"[^>]*>([^<]*)</string>")
                    .matcher(read("src/main/res/" + locale + "/" + file));
            while (m.find()) {
                assertFalse(locale + " defines " + m.group(1) + " twice", result.containsKey(m.group(1)));
                result.put(m.group(1), m.group(2));
            }
        }
        return result;
    }

    private static List<String> formatArguments(String text) {
        List<String> arguments = new ArrayList<>();
        Matcher m = Pattern.compile("%(\\d+\\$)?[sd%]").matcher(text);
        while (m.find()) {
            arguments.add(m.group());
        }
        Collections.sort(arguments);
        return arguments;
    }

    @Test
    public void everyTextExistsInAllSevenLocalesWithTheSameFormatArguments() throws Exception {
        Map<String, String> english = sendspinStrings("values");
        assertTrue("the English texts are there: " + english.size(), english.size() >= 30);
        for (String locale : LOCALES) {
            Map<String, String> strings = sendspinStrings(locale);
            for (Map.Entry<String, String> entry : english.entrySet()) {
                boolean key = entry.getKey().equals("settings_sendspin_enabled") || entry.getKey().equals("settings_sendspin_players")
                        || entry.getKey().equals("settings_sendspin_discovery")
                        || entry.getKey().equals("settings_sendspin_controller_enabled")
                        || entry.getKey().equals("settings_sendspin_unpaired_servers")
                        || entry.getKey().equals("settings_sendspin_servers")
                        || entry.getKey().equals("settings_sendspin_server_discovery")
                        || entry.getKey().equals("settings_sendspin_legacy")
                        || entry.getKey().equals("settings_sendspin_advertise");
                if (key && !locale.equals("values")) {
                    continue; // a preference key is not translated
                }
                assertTrue(locale + " lacks " + entry.getKey(), strings.containsKey(entry.getKey()));
                assertEquals(locale + " " + entry.getKey(), formatArguments(entry.getValue()), formatArguments(strings.get(entry.getKey())));
                assertFalse(locale + " " + entry.getKey() + " is empty", strings.get(entry.getKey()).trim().isEmpty());
            }
            for (String name : strings.keySet()) {
                assertTrue(locale + " has " + name + " that English does not", english.containsKey(name));
            }
        }
    }

    @Test
    public void everyApostropheIsEscapedSoThatTheResourceCompiles() throws Exception {
        for (String locale : LOCALES) {
            for (Map.Entry<String, String> entry : sendspinStrings(locale).entrySet()) {
                assertFalse(locale + " " + entry.getKey(), Pattern.compile("(?<!\\\\)'").matcher(entry.getValue()).find());
            }
        }
    }

    @Test
    public void thePreferenceKeysAreTheOnesTheCodeReads() throws Exception {
        Map<String, String> english = sendspinStrings("values");
        assertEquals(SendspinSettings.KEY_ENABLED, english.get("settings_sendspin_enabled"));
        assertEquals("settings_sendspin_players", english.get("settings_sendspin_players"));
        assertEquals(SendspinSettings.KEY_DISCOVERY, english.get("settings_sendspin_discovery"));
        String values = read("src/main/res/values/setting_strings.xml");
        assertTrue(values.contains("<string name=\"settings_sendspin_enabled\" translatable=\"false\">"));
        assertTrue(values.contains("<string name=\"settings_sendspin_players\" translatable=\"false\">"));
        assertTrue(values.contains("<string name=\"settings_sendspin_discovery\" translatable=\"false\">"));
    }

    @Test
    public void theSwitchIsDeclaredOffByDefaultLikeTheCode() throws Exception {
        String screen = read("src/main/res/xml/preference.xml");
        Matcher m = Pattern.compile("<CheckBoxPreference\\s+android:defaultValue=\"(true|false)\"\\s+android:key=\"@string/settings_sendspin_enabled\"")
                .matcher(screen);
        assertTrue("the switch is on the preference screen", m.find());
        assertEquals(String.valueOf(SendspinSettings.ENABLED_DEFAULT), m.group(1));
    }

    @Test
    public void theSearchSwitchIsOnByDefaultLikeTheCodeAndDependsOnTheMainSwitch() throws Exception {
        String screen = read("src/main/res/xml/preference.xml");
        Matcher m = Pattern.compile("<CheckBoxPreference\\s+android:defaultValue=\"(true|false)\"\\s+android:dependency=\"@string/settings_sendspin_enabled\"\\s+"
                + "android:key=\"@string/settings_sendspin_discovery\"").matcher(screen);
        assertTrue("the search switch is on the preference screen and depends on the main switch", m.find());
        assertEquals(String.valueOf(SendspinSettings.DISCOVERY_DEFAULT), m.group(1));
    }

    @Test
    public void everyResourceThePreferenceScreenUsesForSendspinExists() throws Exception {
        String screen = read("src/main/res/xml/preference.xml");
        Map<String, String> english = sendspinStrings("values");
        Matcher m = Pattern.compile("@string/(settings_sendspin_[a-z_]+)").matcher(screen);
        int found = 0;
        while (m.find()) {
            found++;
            assertTrue(m.group(1), english.containsKey(m.group(1)));
        }
        assertTrue(found >= 6);
    }

    @Test
    public void theAboutTextSaysItIsNotAffiliatedAndNamesTheTrademark() throws Exception {
        for (String locale : LOCALES) {
            String about = sendspinStrings(locale).get("settings_sendspin_about_summary");
            assertTrue(locale, about.contains("Open Home Foundation"));
        }
    }

    @Test
    public void theControllerSwitchesAreDeclaredLikeTheCodeAndTheKeysAreTheOnesItReads() throws Exception {
        Map<String, String> english = sendspinStrings("values");
        assertEquals(SendspinSettings.KEY_CONTROLLER_ENABLED, english.get("settings_sendspin_controller_enabled"));
        assertEquals(SendspinSettings.KEY_UNPAIRED_SERVERS, english.get("settings_sendspin_unpaired_servers"));
        assertEquals("settings_sendspin_servers", english.get("settings_sendspin_servers"));
        String screen = read("src/main/res/xml/preference.xml");
        Matcher controller = Pattern.compile("<CheckBoxPreference\\s+android:defaultValue=\"(true|false)\"\\s+android:key=\"@string/settings_sendspin_controller_enabled\"")
                .matcher(screen);
        assertTrue("the controller switch is on the preference screen", controller.find());
        assertEquals(String.valueOf(SendspinSettings.CONTROLLER_ENABLED_DEFAULT), controller.group(1));
        Matcher unpaired = Pattern.compile("<CheckBoxPreference\\s+android:defaultValue=\"(true|false)\"\\s+android:dependency=\"@string/settings_sendspin_controller_enabled\"\\s+"
                + "android:key=\"@string/settings_sendspin_unpaired_servers\"").matcher(screen);
        assertTrue("the unpaired switch depends on the controller switch", unpaired.find());
        assertEquals(String.valueOf(SendspinSettings.UNPAIRED_SERVERS_DEFAULT), unpaired.group(1));
        assertFalse("the controller is off by default until it has run on a device", SendspinSettings.CONTROLLER_ENABLED_DEFAULT);
    }

    @Test
    public void theControllerSettingsReadTheirValuesAndDefaults() {
        assertFalse(SendspinSettings.isControllerEnabled((key, def) -> def));
        assertTrue(SendspinSettings.isControllerEnabled((key, def) -> SendspinSettings.KEY_CONTROLLER_ENABLED.equals(key) || def));
        assertFalse(SendspinSettings.isControllerEnabled(null));
        assertFalse(SendspinSettings.isControllerEnabled((key, def) -> {
            throw new ClassCastException();
        }));
        assertTrue("unpaired servers are allowed by default", SendspinSettings.isUnpairedServersAllowed((key, def) -> def));
        assertFalse(SendspinSettings.isUnpairedServersAllowed((key, def) -> !SendspinSettings.KEY_UNPAIRED_SERVERS.equals(key) && def));
        assertFalse(SendspinSettings.isUnpairedServersAllowed(null));
    }

    @Test
    public void theServerSearchSwitchIsOnByDefaultLikeTheCodeAndDependsOnTheControllerSwitch() throws Exception {
        assertEquals(SendspinSettings.KEY_SERVER_DISCOVERY, sendspinStrings("values").get("settings_sendspin_server_discovery"));
        String screen = read("src/main/res/xml/preference.xml");
        Matcher m = Pattern.compile("<CheckBoxPreference\\s+android:defaultValue=\"(true|false)\"\\s+android:dependency=\"@string/settings_sendspin_controller_enabled\"\\s+"
                + "android:key=\"@string/settings_sendspin_server_discovery\"").matcher(screen);
        assertTrue("the server search switch is on the preference screen", m.find());
        assertEquals(String.valueOf(SendspinSettings.SERVER_DISCOVERY_DEFAULT), m.group(1));
    }

    @Test
    public void theServerSearchRunsOnlyWhenTheControllerAndItsOwnSwitchAreOn() {
        assertFalse("everything at its default: the controller is off", SendspinSettings.isServerDiscoveryEnabled((key, def) -> def));
        assertTrue(SendspinSettings.isServerDiscoveryEnabled((key, def) -> SendspinSettings.KEY_CONTROLLER_ENABLED.equals(key) || def));
        assertFalse(SendspinSettings.isServerDiscoveryEnabled((key, def) ->
                SendspinSettings.KEY_CONTROLLER_ENABLED.equals(key) || (!SendspinSettings.KEY_SERVER_DISCOVERY.equals(key) && def)));
        assertFalse(SendspinSettings.isServerDiscoveryEnabled(null));
        assertTrue(SendspinSettings.isServerDiscoveryRelevantKey(SendspinSettings.KEY_CONTROLLER_ENABLED));
        assertTrue(SendspinSettings.isServerDiscoveryRelevantKey(SendspinSettings.KEY_SERVER_DISCOVERY));
        assertFalse(SendspinSettings.isServerDiscoveryRelevantKey(SendspinSettings.KEY_ENABLED));
    }

    @Test
    public void theLegacyAndAdvertiseSwitchesAreOffByDefaultInTheCodeAndOnTheScreen() throws Exception {
        assertEquals(SendspinSettings.KEY_LEGACY, sendspinStrings("values").get("settings_sendspin_legacy"));
        assertEquals(SendspinSettings.KEY_ADVERTISE, sendspinStrings("values").get("settings_sendspin_advertise"));
        assertFalse(SendspinSettings.LEGACY_DEFAULT);
        assertFalse(SendspinSettings.ADVERTISE_DEFAULT);
        String screen = read("src/main/res/xml/preference.xml");
        for (String key : new String[]{"settings_sendspin_legacy", "settings_sendspin_advertise"}) {
            Matcher m = Pattern.compile("<CheckBoxPreference\\s+android:defaultValue=\"(true|false)\"\\s+android:dependency=\"@string/settings_sendspin_enabled\"\\s+"
                    + "android:key=\"@string/" + key + "\"").matcher(screen);
            assertTrue(key + " is on the screen and depends on the main switch", m.find());
            assertEquals(key + " is off by default", "false", m.group(1));
        }
    }

    @Test
    public void theLegacyAndAdvertiseSettingsReadTheirValuesAndNeedTheMainSwitch() {
        assertFalse("off by default", SendspinSettings.isLegacyAllowed((key, def) -> def));
        assertTrue(SendspinSettings.isLegacyAllowed((key, def) -> SendspinSettings.KEY_LEGACY.equals(key) || def));
        assertFalse(SendspinSettings.isLegacyAllowed(null));
        assertFalse("off by default", SendspinSettings.isAdvertiseEnabled((key, def) -> def));
        assertFalse("the switch alone does nothing while Sendspin is off",
                SendspinSettings.isAdvertiseEnabled((key, def) -> SendspinSettings.KEY_ADVERTISE.equals(key) || def));
        assertTrue(SendspinSettings.isAdvertiseEnabled((key, def) ->
                SendspinSettings.KEY_ADVERTISE.equals(key) || SendspinSettings.KEY_ENABLED.equals(key) || def));
        assertTrue(SendspinSettings.isAdvertiseRelevantKey(SendspinSettings.KEY_ADVERTISE));
        assertTrue(SendspinSettings.isAdvertiseRelevantKey(SendspinSettings.KEY_ENABLED));
        assertFalse(SendspinSettings.isAdvertiseRelevantKey(SendspinSettings.KEY_LEGACY));
    }
}
