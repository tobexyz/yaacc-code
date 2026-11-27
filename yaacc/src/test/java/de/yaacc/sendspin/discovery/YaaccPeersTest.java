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
package de.yaacc.sendspin.discovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import de.yaacc.sendspin.hub.Endpoint;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One YAACC phone announces a player and a server: the {@code yaacc} id in both TXT records says they are one phone (K14). */
public class YaaccPeersTest {

    private static final String ID = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopq"; // 43 characters of a key id

    private static Map<String, byte[]> txt(String key, String value) {
        Map<String, byte[]> attributes = new HashMap<>();
        attributes.put("path", "/sendspin".getBytes(StandardCharsets.UTF_8));
        if (key != null) {
            attributes.put(key, value.getBytes(StandardCharsets.UTF_8));
        }
        return attributes;
    }

    @Test
    public void theTxtRecordCarriesTheIdOfThePhone() {
        assertEquals(ID, SendspinServiceTxt.parse(txt("yaacc", ID)).yaacc());
        assertNull("none announced", SendspinServiceTxt.parse(txt(null, null)).yaacc());
    }

    @Test
    public void anIdThatIsNotAKeyIdIsIgnoredNotTrusted() {
        assertNull(SendspinServiceTxt.parse(txt("yaacc", "has spaces and ä")).yaacc());
        assertNull(SendspinServiceTxt.parse(txt("yaacc", "")).yaacc());
        StringBuilder longId = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longId.append('a');
        }
        assertNull(SendspinServiceTxt.parse(txt("yaacc", longId.toString())).yaacc());
    }

    private static SendspinDirectory.Entry entry(String host, int port, String name, String yaacc) {
        return new SendspinDirectory.Entry(new Endpoint(host, port, "/sendspin"), name, yaacc);
    }

    @Test
    public void theServerOfAPlayerIsTheOneWithTheSameId() {
        SendspinDirectory.Entry player = entry("192.168.0.221", 8928, "Nokia", ID);
        SendspinDirectory.Entry server = entry("192.168.0.221", 8927, "Nokia", ID);
        SendspinDirectory.Entry other = entry("192.168.0.221", 8095, "Music Assistant", null);
        List<SendspinDirectory.Entry> servers = Arrays.asList(other, server);
        assertEquals(server.endpoint(), YaaccPeers.serverOf(player, servers));
        assertTrue(YaaccPeers.isMergedServer(server, Collections.singletonList(player)));
        assertFalse("a server that is not a YAACC phone's stays a card", YaaccPeers.isMergedServer(other, Collections.singletonList(player)));
    }

    @Test
    public void sameAddressWithoutTheIdIsNoMatch() {
        SendspinDirectory.Entry player = entry("192.168.0.9", 8928, "Kodi", null);
        SendspinDirectory.Entry server = entry("192.168.0.9", 8095, "Music Assistant", null);
        assertNull(YaaccPeers.serverOf(player, Collections.singletonList(server)));
        assertFalse(YaaccPeers.isMergedServer(server, Collections.singletonList(player)));
    }

    @Test
    public void anotherPhoneWithAnotherIdIsNoMatch() {
        SendspinDirectory.Entry player = entry("192.168.0.221", 8928, "Nokia", ID);
        SendspinDirectory.Entry server = entry("192.168.0.50", 8927, "A142", "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz");
        assertNull(YaaccPeers.serverOf(player, Collections.singletonList(server)));
    }
}
