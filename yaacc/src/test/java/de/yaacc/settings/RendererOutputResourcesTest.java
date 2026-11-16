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
            "renderer_output_error_unknown_type",
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

    @Test
    public void theSummaryOfTheOutputSaysTheAudioIsUnencryptedInEveryLocale() throws Exception {
        // Fix Group 9b W2: choosing the group starts the same feed as the play option, so the same disclosure is there; since
        // 2026-11-16 (issue 319 task 12.20) the wording "experimental" / "not tested on a device yet" is gone, the facts stay
        String[][] words = {
                {"values", "unencrypted"},
                {"values-de", "unverschlüsselt"},
                {"values-es", "sin cifrar"},
                {"values-fr", "non chiffré"},
                {"values-nl", "onversleuteld"},
                {"values-pt", "sem encriptação"},
                {"values-zh", "未加密"}};
        String[] gone = {"xperiment", "xpérimental", "实验", "Not tested", "not tested", "noch nicht getestet", "Aún no se ha probado",
                "Pas encore testé", "Nog niet op een apparaat getest", "Ainda não foi testado", "尚未在设备上测试"};
        for (String[] row : words) {
            String text = strings(row[0], "setting_strings.xml").get("settings_renderer_output_summary");
            for (int i = 1; i < row.length; i++) {
                assertTrue(row[0] + " summary lacks \"" + row[i] + "\": " + text, text.contains(row[i]));
            }
            for (String old : gone) {
                assertFalse(row[0] + " summary still says \"" + old + "\": " + text, text.contains(old));
            }
        }
    }

    @Test
    public void theMessageForAnUnknownTypeSaysWhatIsMissing() throws Exception {
        String english = strings("values", "strings.xml").get("renderer_output_error_unknown_type");

        assertTrue(english, english.contains("type of this content is unknown"));
        assertTrue(english, english.contains("Nothing was played"));
    }

    @Test
    public void theDocumentationTellsTheDisclosureWhereTheOutputIsDescribed() throws Exception {
        // the docs live one level above the module directory
        for (String file : new String[]{"../README.md", "../CHANGELOG.md", "../docs/settings.md"}) {
            String text = read(file);
            int at = text.indexOf("renderer_output");
            String paragraph = at >= 0 ? text.substring(at) : text;
            int start = file.endsWith("settings.md") ? Math.max(0, text.indexOf("output of the media receiver")) :
                    text.indexOf(file.endsWith("README.md") ? "The YAACC renderer into a group" : "Media renderer output: play into a Snapcast group");
            assertTrue(file + " describes the output", start >= 0);
            String entry = text.substring(start, Math.min(text.length(), start + 2500));
            assertTrue(file + " says unencrypted: " + entry, entry.contains("unencrypted"));
            assertFalse(file + " no longer says it is not tested on a device: " + entry, entry.contains("ot tested on a device"));
            assertFalse(file + " no longer calls it experimental: " + entry, entry.toLowerCase(java.util.Locale.ROOT).contains("experimental"));
        }
    }

    @Test
    public void theSummaryOfTheOutputSaysThatAnyDeviceThatReachesTheReceiverCanChangeTheGroupsVolumeInEveryLocale() throws Exception {
        // Fix Group 9d S74
        String[][] words = {
                {"values", "Any device that can reach the receiver", "volume and mute of the whole group"},
                {"values-de", "Jedes Gerät, das den Empfänger erreicht", "Lautstärke und Stumm der ganzen Gruppe"},
                {"values-es", "Cualquier dispositivo que pueda llegar al receptor", "el volumen y el silencio de todo el grupo"},
                {"values-fr", "Tout appareil qui peut joindre le récepteur", "le volume et la sourdine de tout le groupe"},
                {"values-nl", "Elk apparaat dat de ontvanger kan bereiken", "het volume en het dempen van de hele groep"},
                {"values-pt", "Qualquer dispositivo que consiga alcançar o recetor", "o volume e o silêncio de todo o grupo"},
                {"values-zh", "任何能访问该接收器的设备", "整个组的音量和静音"}};
        for (String[] row : words) {
            String text = strings(row[0], "setting_strings.xml").get("settings_renderer_output_summary");
            for (int i = 1; i < row.length; i++) {
                assertTrue(row[0] + " summary lacks \"" + row[i] + "\": " + text, text.contains(row[i]));
            }
            assertFalse(row[0] + " has a double space", text.contains("  "));
        }
    }

    @Test
    public void theSummaryOfTheOutputIsShortEnoughForTheListLayoutInEveryLocale() throws Exception {
        // Fix Group 9e W8: a ListPreference shows at most 10 lines; on a narrow phone with a large font the last sentence of a
        // long summary is cut off, so the disclosures are in a short text, the unencrypted audio first
        final int limit = 330;
        String[][] disclosures = {
                {"values", "unencrypted", "Any device that can reach the receiver"},
                {"values-de", "unverschlüsselt", "Jedes Gerät, das den Empfänger erreicht"},
                {"values-es", "sin cifrar", "Cualquier dispositivo que pueda llegar al receptor"},
                {"values-fr", "non chiffré", "Tout appareil qui peut joindre le récepteur"},
                {"values-nl", "onversleuteld", "Elk apparaat dat de ontvanger kan bereiken"},
                {"values-pt", "sem encriptação", "Qualquer dispositivo que consiga alcançar o recetor"},
                {"values-zh", "未加密", "任何能访问该接收器的设备"}};
        for (String[] row : disclosures) {
            String text = strings(row[0], "setting_strings.xml").get("settings_renderer_output_summary").replace("\\'", "'");
            assertTrue(row[0] + " summary has " + text.length() + " characters: " + text, text.length() <= limit);
            for (int k = 1; k < row.length; k++) {
                int at = text.indexOf(row[k]);
                assertTrue(row[0] + " lacks " + row[k], at >= 0);
                assertTrue(row[0] + " has " + row[k] + " too late (" + at + ")", at < limit - 60);
            }
            assertTrue(row[0] + ": the unencrypted audio is said before the exposure of the group",
                    text.indexOf(row[1]) < text.indexOf(row[2]));
        }
    }
}
