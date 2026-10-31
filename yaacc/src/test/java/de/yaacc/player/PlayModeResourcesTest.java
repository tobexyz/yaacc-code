/*
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
package de.yaacc.player;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Pattern;

/**
 * The repeat and shuffle controls: their texts exist in every locale and every player screen declares the
 * buttons (both orientations), styled and focusable like the other icon buttons. Plain JVM: the resource
 * files are read from the module directory Gradle runs unit tests in.
 */
public class PlayModeResourcesTest {

    private static final String[] LOCALES = {"values", "values-de", "values-es", "values-fr", "values-nl",
            "values-pt", "values-zh"};
    private static final String[] TEXTS = {"repeat_off", "repeat_all", "repeat_one", "shuffle_off", "shuffle_on"};

    private static String read(String relativePath) throws Exception {
        File file = new File(relativePath);
        assertTrue("missing " + file.getAbsolutePath(), file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void everyLocaleHasEveryText() throws Exception {
        for (String dir : LOCALES) {
            String xml = read("src/main/res/" + dir + "/strings.xml");
            for (String name : TEXTS) {
                assertTrue(dir + " " + name,
                        Pattern.compile("<string name=\"" + name + "\">[^<]+</string>").matcher(xml).find());
            }
        }
    }

    @Test
    public void theTextsOfALocaleDiffer() throws Exception {
        for (String dir : LOCALES) {
            String xml = read("src/main/res/" + dir + "/strings.xml");
            java.util.Set<String> values = new java.util.HashSet<>();
            for (String name : TEXTS) {
                java.util.regex.Matcher matcher = Pattern.compile("<string name=\"" + name + "\">([^<]+)</string>").matcher(xml);
                assertTrue(dir + " " + name, matcher.find());
                assertTrue(dir + " " + name + " is a duplicate", values.add(matcher.group(1)));
            }
        }
    }

    private static void assertButton(String layout, String id, String icon) throws Exception {
        String xml = read(layout);
        // one ImageButton element with the id, the button style, the focus ring, an icon and a gray tint
        java.util.regex.Matcher matcher = Pattern.compile("<ImageButton[^>]*@\\+id/" + id + "\"[^>]*/>", Pattern.DOTALL).matcher(xml);
        assertTrue(layout + " declares " + id, matcher.find());
        String element = matcher.group();
        assertTrue(layout + " " + id + " style", element.contains("style=\"@style/Widget.MaterialComponents.Button.UnelevatedButton\""));
        assertTrue(layout + " " + id + " focus ring", element.contains("android:foreground=\"@drawable/focus_ring\""));
        assertTrue(layout + " " + id + " icon", element.contains("app:srcCompat=\"@drawable/" + icon + "\""));
        assertTrue(layout + " " + id + " tint", element.contains("app:tint=\"?attr/colorControlNormal\""));
        assertTrue(layout + " " + id + " description", element.contains("android:contentDescription=\"@string/"));
    }

    @Test
    public void musicAndAvTransportScreensHaveBothButtonsInBothOrientations() throws Exception {
        for (String dir : new String[]{"layout", "layout-land"}) {
            assertButton("src/main/res/" + dir + "/activity_music_player.xml", "musicActivityControlRepeat", "ic_baseline_repeat_32");
            assertButton("src/main/res/" + dir + "/activity_music_player.xml", "musicActivityControlShuffle", "ic_baseline_shuffle_32");
            assertButton("src/main/res/" + dir + "/activity_avtransport_player.xml", "avtransportPlayerActivityControlRepeat", "ic_baseline_repeat_32");
            assertButton("src/main/res/" + dir + "/activity_avtransport_player.xml", "avtransportPlayerActivityControlShuffle", "ic_baseline_shuffle_32");
        }
    }

    @Test
    public void theMultiContentScreenHasBothButtons() throws Exception {
        String layout = "src/main/res/layout/activity_multi_content_player.xml";
        assertButton(layout, "multiContentPlayerActivityControlRepeat", "ic_baseline_repeat_32");
        assertButton(layout, "multiContentPlayerActivityControlShuffle", "ic_baseline_shuffle_32");
    }

    @Test
    public void theImageViewerMenuHasBothItems() throws Exception {
        String xml = read("src/main/res/menu/activity_image_viewer.xml");
        assertTrue(Pattern.compile("<item[^>]*@\\+id/menu_repeat\"[^>]*ic_baseline_repeat_32", Pattern.DOTALL).matcher(xml).find());
        assertTrue(Pattern.compile("<item[^>]*@\\+id/menu_shuffle\"[^>]*ic_baseline_shuffle_32", Pattern.DOTALL).matcher(xml).find());
    }

    @Test
    public void theIconsAreWhiteVectorsLikeTheOthers() throws Exception {
        for (String icon : new String[]{"ic_baseline_repeat_32", "ic_baseline_repeat_one_32", "ic_baseline_shuffle_32"}) {
            String xml = read("src/main/res/drawable/" + icon + ".xml");
            assertTrue(icon, xml.contains("android:fillColor=\"@android:color/white\""));
            assertTrue(icon, xml.contains("android:width=\"32dp\"") && xml.contains("android:height=\"32dp\""));
        }
    }
}
