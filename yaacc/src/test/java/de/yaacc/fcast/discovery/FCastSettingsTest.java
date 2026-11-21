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
package de.yaacc.fcast.discovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Task 5.1: the keys and defaults of the FCast settings are pinned against the resource files: the key is the name of a
 * string resource whose value is the key, the preference screen uses it with the default of the code, and every language has every
 * text (with the disclaimer of the trademark policy).
 */
public class FCastSettingsTest {

    private static final String[] LOCALES = {"values", "values-de", "values-es", "values-fr", "values-nl", "values-pt", "values-zh"};

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(new File("src/main/res/" + path).toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void theDiscoveryKeyIsAStringResourceWhoseValueIsTheKey() throws Exception {
        String english = read("values/setting_strings.xml");

        assertTrue(english.contains("<string name=\"" + FCastSettings.KEY_DISCOVERY + "\" translatable=\"false\">" + FCastSettings.KEY_DISCOVERY + "</string>"));
        assertTrue(english.contains("<string name=\"settings_fcast_receivers\" translatable=\"false\">settings_fcast_receivers</string>"));
    }

    @Test
    public void theScreenUsesTheKeyWithTheDefaultOfTheCode() throws Exception {
        String xml = read("xml/preference.xml");
        int at = xml.indexOf("android:key=\"@string/" + FCastSettings.KEY_DISCOVERY + "\"");
        assertTrue(at > 0);
        int start = xml.lastIndexOf("<CheckBoxPreference", at);
        int end = xml.indexOf("/>", at);
        String element = xml.substring(start, end);

        assertTrue(element, element.contains("android:defaultValue=\"" + FCastSettings.DISCOVERY_DEFAULT + "\""));
        assertTrue(element, element.contains("@string/settings_fcast_discovery_summary"));
        assertTrue(element, element.contains("@string/settings_fcast_discovery_title"));
        assertEquals("one category with a clear name", 1, xml.split("@string/settings_fcast_category", -1).length - 1);
        assertTrue(xml.contains("android:key=\"@string/settings_fcast_receivers\""));
    }

    @Test
    public void everyLanguageHasEveryText() throws Exception {
        String[] settingTexts = {"settings_fcast_category", "settings_fcast_about_title", "settings_fcast_about_summary",
                "settings_fcast_discovery_title", "settings_fcast_discovery_summary", "settings_fcast_receivers_title",
                "settings_fcast_receivers_summary"};
        String[] screenTexts = {"fcast_item_too_large", "fcast_link_addresses", "fcast_link_name", "fcast_link_not_a_link",
                "fcast_link_note", "fcast_link_title", "fcast_link_unnamed", "fcast_offline", "fcast_play_failed",
                "fcast_receivers_add", "fcast_receivers_add_hint", "fcast_receivers_added", "fcast_receivers_adding",
                "fcast_receivers_discovery_copied", "fcast_receivers_discovery_copy", "fcast_receivers_discovery_note",
                "fcast_receivers_discovery_title", "fcast_receivers_empty", "fcast_receivers_error_already",
                "fcast_receivers_error_ambiguous", "fcast_receivers_error_bad_link", "fcast_receivers_error_empty",
                "fcast_receivers_error_full", "fcast_receivers_error_invalid", "fcast_receivers_error_link_local",
                "fcast_receivers_error_link_no_address", "fcast_receivers_error_link_no_service",
                "fcast_receivers_error_not_found", "fcast_receivers_error_unreachable", "fcast_receivers_kind_found",
                "fcast_receivers_kind_manual", "fcast_receivers_kind_manual_found", "fcast_receivers_kind_remembered",
                "fcast_receivers_remove", "fcast_receivers_removed", "fcast_receivers_tried_found",
                "fcast_receivers_tried_search", "fcast_receivers_tried_search_off", "fcast_receivers_tried_system",
                "fcast_row_idle", "fcast_row_not_reachable", "fcast_row_settings", "fcast_row_state_title", "fcast_row_type",
                "fcast_sheet_address", "fcast_sheet_app", "fcast_sheet_check", "fcast_sheet_check_failed", "fcast_sheet_check_ok",
                "fcast_sheet_check_running", "fcast_sheet_mute", "fcast_sheet_name", "fcast_sheet_not_known",
                "fcast_sheet_protocol", "fcast_sheet_protocol_other", "fcast_sheet_protocol_v3", "fcast_sheet_reconnect",
                "fcast_sheet_remove", "fcast_sheet_removed", "fcast_sheet_section_connection", "fcast_sheet_section_device",
                "fcast_sheet_section_manage", "fcast_sheet_section_playback", "fcast_sheet_section_trouble", "fcast_sheet_source",
                "fcast_sheet_speed", "fcast_sheet_state", "fcast_sheet_state_connected", "fcast_sheet_state_connecting",
                "fcast_sheet_state_failed", "fcast_sheet_state_not_connected", "fcast_sheet_state_reconnecting",
                "fcast_sheet_stop", "fcast_sheet_too_many", "fcast_sheet_trace_copied", "fcast_sheet_trace_copy",
                "fcast_sheet_trace_empty", "fcast_sheet_trace_note", "fcast_sheet_volume", "fcast_unreachable"};
        for (String locale : LOCALES) {
            String settings = read(locale + "/setting_strings.xml");
            for (String name : settingTexts) {
                assertTrue(locale + " lacks " + name, settings.contains("<string name=\"" + name + "\">"));
            }
            String screen = read(locale + "/strings.xml");
            for (String name : screenTexts) {
                assertTrue(locale + " lacks " + name, screen.contains("<string name=\"" + name + "\">"));
            }
        }
    }

    @Test
    public void theDisclaimerOfTheTrademarkPolicyIsInTheEnglishSummaryAndTheNamesAreNotLogos() throws Exception {
        String english = read("values/setting_strings.xml");

        assertTrue(english.contains("independent community implementation of the FCast protocol"));
        assertTrue(english.contains("not affiliated with FUTO"));
        assertTrue(english.contains("FCast is a registered trademark of FUTO"));
        assertTrue(english.contains("<string name=\"settings_fcast_category\">FCast</string>"));
    }

    @Test
    public void theSummaryOfTheSwitchSaysThatAnnouncementsAreNotAuthenticatedAndTheDataIsUnencrypted() throws Exception {
        String english = read("values/setting_strings.xml");

        assertTrue(english.contains("not authenticated"));
        assertTrue(english.contains("unencrypted"));
    }

    @Test
    public void theSwitchIsReadWithItsDefaultAndAnUnreadableValueIsOff() {
        assertTrue(FCastSettings.isDiscoveryEnabled((key, def) -> def));
        assertFalse(FCastSettings.isDiscoveryEnabled((key, def) -> {
            throw new ClassCastException("damaged");
        }));
        assertFalse(FCastSettings.isDiscoveryEnabled(null));
        assertTrue(FCastSettings.isDiscoveryKey("settings_fcast_discovery"));
        assertFalse(FCastSettings.isDiscoveryKey("settings_snapcast_discovery"));
    }

    @Test
    public void theTextsAreTranslatedNotCopiedFromEnglish() throws Exception {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("<string name=\"(fcast_[a-z0-9_]+|settings_fcast_[a-z_]+)\">([^<]*)</string>");
        java.util.Map<String, String> english = new java.util.HashMap<>();
        for (String file : new String[]{"strings.xml", "setting_strings.xml"}) {
            java.util.regex.Matcher m = pattern.matcher(read("values/" + file));
            while (m.find()) {
                english.put(m.group(1), m.group(2));
            }
        }
        for (String locale : LOCALES) {
            if (locale.equals("values")) {
                continue;
            }
            int same = 0;
            for (String file : new String[]{"strings.xml", "setting_strings.xml"}) {
                java.util.regex.Matcher m = pattern.matcher(read(locale + "/" + file));
                while (m.find()) {
                    if (m.group(2).equals(english.get(m.group(1)))) {
                        same++;
                    }
                }
            }
            // a few texts are the same word in every language (FCast, offline, Volume, Name, Protocol)
            assertTrue(locale + " has " + same + " texts that are the English ones", same <= 14);
            assertFalse(locale + " the disclaimer is translated",
                    read(locale + "/setting_strings.xml").contains("independent community implementation of the FCast protocol"));
        }
        assertTrue(english.size() > 60);
    }
}
