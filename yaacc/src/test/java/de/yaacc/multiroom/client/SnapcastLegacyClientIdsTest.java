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
package de.yaacc.multiroom.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import de.yaacc.multiroom.discovery.SnapcastEndpoint;

/**
 * Task 12.19: the per-server client ids that earlier builds gave a server (secret and the canonical text of the endpoint the
 * client connected to, {@code host:1704} when the entry had no port) are computed again from the install secret, so the entries
 * they left on the servers are recognised as this phone's. The vectors were computed with the old derivation (SHA-256 of the
 * secret, a zero byte, the endpoint text; {@code yaacc-} and the first 16 hexadecimal characters) outside of this code base.
 * Plain JVM.
 */
public class SnapcastLegacyClientIdsTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    private static SnapcastEndpoint server(String text) {
        SnapcastEndpoint endpoint = SnapcastEndpoint.parse(text);
        assertTrue(text, endpoint != null);
        return endpoint;
    }

    // ---- the derivation itself: vectors of the old code ---------------------------------------------------------------

    @Test
    public void theOldDerivationIsReproducedExactly() {
        String[][] table = {
                {"192.168.0.51:1704", "yaacc-16555479cdf4dc61"},
                {"192.168.0.51:1705", "yaacc-396e159dc0cd8935"},
                {"snapserver.local:1704", "yaacc-fa40a9cbe9ac8e90"},
                {"[0:0:0:0:0:0:0:1]:1704", "yaacc-29ca052f17ab231d"},
                {"10.0.0.9:1704", "yaacc-76b1f7da6c2d92aa"},
        };
        for (String[] row : table) {
            assertEquals(row[0], row[1], SnapcastLegacyClientIds.legacyId(SECRET, row[0]));
        }
    }

    @Test
    public void theOldIdIsNotTheNewOneAndNeedsTheSecret() {
        assertFalse(SnapcastLegacyClientIds.legacyId(SECRET, "192.168.0.51:1704").equals(SnapcastClientIdentity.deriveId(SECRET)));
        assertFalse(SnapcastLegacyClientIds.legacyId(SECRET, "192.168.0.51:1704")
                .equals(SnapcastLegacyClientIds.legacyId("ffffffffffffffffffffffffffffffff", "192.168.0.51:1704")));
    }

    // ---- which endpoint texts are tried -------------------------------------------------------------------------------

    @Test
    public void aKnownServerGivesTheStreamPortTheControlPortAndItsOwnPort() {
        List<String> ids = SnapcastLegacyClientIds.of(SECRET, Collections.singletonList(server("192.168.0.51")), null);

        assertEquals("the stream port (what a typed host gets), the control port of the list and nothing else for the default port", 2, ids.size());
        assertTrue(ids.contains("yaacc-16555479cdf4dc61"));
        assertTrue(ids.contains("yaacc-396e159dc0cd8935"));
    }

    @Test
    public void aServerOnAnotherControlPortAddsThatPortToo() {
        List<String> ids = SnapcastLegacyClientIds.of(SECRET, Collections.singletonList(server("192.168.0.51:1706")), null);

        assertEquals(3, ids.size());
        assertTrue(ids.contains(SnapcastLegacyClientIds.legacyId(SECRET, "192.168.0.51:1706")));
    }

    @Test
    public void anIpv6ServerIsWrittenInTheCanonicalBracketForm() {
        List<String> ids = SnapcastLegacyClientIds.of(SECRET, Collections.singletonList(server("[::1]")), null);

        assertTrue("host written as the old code wrote it: eight groups in brackets", ids.contains("yaacc-29ca052f17ab231d"));
    }

    @Test
    public void aHostNameServerIsLowerCased() {
        List<String> ids = SnapcastLegacyClientIds.of(SECRET, Collections.singletonList(server("SnapServer.Local")), null);

        assertTrue(ids.contains("yaacc-fa40a9cbe9ac8e90"));
    }

    @Test
    public void theAddressTheListenTestSavedIsReadLikeTheOldClientReadIt() {
        // typed text: no port means the stream port, case does not matter, the text is the one the old client checked
        assertTrue(SnapcastLegacyClientIds.of(SECRET, null, "snapserver.local").contains("yaacc-fa40a9cbe9ac8e90"));
        assertTrue(SnapcastLegacyClientIds.of(SECRET, null, "  SNAPSERVER.local ").contains("yaacc-fa40a9cbe9ac8e90"));
        assertTrue(SnapcastLegacyClientIds.of(SECRET, null, "10.0.0.9:1704").contains("yaacc-76b1f7da6c2d92aa"));
        assertTrue("a typed host that is a server not in the list is still found", SnapcastLegacyClientIds.of(SECRET, null, "[::1]")
                .contains("yaacc-29ca052f17ab231d"));
    }

    @Test
    public void damagedOrMissingInputGivesNothing() {
        assertTrue(SnapcastLegacyClientIds.of(null, Collections.singletonList(server("10.0.0.9")), "10.0.0.9").isEmpty());
        assertTrue("not a secret as the app makes it", SnapcastLegacyClientIds.of("secret", Collections.singletonList(server("10.0.0.9")), null).isEmpty());
        assertTrue(SnapcastLegacyClientIds.of("", Collections.singletonList(server("10.0.0.9")), null).isEmpty());
        assertTrue(SnapcastLegacyClientIds.of(SECRET, null, null).isEmpty());
        assertTrue(SnapcastLegacyClientIds.of(SECRET, Collections.<SnapcastEndpoint>emptyList(), "").isEmpty());
        assertTrue("an address that was never valid", SnapcastLegacyClientIds.of(SECRET, null, "http://x/y z").isEmpty());
        assertTrue(SnapcastLegacyClientIds.of(SECRET, Arrays.asList((SnapcastEndpoint) null), null).isEmpty());
    }

    @Test
    public void everyIdIsListedOnceAndInAStableOrder() {
        List<SnapcastEndpoint> servers = Arrays.asList(server("10.0.0.9"), server("10.0.0.9:1705"), server("192.168.0.51"));

        List<String> first = SnapcastLegacyClientIds.of(SECRET, servers, "10.0.0.9");
        List<String> second = SnapcastLegacyClientIds.of(SECRET, servers, "10.0.0.9");

        assertEquals(first, second);
        assertEquals(first.size(), new HashSet<>(first).size());
        assertEquals("the address of the listen test comes first", "yaacc-76b1f7da6c2d92aa", first.get(0));
        assertEquals("two hosts, two ports each", 4, first.size());
    }

    @Test
    public void theListIsBounded() {
        List<SnapcastEndpoint> many = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            many.add(server("10.1.0." + i + ":" + (2000 + i)));
        }

        List<String> ids = SnapcastLegacyClientIds.of(SECRET, many, "10.0.0.9");

        assertEquals(SnapcastLegacyClientIds.MAX_IDS, ids.size());
        assertTrue("the address of the listen test is kept when the list is cut", ids.contains("yaacc-76b1f7da6c2d92aa"));
        assertTrue(SnapcastLegacyClientIds.MAX_IDS <= 64);
    }

    @Test
    public void noLegacyIdIsTheCurrentId() {
        List<String> ids = SnapcastLegacyClientIds.of(SECRET, Arrays.asList(server("10.0.0.9"), server("192.168.0.51")), "snapserver.local");

        assertFalse(ids.contains(SnapcastClientIdentity.deriveId(SECRET)));
        for (String id : ids) {
            assertTrue(id, id.matches("yaacc-[0-9a-f]{16}"));
        }
    }
}
