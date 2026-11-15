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

import de.yaacc.multiroom.SnapcastRendererOutput;
import de.yaacc.player.PlayerService;

/**
 * The setting of the renderer's output and its texts (task 9.1): the keys in the preference screen are the keys the code
 * reads, the output defaults to this device, the group chooser depends on the receiver switch and persists nothing by
 * itself, every text exists in the seven locales with the same format arguments, and every problem that can refuse a play has
 * a text of its own. Plain JVM: the resource files are read from the module directory.
 */
public class RendererOutputResourcesTest {

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

    private static List<String> formatArguments(String text) {
        List<String> arguments = new ArrayList<>();
        Matcher m = Pattern.compile("%(\\d+\\$)?[sd%]").matcher(text);
        while (m.find()) {
            arguments.add(m.group());
        }
        java.util.Collections.sort(arguments);
        return arguments;
    }

    private static final String[] SETTING_TEXTS = {"settings_renderer_output_title", "settings_renderer_output_summary",
            "settings_renderer_output_device", "settings_renderer_output_group_option", "settings_renderer_output_group_title",
            "settings_renderer_output_group_none", "settings_renderer_output_group_server", "settings_renderer_output_group_named",
            "settings_renderer_output_group_damaged"};

    private static final String[] PLAY_TEXTS = {"renderer_output_damaged_note", "renderer_output_error_no_group",
            "renderer_output_error_server_not_listed", "renderer_output_error_unreachable", "renderer_output_error_group_gone",
            "renderer_group_server_title", "renderer_group_group_title", "renderer_group_no_servers", "renderer_group_reading",
            "renderer_group_unreachable", "renderer_group_none", "renderer_group_members"};

    @Test
    public void theKeysOfThePreferenceScreenAreTheKeysTheCodeReads() throws Exception {
        Map<String, String> keys = strings("values", "setting_strings.xml");

        assertEquals(SnapcastRendererOutput.KEY_OUTPUT, keys.get("settings_renderer_output"));
        assertEquals(SnapcastRendererOutput.KEY_GROUP, keys.get("settings_renderer_output_group"));
        String xml = read("src/main/res/xml/preference.xml");
        assertTrue(xml.contains("android:key=\"@string/settings_renderer_output\""));
        assertTrue(xml.contains("android:key=\"@string/settings_renderer_output_group\""));
        assertFalse("no trademark in a key", keys.get("settings_renderer_output").toLowerCase().contains("snapcast")
                || keys.get("settings_renderer_output_group").toLowerCase().contains("snapcast"));
    }

    @Test
    public void theOutputIsAListThatDefaultsToThisDeviceAndDependsOnTheReceiverSwitch() throws Exception {
        String xml = read("src/main/res/xml/preference.xml");
        int at = xml.indexOf("android:key=\"@string/settings_renderer_output\"");
        int start = xml.lastIndexOf("<ListPreference", at);
        String element = xml.substring(start, xml.indexOf("/>", at));

        assertTrue(element, element.contains("android:defaultValue=\"" + SnapcastRendererOutput.VALUE_DEVICE + "\""));
        assertTrue(element, element.contains("android:dependency=\"@string/settings_local_server_receiver_chkbx\""));
        assertTrue("it sits in the UPnP server category, behind the receiver switch",
                xml.indexOf("@string/settings_local_server_receiver_chkbx\"") < at
                        && at < xml.indexOf("@string/settings_local_server_provider_chkbx\""));
    }

    @Test
    public void theGroupChooserPersistsNothingByItselfAndStaysBehindTheOutput() throws Exception {
        String xml = read("src/main/res/xml/preference.xml");
        int at = xml.indexOf("android:key=\"@string/settings_renderer_output_group\"");
        int start = xml.lastIndexOf("<Preference", at);
        String element = xml.substring(start, xml.indexOf("/>", at));

        assertTrue(element, element.contains("android:persistent=\"false\"")); // the dialog stores the id itself
        assertTrue(element, element.contains("android:dependency=\"@string/settings_local_server_receiver_chkbx\""));
        assertTrue(xml.indexOf("settings_renderer_output\"") < at);
    }

    @Test
    public void everySettingTextExistsInEveryLocaleWithTheSameArguments() throws Exception {
        for (String name : SETTING_TEXTS) {
            String english = strings("values", "setting_strings.xml").get(name);
            assertTrue(name, english != null && !english.trim().isEmpty());
            for (String locale : LOCALES) {
                String text = strings(locale, "setting_strings.xml").get(name);
                assertTrue(locale + " lacks " + name, text != null && !text.trim().isEmpty());
                assertEquals(locale + " " + name, formatArguments(english), formatArguments(text));
                assertFalse(locale + " " + name + " has an unescaped apostrophe", Pattern.compile("(?<!\\\\)'").matcher(text).find());
            }
        }
    }

    @Test
    public void everyPlayAndChooserTextExistsInEveryLocaleWithTheSameArguments() throws Exception {
        for (String name : PLAY_TEXTS) {
            String english = strings("values", "strings.xml").get(name);
            assertTrue(name, english != null && !english.trim().isEmpty());
            for (String locale : LOCALES) {
                String text = strings(locale, "strings.xml").get(name);
                assertTrue(locale + " lacks " + name, text != null && !text.trim().isEmpty());
                assertEquals(locale + " " + name, formatArguments(english), formatArguments(text));
                assertFalse(locale + " " + name + " has an unescaped apostrophe", Pattern.compile("(?<!\\\\)'").matcher(text).find());
            }
        }
    }

    @Test
    public void theTextsAreTranslatedNotCopiedInMostLocales() throws Exception {
        // words that are the same in several languages are fine; a text that is English in most locales is not
        for (String name : PLAY_TEXTS) {
            String english = strings("values", "strings.xml").get(name);
            int different = 0;
            for (String locale : LOCALES) {
                if (!locale.equals("values") && !strings(locale, "strings.xml").get(name).equals(english)) {
                    different++;
                }
            }
            assertTrue(name + " is untranslated in most locales", different >= 5);
        }
        for (String name : SETTING_TEXTS) {
            String english = strings("values", "setting_strings.xml").get(name);
            int different = 0;
            for (String locale : LOCALES) {
                if (!locale.equals("values") && !strings(locale, "setting_strings.xml").get(name).equals(english)) {
                    different++;
                }
            }
            assertTrue(name + " is untranslated in most locales", different >= 4);
        }
    }

    @Test
    public void everyProblemThatRefusesAPlayHasATextAndTheTextsDiffer() {
        Set<Integer> texts = new HashSet<>();
        for (SnapcastRendererOutput.Problem problem : SnapcastRendererOutput.Problem.values()) {
            int text = PlayerService.textFor(problem);
            if (problem == SnapcastRendererOutput.Problem.NONE) {
                assertEquals(0, text);
            } else {
                assertNotEquals(problem.name(), 0, text);
                assertTrue(problem + " has its own text", texts.add(text));
            }
        }
        assertEquals(SnapcastRendererOutput.Problem.values().length - 1, texts.size());
    }

    @Test
    public void theMessagesNameTheCauseAndTheWayOut() throws Exception {
        Map<String, String> english = strings("values", "strings.xml");

        assertTrue(english.get("renderer_output_error_no_group").contains("Choose one in the settings"));
        assertTrue(english.get("renderer_output_error_server_not_listed").contains("server list"));
        assertTrue(english.get("renderer_output_error_unreachable").contains("does not answer"));
        assertTrue(english.get("renderer_output_error_group_gone").contains("not on its Snapcast server any more"));
        assertTrue(english.get("renderer_output_damaged_note").contains("playing on this device"));
    }
}
