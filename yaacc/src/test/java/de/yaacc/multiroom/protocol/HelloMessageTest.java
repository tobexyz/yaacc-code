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
package de.yaacc.multiroom.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** What the Hello tells the server (design: "Disclosure (Hello)") and how its text is made safe. */
public class HelloMessageTest {

    private static JSONObject json(HelloMessage hello) throws Exception {
        byte[] bytes = SnapcastMessageCodec.encode(hello, 1, 0, 0, 0, 0, 0);
        return new JSONObject(new String(bytes, 30, bytes.length - 30, StandardCharsets.UTF_8));
    }

    @Test
    public void theMacIsAFixedPlaceholderAndThereIsNoAuth() throws Exception {
        JSONObject json = json(HelloMessage.create("yaacc-abc", "pixel", "YAACC", "Android 14", "arm64-v8a", "1.2.3"));

        assertEquals("00:00:00:00:00:00", json.getString("MAC"));
        assertFalse(json.has("Auth"));
        assertEquals("00:00:00:00:00:00", HelloMessage.MAC_PLACEHOLDER);
    }

    @Test
    public void onlyTheDocumentedFieldsAreSent() throws Exception {
        JSONObject json = json(HelloMessage.create("yaacc-abc", "pixel", "YAACC", "Android 14", "arm64-v8a", "1.2.3"));

        Set<String> keys = new HashSet<>();
        for (Iterator<String> it = json.keys(); it.hasNext(); ) {
            keys.add(it.next());
        }
        assertEquals(new HashSet<>(Arrays.asList("Arch", "ClientName", "HostName", "ID", "Instance", "MAC", "OS",
                "SnapStreamProtocolVersion", "Version")), keys);
        assertEquals("yaacc-abc", json.getString("ID"));
        assertEquals("pixel", json.getString("HostName"));
        assertEquals("YAACC", json.getString("ClientName"));
        assertEquals("Android 14", json.getString("OS"));
        assertEquals("arm64-v8a", json.getString("Arch"));
        assertEquals("1.2.3", json.getString("Version"));
        assertEquals(1, json.getInt("Instance"));
        assertEquals(2, json.getInt("SnapStreamProtocolVersion"));
    }

    @Test
    public void controlAndBidiCharactersAreReplacedAndLongTextIsCut() throws Exception {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            longName.append('n');
        }

        JSONObject json = json(HelloMessage.create("id\n1", "ho‮st", longName.toString(), "O\u0000S", "a\tb", "v​1"));

        assertEquals("id?1", json.getString("ID"));
        assertEquals("ho?st", json.getString("HostName"));
        assertEquals(HelloMessage.MAX_FIELD_LENGTH, json.getString("ClientName").length());
        assertEquals("O?S", json.getString("OS"));
        assertEquals("a?b", json.getString("Arch"));
        assertEquals("v?1", json.getString("Version"));
    }

    @Test
    public void textThatLooksLikeJsonStaysInsideItsValue() throws Exception {
        String attack = "x\",\"Auth\":{\"scheme\":\"Basic\",\"param\":\"eDp5\"},\"y\":\"";

        JSONObject json = json(HelloMessage.create("id", attack, "YAACC", "Android", "arm", "1"));

        assertFalse(json.has("Auth"));
        assertFalse(json.has("y"));
        assertTrue(json.getString("HostName").startsWith("x\","));
    }

    @Test
    public void missingTextBecomesEmptyButTheIdIsRequired() throws Exception {
        JSONObject json = json(HelloMessage.create("id", null, null, null, null, null));

        assertEquals("", json.getString("HostName"));
        assertEquals("", json.getString("OS"));
        for (String bad : new String[]{null, "", "   ", "\n\n"}) {
            try {
                HelloMessage.create(bad, "h", "c", "o", "a", "v");
                fail("id " + bad);
            } catch (IllegalArgumentException expected) {
                // an anonymous client would be one client for everybody
            }
        }
    }
}
