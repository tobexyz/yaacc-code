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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Layouts that must also work on a small phone (issues #324 and #334), checked on the XML: no emulator is needed to see that
 * the bottom lines of the classic server tab keep room for their texts at 320dp (the width of a small phone, also what a
 * bigger font leaves) and that the screen with the shared folders scrolls as a whole. Plain JVM: the layout files are read from
 * the module directory.
 */
public class SmallScreenLayoutResourcesTest {

    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    /** The narrowest phone width in dp that the layouts are checked at. */
    private static final int SMALLEST_WIDTH_DP = 320;
    /** What a row must leave for its label (about "UPnP server" in the medium text size). */
    private static final int LABEL_DP = 90;
    /** A Material switch without text, its measured width. */
    private static final int SWITCH_DP = 52;

    private static Element root(String path) throws Exception {
        File file = new File("src/main/res/" + path);
        assertTrue("missing " + file.getAbsolutePath(), file.isFile());
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true); // the attributes are looked up by their namespace
        Document document = factory.newDocumentBuilder().parse(file);
        Element element = document.getDocumentElement();
        assertNotNull(element);
        return element;
    }

    private static String attr(Element element, String name) {
        return element.getAttributeNS(ANDROID, name);
    }

    private static List<Element> children(Element parent) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i).getNodeType() == Node.ELEMENT_NODE) {
                result.add((Element) nodes.item(i));
            }
        }
        return result;
    }

    private static Element byId(Element parent, String id) {
        for (Element element : all(parent)) {
            if (("@+id/" + id).equals(attr(element, "id"))) {
                return element;
            }
        }
        return null;
    }

    private static List<Element> all(Element parent) {
        List<Element> result = new ArrayList<>();
        for (Element child : children(parent)) {
            result.add(child);
            result.addAll(all(child));
        }
        return result;
    }

    private static int dp(String value) {
        return value.endsWith("dp") ? Integer.parseInt(value.substring(0, value.length() - 2)) : 0;
    }

    /** The width a child takes whatever the text is: fixed dp, the margins, and a switch. */
    private static int fixedWidth(Element child) {
        String width = attr(child, "layout_width");
        int fixed = dp(width) + dp(attr(child, "layout_marginStart")) + dp(attr(child, "layout_marginEnd"));
        if (child.getTagName().endsWith("SwitchMaterial")) {
            fixed += SWITCH_DP;
        }
        return fixed;
    }

    @Test
    public void theBottomLinesOfTheClassicServerTabFitAPhoneOf320dp() throws Exception {
        Element layout = root("layout/fragment_server_list.xml");
        for (String label : new String[]{"serverListLocalServerLabel", "serverListShutdownTimerLabel"}) {
            Element text = byId(layout, label);
            assertNotNull(label, text);
            Element row = (Element) text.getParentNode();
            assertEquals(label + " is in a horizontal line", "horizontal", attr(row, "orientation"));
            int padding = dp(attr(row, "paddingStart")) + dp(attr(row, "paddingEnd"));
            int fixed = 0;
            boolean flexible = false;
            for (Element child : children(row)) {
                fixed += fixedWidth(child);
                flexible |= "0dp".equals(attr(child, "layout_width")) && !attr(child, "layout_weight").isEmpty();
            }
            assertTrue(label + "'s line has a part that gives way (weight, 0dp)", flexible);
            if (label.equals("serverListLocalServerLabel")) {
                assertTrue("the server line leaves " + LABEL_DP + "dp for its label at " + SMALLEST_WIDTH_DP + "dp, fixed parts: "
                        + fixed + "dp plus " + padding + "dp padding", SMALLEST_WIDTH_DP - padding - fixed >= LABEL_DP);
                assertEquals("end", attr(text, "ellipsize"));
            }
        }
    }

    @Test
    public void theRefreshButtonIsAloneOnTheLastLineOfTheClassicServerTab() throws Exception {
        Element layout = root("layout/fragment_server_list.xml");
        Element refresh = byId(layout, "serverListRefreshButton");
        assertNotNull(refresh);
        assertEquals("a line of its own, not inside the line of the server", layout, refresh.getParentNode());
        List<Element> lines = children(layout);
        assertEquals("the refresh button is the last line", refresh, lines.get(lines.size() - 1));
        // the line above is the server line: the status icons are in it, the button is not
        Element proxyLine = (Element) byId(layout, "serverListProxyEnabled").getParentNode();
        assertEquals(proxyLine, lines.get(lines.size() - 2));
        assertEquals("the timer line comes before the server line", lines.get(lines.size() - 3),
                byId(layout, "serverListShutdownTimerLabel").getParentNode());
    }

    @Test
    public void theScreenWithTheSharedFoldersScrollsAsAWhole() throws Exception {
        for (String path : new String[]{"layout/activity_yaacc_upnp_server_control.xml",
                "layout-land/activity_yaacc_upnp_server_control.xml"}) {
            Element layout = root(path);
            assertEquals(path + " scrolls", "ScrollView", layout.getTagName());
            assertEquals(path, "true", attr(layout, "fillViewport"));
            Element folders = byId(layout, "folders_recycler_view");
            assertNotNull(path, folders);
            assertEquals(path + ": the list takes the height of its rows, not what is left", "wrap_content", attr(folders, "layout_height"));
            assertEquals(path + ": the screen scrolls, not the list", "false", attr(folders, "nestedScrollingEnabled"));
            assertFalse(path + ": nothing pins the list to the bottom of the screen", "true".equals(attr(folders, "layout_alignParentBottom")));
            for (String id : new String[]{"serverOnOff", "providerEnabled", "receiverEnabled", "proxyEnabled", "filterEnabled",
                    "mediaStoreRescan", "sharedFolders", "sharedFoldersReset", "sharedFoldersAddSaf", "clearSafCache"}) {
                assertNotNull(path + " still has " + id, byId(layout, id));
            }
        }
    }
}
