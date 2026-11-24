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
package de.yaacc.sendspin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import de.yaacc.sendspin.hub.Endpoint;

import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** What the user types, the stored list of players, the receiver ids and the approvals. */
public class SendspinStoreTest {

    /** A store in a map. */
    static final class MapStore implements StringSetStore {
        final Map<String, Set<String>> map = new HashMap<>();

        @Override
        public Set<String> get(String key) {
            Set<String> set = map.get(key);
            return set == null ? new HashSet<String>() : new HashSet<>(set);
        }

        @Override
        public void put(String key, Set<String> values) {
            map.put(key, new HashSet<>(values));
        }
    }

    @Test
    public void addressesAreParsedWithTheDefaultPortAndPath() {
        Endpoint plain = SendspinAddress.parse("kitchen.local");
        assertEquals("kitchen.local", plain.host());
        assertEquals(8928, plain.port());
        assertEquals("/sendspin", plain.path());
        assertEquals(9000, SendspinAddress.parse(" 192.168.0.7:9000 ").port());
        assertEquals("fe80::1", SendspinAddress.parse("[fe80::1]").host());
        assertEquals(1234, SendspinAddress.parse("[fe80::1]:1234").port());
    }

    @Test
    public void whatIsNotAnAddressIsRefused() {
        String[] bad = {null, "", "  ", "ws://host:1", "host/path", "user@host", "host:0", "host:65536", "host:abc", "host:", "fe80::1",
                "[fe80::1", "[fe80::1]x", "[nothex]", "ho st", "host\r\nX: 1", ".host", "-host", "host-", "a:b:c", "häst"};
        for (String text : bad) {
            assertNull("accepted: " + text, SendspinAddress.parse(text));
        }
        assertNull(SendspinAddress.parse(new String(new char[300]).replace('\0', 'a')));
    }

    @Test
    public void receiverIdsRoundTrip() {
        for (String text : new String[]{"192.168.0.7", "kitchen.local:9001", "[fe80::1]:8928"}) {
            Endpoint endpoint = SendspinAddress.parse(text);
            String id = SendspinAddress.idOf(endpoint);
            assertTrue(id, SendspinAddress.isSendspinId(id));
            assertEquals(endpoint, SendspinAddress.endpointOf(id));
        }
        assertFalse(SendspinAddress.isSendspinId("fcast:1.2.3.4:46899"));
        assertFalse(SendspinAddress.isSendspinId(null));
        assertNull(SendspinAddress.endpointOf("sendspin:bad host"));
    }

    @Test
    public void thePlayerListKeepsAtMostSixteenInAFixedOrder() {
        SendspinPlayers players = new SendspinPlayers(new MapStore());
        assertEquals(SendspinPlayers.AddResult.ADDED, players.add("b.local"));
        assertEquals(SendspinPlayers.AddResult.ADDED, players.add("a.local:9000"));
        assertEquals(SendspinPlayers.AddResult.ALREADY_THERE, players.add("a.local:9000"));
        assertEquals(SendspinPlayers.AddResult.NOT_AN_ADDRESS, players.add("not an address"));
        List<Endpoint> list = players.list();
        assertEquals(2, list.size());
        assertEquals("a.local", list.get(0).host());
        for (int i = 0; i < 14; i++) {
            assertEquals(SendspinPlayers.AddResult.ADDED, players.add("host" + i));
        }
        assertEquals(SendspinPlayers.AddResult.TOO_MANY, players.add("one-too-many"));
        players.remove(SendspinAddress.parse("host3"));
        assertEquals(15, players.list().size());
        assertEquals(SendspinPlayers.AddResult.ADDED, players.add("one-more"));
    }

    @Test
    public void damagedStoredEntriesAreLeftOut() {
        MapStore store = new MapStore();
        Set<String> set = new HashSet<>();
        set.add("ok.local:8928");
        set.add("???");
        store.put(SendspinSettings.KEY_PLAYERS, set);
        assertEquals(1, new SendspinPlayers(store).list().size());
    }

    private static String id(int n) {
        char[] c = new char[43];
        java.util.Arrays.fill(c, 'A');
        String s = Integer.toString(n);
        for (int i = 0; i < s.length(); i++) {
            c[i] = s.charAt(i);
        }
        return new String(c);
    }

    @Test
    public void approvalsAreRememberedAndRevocableAndTheOldestAreDropped() {
        SendspinApprovals approvals = new SendspinApprovals(new MapStore());
        assertFalse(approvals.isApproved(id(1)));
        approvals.approve(id(1));
        assertTrue(approvals.isApproved(id(1)));
        approvals.revoke(id(1));
        assertFalse(approvals.isApproved(id(1)));
        approvals.approve("short"); // not a client id
        assertFalse(approvals.isApproved("short"));
        for (int i = 0; i < SendspinSettings.MAX_APPROVALS + 5; i++) {
            approvals.approve(id(i));
        }
        assertFalse("the oldest was dropped", approvals.isApproved(id(0)));
        assertTrue(approvals.isApproved(id(SendspinSettings.MAX_APPROVALS + 4)));
        assertNotNull(approvals.pskFor(id(7)));
    }

    @Test
    public void theSwitchIsOffByDefaultAndForAnUnreadableValue() {
        assertFalse(SendspinSettings.isEnabled(null));
        assertFalse(SendspinSettings.isEnabled((key, def) -> def));
        assertTrue(SendspinSettings.isEnabled((key, def) -> true));
        assertFalse(SendspinSettings.isEnabled((key, def) -> {
            throw new ClassCastException();
        }));
    }
}
