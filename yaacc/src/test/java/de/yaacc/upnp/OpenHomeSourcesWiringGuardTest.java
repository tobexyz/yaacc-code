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
package de.yaacc.upnp;

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
 * Pins the wiring of the Snapcast groups as sources of the OpenHome renderer (task 12.5) that only the device could show otherwise,
 * in the style of {@link RendererOutputWiringGuardTest}: the list follows the output setting and the groups and is stopped with the
 * renderer, the renderer's source choice writes exactly the keys of the settings screen in one step, the glue never makes the hub
 * or a connection, nothing is logged, and the texts exist in every locale. Plain JVM: the sources are read (comments stripped).
 */
public class OpenHomeSourcesWiringGuardTest {

    private static final String[] LOCALES = {"values", "values-de", "values-es", "values-fr", "values-nl", "values-pt", "values-zh"};

    private static String raw(String relativePath) throws Exception {
        File file = new File(relativePath);
        assertTrue("missing " + file.getAbsolutePath(), file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static String source(String relativePath) throws Exception {
        return raw("src/main/java/de/yaacc/" + relativePath).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    private static String methodBody(String source, String signatureStart) {
        int at = source.indexOf(signatureStart);
        assertTrue("no method " + signatureStart, at >= 0);
        int open = source.indexOf('{', at);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            if (source.charAt(i) == '{') {
                depth++;
            } else if (source.charAt(i) == '}' && --depth == 0) {
                return source.substring(open, i + 1);
            }
        }
        throw new AssertionError("unbalanced " + signatureStart);
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    @Test
    public void theServiceStartsTheListWithTheRendererAndStopsItWithIt() throws Exception {
        String service = source("upnp/server/YaaccUpnpServerService.java");

        assertTrue(methodBody(service, "private List<LocalService<?>> createOpenHomeServices()")
                .contains("AndroidOutputSources.start(getApplicationContext(), openHomeRenderer.core())"));
        assertEquals("closed on the failure path and on the shutdown, before the renderer ends", 3, count(service, "closeOutputSources()"));
        assertTrue(methodBody(service, "private void closeOutputSources()").contains("outputSources.close()"));
    }

    @Test
    public void theChoiceWritesOnlyTheKeysOfTheSettingsScreenInOneStep() throws Exception {
        String provider = source("upnp/server/openhome/RendererOutputSources.java");
        String glue = source("upnp/server/openhome/AndroidOutputSources.java");

        Matcher keys = Pattern.compile("values\\.put\\(([^,]+),").matcher(provider);
        int found = 0;
        while (keys.find()) {
            String key = keys.group(1).trim();
            assertTrue(key, key.equals("SnapcastRendererOutput.KEY_OUTPUT") || key.equals("SnapcastRendererOutput.KEY_GROUP"));
            found++;
        }
        assertEquals("the device case and the group case (two keys)", 3, found);
        assertEquals("one write per choice", 1, count(provider, "writer.write("));
        assertEquals("one editor", 1, count(glue, "preferences.edit()"));
        assertEquals("one apply: the listeners read both values together", 1, count(glue, "editor.apply()"));
        assertFalse(glue.contains("commit()"));
    }

    @Test
    public void theListFollowsTheSettingAndTheGroupsAndTheListenersAreKept() throws Exception {
        String glue = source("upnp/server/openhome/AndroidOutputSources.java");

        assertTrue(glue.contains("SharedPreferences.OnSharedPreferenceChangeListener preferenceListener ="));
        assertTrue(methodBody(glue, "private void begin()").contains("registerOnSharedPreferenceChangeListener(preferenceListener)"));
        assertTrue(methodBody(glue, "public void close()").contains("unregisterOnSharedPreferenceChangeListener(preferenceListener)"));
        assertTrue(methodBody(glue, "public void close()").contains("removeChangeListener(groupsListener)"));
        assertTrue(methodBody(glue, "public void close()").contains("forgetWhenCreated(hubCreated)"));
        assertTrue(methodBody(glue, "private void begin()").contains("SnapcastHub.whenCreated(hubCreated)"));
        assertTrue(glue.contains("KEY_OUTPUT.equals(key) || SnapcastRendererOutput.KEY_GROUP.equals(key)"));
        assertTrue("a burst of changes is one read", methodBody(glue, "private void scheduleRefresh()").contains("refreshQueued.compareAndSet(false, true)"));
    }

    @Test
    public void theGlueNeverMakesTheHubOrAConnection() throws Exception {
        String glue = source("upnp/server/openhome/AndroidOutputSources.java");

        assertFalse("the hub is made by others; the list shows what it knows", glue.contains("getShared("));
        for (String call : new String[]{"wake(", "wakeServer(", "pageStarted(", "retain(", "getLink(", "newFeedController("}) {
            assertFalse(call, glue.contains(call));
        }
        assertTrue(source("multiroom/SnapcastHub.java").contains("public static synchronized void whenCreated("));
    }

    @Test
    public void theCoreAsksTheProviderOnlyForAChoiceAndReadsTheListAgainAfterwards() throws Exception {
        String core = source("upnp/server/openhome/OhRendererCore.java");
        String choose = methodBody(core, "private void chooseSource(");

        assertEquals("one place chooses", 1, count(core, "provider.select("));
        assertTrue(choose.indexOf("provider.select(") < choose.indexOf("refreshSources()"));
        assertTrue("a refusal is an invalid argument", choose.indexOf("refreshSources()") < choose.lastIndexOf("OhError.invalid(\"source\")"));
        for (String action : new String[]{"\"SetSourceIndex\"", "\"SetSourceIndexByName\"", "\"SetSourceBySystemName\""}) {
            int at = core.indexOf("h.put(" + action);
            assertTrue(action, at >= 0 && core.substring(at, at + 160).contains("chooseSource("));
        }
        assertFalse("standby is not touched by a source", methodBody(core, "private void chooseSource(").contains("Standby"));
    }

    @Test
    public void nothingOfTheSourceListIsLogged() throws Exception {
        for (String file : new String[]{"upnp/server/openhome/RendererOutputSources.java", "upnp/server/openhome/AndroidOutputSources.java",
                "multiroom/SnapcastRendererSources.java", "upnp/server/openhome/OhSourceProvider.java"}) {
            String text = source(file);
            assertFalse(file, text.contains("YaaccLogger") || text.contains("android.util.Log") || text.contains("System.out"));
        }
    }

    @Test
    public void theTextsExistInEveryLocale() throws Exception {
        for (String locale : LOCALES) {
            String xml = raw("src/main/res/" + locale + "/strings.xml");
            for (String name : new String[]{"renderer_source_unavailable", "renderer_source_error_group_gone", "renderer_source_error_not_saved"}) {
                assertTrue(locale + " lacks " + name, Pattern.compile("<string name=\"" + name + "\">([^<]+)</string>").matcher(xml).find());
            }
        }
    }

    @Test
    public void theSettingsScreenFollowsAChangeFromAControllerWhileItIsShown() throws Exception {
        String fragment = source("settings/SettingsFragment.java");

        assertTrue("kept in a field: the preferences hold their listeners weakly",
                fragment.contains("SharedPreferences.OnSharedPreferenceChangeListener rendererOutputListener ="));
        assertTrue(methodBody(fragment, "public void onStart()").contains("registerOnSharedPreferenceChangeListener(rendererOutputListener)"));
        assertTrue(methodBody(fragment, "public void onStop()").contains("unregisterOnSharedPreferenceChangeListener(rendererOutputListener)"));
        String shown = methodBody(fragment, "private void showRendererOutputAsStored()");
        assertTrue(shown.contains("updateRendererGroupSummary(group)") && shown.contains("group.setVisible("));
        assertFalse("it only shows, it never writes a preference", shown.contains(".edit()") || shown.contains("putString"));
    }
}
