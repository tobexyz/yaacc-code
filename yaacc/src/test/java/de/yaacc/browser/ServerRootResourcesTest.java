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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Pattern;

/**
 * The top-level page of the new layout (servers and receivers in one list): what its layouts declare and that the new texts exist in
 * every locale. Plain JVM: the resource files are read from the module directory Gradle runs unit tests in.
 */
public class ServerRootResourcesTest {

    private static final String[] LOCALES = {"values", "values-de", "values-es", "values-fr", "values-nl", "values-pt", "values-zh"};

    private static String read(String relativePath) throws Exception {
        File file = new File(relativePath);
        assertTrue("missing " + file.getAbsolutePath(), file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void theRootLayoutHasOneListAndTheFooterButNoFixedHeader() throws Exception {
        String xml = read("src/main/res/layout/fragment_server_root.xml");

        assertTrue(xml.contains("@+id/serverList"));
        assertTrue(xml.contains("@+id/serverListLocalServerEnabled"));
        assertTrue(xml.contains("@+id/serverListLocalServerSettings"));
        assertTrue("a line above the footer (it was the line of the bottom stack)", xml.contains("@+id/serverListFooterSeparator"));
        assertFalse("the header is a row of the list now", xml.contains("@+id/serverRootHeader"));
        assertFalse("the refresh button is in the headers of the list", xml.contains("@+id/serverListRefreshButton"));
    }

    @Test
    public void theClassicLayoutIsUntouched() throws Exception {
        String xml = read("src/main/res/layout/fragment_server_list.xml");

        assertTrue(xml.contains("@+id/serverList"));
        assertTrue(xml.contains("@+id/serverListRefreshButton"));
    }

    @Test
    public void theSectionHeaderHasIconLabelAndRefresh() throws Exception {
        String xml = read("src/main/res/layout/server_list_section_header.xml");

        assertTrue(xml.contains("@+id/sectionHeaderIcon"));
        assertTrue(xml.contains("@+id/sectionHeaderLabel"));
        assertTrue(xml.contains("@+id/sectionHeaderRefresh"));
        assertTrue("a line above the section", xml.contains("@+id/sectionHeaderDivider"));
        assertTrue("the refresh button is labelled", Pattern.compile("@\\+id/sectionHeaderRefresh\"[^>]*android:contentDescription=",
                Pattern.DOTALL).matcher(xml).find());
        assertTrue("the icon is decorative, the label says it", xml.contains("importantForAccessibility=\"no\""));
    }

    @Test
    public void theHintLayoutHasItsText() throws Exception {
        assertTrue(read("src/main/res/layout/server_list_section_hint.xml").contains("@+id/sectionHintText"));
    }

    @Test
    public void everyLocaleHasTheNewTexts() throws Exception {
        for (String dir : LOCALES) {
            String xml = read("src/main/res/" + dir + "/strings.xml");
            for (String name : new String[]{"browser_receivers_header", "browser_receivers_none", "browser_players_header"}) {
                assertTrue(dir + " " + name, Pattern.compile("<string name=\"" + name + "\">[^<]+</string>").matcher(xml).find());
            }
        }
    }

    @Test
    public void theReceiverBarAndTheMiniPlayerShareOneContainerThatTheActivityCanHide() throws Exception {
        String xml = read("src/main/res/layout/activity_browser.xml");

        int stack = xml.indexOf("@+id/browserBottomStack");
        assertTrue("the bottom stack has a container", stack > 0);
        int separator = xml.indexOf("@+id/browserBottomSeparator");
        int miniPlayer = xml.indexOf("@layout/mini_player_bar");
        int receiverBar = xml.indexOf("@+id/browserReceiverBar");
        assertTrue("separator, mini-player, receiver bar come after the container, in this order",
                stack < separator && separator < miniPlayer && miniPlayer < receiverBar);
        assertTrue("the content container is outside the stack", xml.indexOf("@+id/browserContainer") < stack);
    }
}
