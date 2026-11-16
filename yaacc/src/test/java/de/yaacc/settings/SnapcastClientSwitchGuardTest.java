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

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Task 12C run 1 (12.6, 12.17, 12.18 c): the parts that need Android (the first page, the settings screen, the dialog, the
 * application object, the service) have no JVM test, so this pins how they are wired to the pure classes
 * ({@code SnapcastClientPower}, {@code SnapcastClientRestore}) and that the layout, the preference and the texts exist in every
 * locale. Comments are stripped before anything is looked for in Java; the XML files are read as text.
 */
public class SnapcastClientSwitchGuardTest {

    private static final File MAIN = new File("src/main");
    private static final String[] LOCALES = {"values", "values-de", "values-es", "values-fr", "values-nl", "values-pt", "values-zh"};
    private static final String KEY = "settings_snapcast_client_switched_on";

    private static String text(String relativePath) throws Exception {
        File file = new File(MAIN, relativePath);
        assertTrue("missing " + file.getAbsolutePath(), file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static String code(String relativePath) throws Exception {
        return text("java/de/yaacc/" + relativePath).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    /** The text of the method starting at the signature, from its opening brace to the matching closing brace. */
    private static String body(String code, String signature) {
        int start = code.indexOf(signature);
        assertTrue("method not found: " + signature, start >= 0);
        int open = code.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return code.substring(open, i + 1);
                }
            }
        }
        throw new AssertionError("unbalanced: " + signature);
    }

    private static void assertInOrder(String text, String... markers) {
        int last = -1;
        for (String marker : markers) {
            int at = text.indexOf(marker);
            assertTrue("missing: " + marker, at >= 0);
            assertTrue(marker + " must come after the one before it", at > last);
            last = at;
        }
    }

    // ---- the first page: a switch like the UPnP server toggle, and a gear -------------------------------------------

    @Test
    public void theFirstPageHasTheSnapcastRowNextToTheUpnpServerToggle() throws Exception {
        String xml = text("res/layout/fragment_server_root.xml");

        for (String id : new String[]{"serverListSnapcastClientRow", "serverListSnapcastClientLabel",
                "serverListSnapcastClientEnabled", "serverListSnapcastClientSettings"}) {
            assertTrue(id, xml.contains("@+id/" + id));
        }
        assertInOrder(xml, "@+id/serverListFooterSeparator", "@+id/serverListSnapcastClientRow", "@+id/serverListLocalServerLabel");
        // the row is hidden until the code decides (option on and a server set)
        Matcher row = Pattern.compile("<LinearLayout[^>]*?@\\+id/serverListSnapcastClientRow[^>]*?>", Pattern.DOTALL).matcher(xml);
        assertTrue(row.find());
        assertTrue("hidden by default", row.group().contains("android:visibility=\"gone\""));
    }

    @Test
    public void theRowHasNoSubtitleOnlyLabelSwitchAndGearLikeTheUpnpServerRow() throws Exception {
        // task 12.19 (the owner: "the switch in the bottom does not need a subtitle")
        String xml = text("res/layout/fragment_server_root.xml");
        String snapcast = xml.substring(xml.indexOf("@+id/serverListSnapcastClientRow"),
                xml.lastIndexOf("<LinearLayout", xml.indexOf("@+id/serverListLocalServerLabel")));

        assertFalse("no state line under the label", xml.contains("serverListSnapcastClientState"));
        assertEquals("the row holds the label, the switch and the gear and nothing else", 1, count(snapcast, "<TextView"));
        assertEquals(1, count(snapcast, "<com.google.android.material.switchmaterial.SwitchMaterial"));
        assertEquals(1, count(snapcast, "<ImageButton"));
        assertFalse("no vertical wrapper of label and subtitle", snapcast.contains("android:orientation=\"vertical\""));
        Matcher label = Pattern.compile("<TextView[^>]*?@\\+id/serverListSnapcastClientLabel[^>]*?/>", Pattern.DOTALL).matcher(snapcast);
        assertTrue(label.find());
        assertTrue("the label takes the width like the UPnP server label", label.group().contains("android:layout_width=\"0dp\"")
                && label.group().contains("android:layout_weight=\"1\"") && label.group().contains("android:maxLines=\"1\""));
    }

    @Test
    public void theStateIsKeptForTheScreenReaderAndTheDialogNotAsVisibleText() throws Exception {
        String bar = code("browser/SnapcastClientSwitchBar.java");

        assertFalse("no text view for the state", bar.contains("TextView") || bar.contains("stateText.") || bar.contains("serverListSnapcastClientState"));
        assertFalse("nothing writes a visible text on the row", bar.contains(".setText("));
        assertTrue("the state in words (with the reason of an ending) is the state description of the switch",
                bar.contains("setStateDescription(toggle, SnapcastClientPowerTexts.stateText(context, state))"));
        assertTrue("the switch keeps its label as content description", text("res/layout/fragment_server_root.xml").contains(
                "android:contentDescription=\"@string/snapcast_client_switch_label\""));
        assertTrue("a refused start is still a toast with its reason", body(bar, "private void toggled(boolean checked)").contains("Toast.makeText("));
        // the listen test dialog shows the state and so the reason of a restore that failed
        assertTrue(code("settings/SnapcastClientSpikeDialog.java").contains("SnapcastClientPowerTexts.stateText(context, state)"));
    }

    @Test
    public void theSwitchAndTheGearAreLabelledAndUseThemeAttributes() throws Exception {
        String xml = text("res/layout/fragment_server_root.xml");
        String snapcast = xml.substring(xml.indexOf("@+id/serverListSnapcastClientRow"), xml.indexOf("@+id/serverListLocalServerLabel"));

        Matcher toggle = Pattern.compile("<com\\.google\\.android\\.material\\.switchmaterial\\.SwitchMaterial[^>]*?@\\+id/serverListSnapcastClientEnabled[^>]*?/>",
                Pattern.DOTALL).matcher(snapcast);
        assertTrue("a Material switch like the UPnP server one", toggle.find());
        assertTrue(toggle.group().contains("android:contentDescription=\"@string/snapcast_client_switch_label\""));
        assertTrue("same touch size as the UPnP one", toggle.group().contains("android:minHeight=\"48dp\""));
        Matcher gear = Pattern.compile("<ImageButton[^>]*?@\\+id/serverListSnapcastClientSettings[^>]*?/>", Pattern.DOTALL).matcher(snapcast);
        assertTrue(gear.find());
        assertTrue(gear.group().contains("android:contentDescription=\"@string/snapcast_client_switch_settings\""));
        assertTrue(gear.group().contains("@drawable/ic_baseline_settings_42"));
        assertTrue("48dp touch target", gear.group().contains("android:layout_width=\"48dp\"") && gear.group().contains("android:layout_height=\"48dp\""));
        assertFalse("theme attributes only, no fixed colours", Pattern.compile("#[0-9a-fA-F]{3,8}\\b").matcher(snapcast).find());
        assertTrue(snapcast.contains("?attr/colorOnSurface"));
        assertTrue(snapcast.contains("?attr/colorControlNormal"));
    }

    @Test
    public void theClassicLayoutIsUntouched() throws Exception {
        assertFalse(text("res/layout/fragment_server_list.xml").contains("serverListSnapcastClient"));
    }

    @Test
    public void theFragmentOnlyBindsTheRowThroughTheSwitchBarAndStopsItWithTheScreen() throws Exception {
        String fragment = code("browser/ServerListFragment.java");
        assertTrue(fragment.contains("SnapcastClientSwitchBar"));
        assertTrue("started with the screen", body(fragment, "public void onStart()").contains("snapcastBar"));
        assertTrue("stopped with the screen: no poll while the page is away", body(fragment, "public void onStop()").contains("snapcastBar"));
        assertTrue("released with the view (it holds views)", body(fragment, "public void onDestroyView()").contains("snapcastBar"));

        String bar = code("browser/SnapcastClientSwitchBar.java");
        assertTrue("the switch goes through the one power API", bar.contains("SnapcastClientPowers.of("));
        assertFalse("never straight to the service", bar.contains("SnapcastClientService."));
        assertTrue("the gear opens the Snapcast section of the settings", bar.contains("SettingsActivity.snapcastClientIntent("));
        assertTrue("a refusal is shown with its reason", bar.contains("SnapcastClientPowerTexts."));
        assertTrue("showing a stopped state clears 'unseen'", bar.contains("markEndingSeen()"));
        assertTrue("no polling without the page", bar.contains("removeCallbacks("));
        assertTrue("shown only when the option is on and a server is set", bar.contains(".offered()"));
    }

    // ---- the settings: the checkbox, and the gear's target ----------------------------------------------------------

    @Test
    public void theCheckboxIsInTheSnapcastCategoryBetweenTheOptionAndTheTestScreen() throws Exception {
        String xml = text("res/xml/preference.xml");
        int category = xml.indexOf("@string/settings_snapcast_category");
        String block = xml.substring(category, xml.indexOf("</PreferenceCategory>", category));

        assertInOrder(block, "@string/settings_snapcast_client_spike\"", "@string/" + KEY + "\"", "@string/settings_snapcast_client_spike_open\"");
        Matcher entry = Pattern.compile("<CheckBoxPreference[^>]*?android:key=\"@string/" + KEY + "\"[^>]*?/>", Pattern.DOTALL).matcher(block);
        assertTrue(entry.find());
        assertTrue(entry.group().contains("android:defaultValue=\"false\""));
        assertTrue(entry.group().contains("android:summaryOn=\"@string/settings_snapcast_client_switched_on_summary_on\""));
        assertTrue(entry.group().contains("android:summaryOff=\"@string/settings_snapcast_client_switched_on_summary_off\""));
        assertTrue(entry.group().contains("android:title=\"@string/settings_snapcast_client_switched_on_title\""));
    }

    @Test
    public void theKeyResourceIsTheKeyOfTheCode() throws Exception {
        Matcher key = Pattern.compile("<string name=\"" + KEY + "\" translatable=\"false\">([^<]+)</string>")
                .matcher(text("res/values/setting_strings.xml"));
        assertTrue(key.find());
        assertEquals("the key resource is its own name, as the other settings keys are", KEY, key.group(1));
    }

    @Test
    public void theSettingsScreenBindsTheCheckboxToThePowerApiAndScrollsToTheSectionOnRequest() throws Exception {
        String fragment = code("settings/SettingsFragment.java");
        String setUp = body(fragment, "private void setUpSnapcastClientSpike()");
        assertTrue("the checkbox starts and stops through the power API", setUp.contains("SnapcastClientPowers.of("));
        assertTrue(setUp.contains(".setOn("));
        assertTrue("a refusal is shown with its reason", setUp.contains("SnapcastClientPowerTexts."));
        assertTrue("shown only while the option is on, like the test screen entry", setUp.contains("setVisible("));
        assertTrue("the listen test entry stays", setUp.contains("SnapcastClientSpikeDialog.show("));

        assertTrue(body(fragment, "public void onCreatePreferences(").contains("scrollToRequestedSection()"));
        String scroll = body(fragment, "private void scrollToRequestedSection()");
        assertTrue("the requested section is validated", scroll.contains("SnapcastSettingsTarget.scrollKey("));
        assertTrue(scroll.contains("scrollToPreference("));

        String activity = code("settings/SettingsActivity.java");
        assertTrue(activity.contains("public static android.content.Intent snapcastClientIntent(")
                || activity.contains("public static Intent snapcastClientIntent("));
        assertTrue(activity.contains("SnapcastSettingsTarget.EXTRA_SCROLL_TO"));
    }

    // ---- the listen test dialog: Start and Stop are the same state ---------------------------------------------------

    @Test
    public void theDialogStartsAndStopsThroughThePowerApiAndShowsTheSameState() throws Exception {
        String dialog = code("settings/SnapcastClientSpikeDialog.java");
        assertFalse("no direct start of the service", dialog.contains("SnapcastClientService.start("));
        assertFalse("no direct stop of the service", dialog.contains("SnapcastClientService.stop("));
        assertTrue(dialog.contains("SnapcastClientPowers.of("));
        assertTrue(dialog.contains(".setOn(true)"));
        assertTrue(dialog.contains(".setOn(false)"));
        assertTrue("the same state text as the switch", dialog.contains("SnapcastClientPowerTexts.stateText("));
        assertTrue(dialog.contains("markEndingSeen()"));
        // the value that is applied is saved before the start reads it back
        assertInOrder(body(dialog, "private void onStart()"), "KEY_LAST_HOST", ".setOn(true)");
    }

    // ---- the startup route -------------------------------------------------------------------------------------------

    @Test
    public void theApplicationRestoresInTheStartupRouteAfterTheServerServiceAndWhenTheUserStarts() throws Exception {
        String yaacc = code("Yaacc.java");
        String create = body(yaacc, "public void onCreate()");
        assertInOrder(create, "startService(new Intent(this, YaaccUpnpServerService.class))", "upnpClient = new UpnpClient(this)",
                "SnapcastClientPowers.restore(", "BackgroundIdleTimer.processStarted(this)");
        assertTrue("a start right after an exit is held back", create.contains("Point.PROCESS_START, startDeferredAfterExit)"));

        String started = body(yaacc, "public void userStarted()");
        assertInOrder(started, "if (exiting.get())", "ExitRestartGuards.of(this).clearOnUserStart()", "if (startDeferredAfterExit)",
                "SnapcastClientPowers.restore(");
        assertTrue(started.contains("Point.SCREEN_START, false)"));
    }

    @Test
    public void nothingButTheUsersSwitchClearsTheState() throws Exception {
        // the application object (exit, timers) never touches the state; only the power API writes it, and only setOn(false) clears it
        assertFalse(code("Yaacc.java").contains("KEY_SWITCHED_ON"));
        assertFalse(code("browser/BackgroundIdleTimer.java").contains("KEY_SWITCHED_ON"));
        assertFalse(code("multiroom/client/SnapcastClientService.java").contains("KEY_SWITCHED_ON"));
        String power = code("multiroom/client/SnapcastClientPower.java");
        assertEquals("one place writes the state", 1, count(power, "store.writeSwitchedOn("));
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            n++;
        }
        return n;
    }

    @Test
    public void theServiceWritesTheEndingAsUnseenAndTellsARefusedBackgroundStartApart() throws Exception {
        String service = code("multiroom/client/SnapcastClientService.java");
        assertTrue("every ending is unseen until it was shown", body(service, "private void recordEnding(").contains("KEY_LAST_ENDING_SEEN"));
        assertTrue(service.contains("SnapcastClientPower.isBackgroundStartRefusal("));
        assertTrue(service.contains("Ending.START_NOT_ALLOWED"));
        // no address, no host in any log line of the new classes
        for (String file : new String[]{"multiroom/client/SnapcastClientPower.java", "multiroom/client/SnapcastClientPowers.java",
                "browser/SnapcastClientSwitchBar.java"}) {
            String source = code(file);
            assertFalse(file + " logs no exception message", Pattern.compile("YaaccLogger\\.\\w+\\([^;]*getMessage\\(\\)").matcher(source).find());
        }
    }

    @Test
    public void theManifestKeepsItsServiceAndGetsNoNewComponentOrPermissionForTheSwitch() throws Exception {
        String manifest = text("AndroidManifest.xml");
        assertFalse("no new component of the switch", manifest.contains("SnapcastClientSwitch"));
        assertFalse(manifest.contains("SnapcastClientPower"));
        // the one service is still not exported
        int at = manifest.indexOf(".multiroom.client.SnapcastClientService");
        String declaration = manifest.substring(manifest.lastIndexOf("<service", at), manifest.indexOf("/>", at));
        assertTrue(declaration.contains("android:exported=\"false\""));
    }

    // ---- the texts, in every locale ----------------------------------------------------------------------------------

    @Test
    public void everyLocaleHasTheNewTextsWithTheSameArguments() throws Exception {
        String[] strings = {"snapcast_client_switch_label", "snapcast_client_switch_settings", "snapcast_client_state_off",
                "snapcast_client_state_stopped", "snapcast_client_state_line", "snapcast_client_refused_option_off",
                "snapcast_client_refused_no_server", "snapcast_client_refused_bad_server", "snapcast_client_refused_not_allowed"};
        String[] settings = {"settings_snapcast_client_switched_on_title", "settings_snapcast_client_switched_on_summary_on",
                "settings_snapcast_client_switched_on_summary_off"};
        for (String locale : LOCALES) {
            String xml = text("res/" + locale + "/strings.xml");
            for (String name : strings) {
                assertTrue(locale + " " + name, Pattern.compile("<string name=\"" + name + "\">[^<]+</string>").matcher(xml).find());
            }
            String settingsXml = text("res/" + locale + "/setting_strings.xml");
            for (String name : settings) {
                assertTrue(locale + " " + name, Pattern.compile("<string name=\"" + name + "\">[^<]+</string>").matcher(settingsXml).find());
            }
        }
        Matcher line = Pattern.compile("<string name=\"snapcast_client_state_line\">([^<]+)</string>").matcher(text("res/values/strings.xml"));
        assertTrue(line.find());
        assertTrue("the line has the state as its one argument", line.group(1).contains("%1$s"));
    }
}
