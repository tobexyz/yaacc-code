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
package de.yaacc.browser;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Pattern;

/**
 * The server list button of the content list header (new layout): both orientations declare it,
 * hidden until {@link ContentListFragment} shows it, labelled in every locale. Plain JVM: the
 * resource files are read from the module directory Gradle runs unit tests in.
 */
public class ServerListButtonResourcesTest {

    private static String read(String relativePath) throws Exception {
        File file = new File(relativePath);
        assertTrue("missing " + file.getAbsolutePath(), file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void bothOrientationsHaveTheButtonHiddenAndLabelled() throws Exception {
        for (String layout : new String[]{"src/main/res/layout/fragment_content_list.xml",
                "src/main/res/layout-land/fragment_content_list.xml"}) {
            String xml = read(layout);
            assertTrue(layout, Pattern.compile(
                    "<ImageButton[^>]*@\\+id/contentListServersButton\"[^>]*"
                            + "android:contentDescription=\"@string/server_list_button\"[^>]*"
                            + "android:visibility=\"gone\"", Pattern.DOTALL).matcher(xml).find());
        }
    }

    @Test
    public void everyLocaleLabelsTheButton() throws Exception {
        for (String dir : new String[]{"values", "values-de", "values-es", "values-fr", "values-nl", "values-pt",
                "values-zh"}) {
            String xml = read("src/main/res/" + dir + "/strings.xml");
            assertTrue(dir, Pattern.compile("<string name=\"server_list_button\">[^<]+</string>").matcher(xml).find());
        }
    }
}
