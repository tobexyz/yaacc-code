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
package de.yaacc.browser;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Review W1 of Group 11B: the hardware volume keys reached the Snapcast group only in the classic layout, because the default
 * layout's activity ({@code BrowserActivity}) had no key methods. This guard finds <b>every activity that can show receiver rows</b>
 * and fails when one of them does not hand the volume keys to {@link VolumeKeyHosts} (down and up), so a future layout can not
 * lose the keys silently. Plain JVM: the sources are read from the module directory, comments are stripped, and the hosts are
 * found by code references, starting at the class that registers with {@code VolumeKeyHosts}:
 * <ol>
 *   <li>the classes of the browser package that refer (in code) to {@code ReceiverVolumeLiveUpdater}, then to those, and so on
 *   (adapters, fragments, controllers); activities are not followed (they are the end of the chain);</li>
 *   <li>every activity of the app that refers to one of these classes must override {@code onKeyDown} and {@code onKeyUp} and call
 *   {@code VolumeKeyHosts.onKeyDown} and {@code VolumeKeyHosts.onKeyUp}.</li>
 * </ol>
 */
public class VolumeKeyRoutingGuardTest {

    private static final File SOURCES = new File("src/main/java");
    private static final Pattern ACTIVITY = Pattern.compile("\\bclass\\s+\\w+\\s+extends\\s+\\w*Activity\\b");

    private static String stripComments(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    private static Map<String, String> read(File dir, boolean recursive) throws Exception {
        Map<String, String> files = new HashMap<>();
        File[] list = dir.listFiles();
        assertTrue("missing " + dir.getAbsolutePath(), list != null);
        for (File file : list) {
            if (file.isDirectory() && recursive) {
                files.putAll(read(file, true));
            } else if (file.getName().endsWith(".java")) {
                files.put(file.getName().replace(".java", ""),
                        stripComments(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)));
            }
        }
        return files;
    }

    private static boolean refersTo(String code, String name) {
        return Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(code).find();
    }

    /** The activities of the app that can show receiver rows, found as described in the class comment. */
    private static Set<String> hostingActivities(Map<String, String> all) throws Exception {
        Map<String, String> browser = read(new File(SOURCES, "de/yaacc/browser"), false);
        Set<String> chain = new HashSet<>();
        chain.add("ReceiverVolumeLiveUpdater");
        boolean grown = true;
        while (grown) {
            grown = false;
            for (Map.Entry<String, String> file : browser.entrySet()) {
                if (chain.contains(file.getKey()) || ACTIVITY.matcher(file.getValue()).find()) {
                    continue;
                }
                for (String member : new ArrayList<>(chain)) {
                    if (refersTo(file.getValue(), member)) {
                        chain.add(file.getKey());
                        grown = true;
                        break;
                    }
                }
            }
        }
        Set<String> activities = new HashSet<>();
        for (Map.Entry<String, String> file : all.entrySet()) {
            if (!ACTIVITY.matcher(file.getValue()).find()) {
                continue;
            }
            for (String member : chain) {
                if (refersTo(file.getValue(), member)) {
                    activities.add(file.getKey());
                    break;
                }
            }
        }
        return activities;
    }

    @Test
    public void theGuardFindsTheTwoLayoutsActivities() throws Exception {
        Set<String> hosts = hostingActivities(read(SOURCES, true));

        assertTrue("the default layout: " + hosts, hosts.contains("BrowserActivity"));
        assertTrue("the classic layout: " + hosts, hosts.contains("TabBrowserActivity"));
    }

    @Test
    public void everyActivityThatCanShowReceiverRowsHandsTheVolumeKeysToTheHosts() throws Exception {
        Map<String, String> all = read(SOURCES, true);

        for (String activity : hostingActivities(all)) {
            String code = all.get(activity);
            assertTrue(activity + " must override onKeyDown", code.contains("boolean onKeyDown(int keyCode, KeyEvent event)"));
            assertTrue(activity + " must call VolumeKeyHosts.onKeyDown", code.contains("VolumeKeyHosts.onKeyDown("));
            assertTrue(activity + " must override onKeyUp", code.contains("boolean onKeyUp(int keyCode, KeyEvent event)"));
            assertTrue(activity + " must call VolumeKeyHosts.onKeyUp", code.contains("VolumeKeyHosts.onKeyUp("));
        }
    }

    @Test
    public void theSheetAsksOnlyItsOwnHostAndNeverInPickMode() throws Exception {
        String sheet = read(new File(SOURCES, "de/yaacc/browser"), false).get("DeviceSheetFragment");

        assertTrue("its window gets the keys itself", sheet.contains("VolumeKeyHosts.onKeyDown(keyCode, true)"));
        assertTrue("the picker is modal: the keys are the system's", sheet.contains("isPickMode()"));
        assertFalse(sheet.contains("VolumeKeyHosts.onKeyDown(keyCode)"));
    }
}
