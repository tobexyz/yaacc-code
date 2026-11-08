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
package de.yaacc.openhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The small XML reader for the texts of a device: nesting is limited before a parser sees the text. */
public class OhXmlTest {

    private static String nested(int levels) {
        StringBuilder xml = new StringBuilder();
        for (int i = 0; i < levels; i++) {
            xml.append("<a>");
        }
        for (int i = 0; i < levels; i++) {
            xml.append("</a>");
        }
        return xml.toString();
    }

    @Test
    public void textsAsDeepAsTheLimitAreRead() {
        assertNotNull(OhXml.parse(nested(OhXml.MAX_DEPTH)));
    }

    @Test
    public void textsDeeperThanTheLimitAreRefusedWithoutBuildingADocument() {
        assertNull(OhXml.parse(nested(OhXml.MAX_DEPTH + 1)));
        assertNull(OhXml.parse(nested(100_000)));
    }

    @Test
    public void commentsCdataAndSelfClosingElementsDoNotCountAsLevels() {
        StringBuilder xml = new StringBuilder("<r>");
        for (int i = 0; i < 200; i++) {
            xml.append("<!-- <a><a><a> --><x/><![CDATA[<b><b><b>]]><?pi <c> ?>");
        }
        xml.append("</r>");
        assertFalse(OhXml.tooDeep(xml.toString(), 3));
        assertNotNull(OhXml.parse(xml.toString()));
    }

    @Test
    public void aGreaterThanInsideAnAttributeDoesNotEndTheTag() {
        assertFalse(OhXml.tooDeep("<a b=\"1>2\"><c/></a>", 2));
        assertTrue(OhXml.tooDeep("<a b=\"1>2\"><c><d></d></c></a>", 2));
    }

    @Test
    public void onlyTheOwnTextOfAnElementIsRead() {
        org.w3c.dom.Document document = OhXml.parse("<r><n>one<i>inner</i>two</n></r>");
        assertEquals("onetwo", OhXml.text(document.getDocumentElement(), "n"));
    }
}
