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
package de.yaacc.fcast.discovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The networks of the phone: which addresses of a connection link are on them. */
public class FCastSubnetTest {

    private static InetAddress ip(String text) throws Exception {
        return InetAddress.getByName(text);
    }

    @Test
    public void anAddressIsInTheNetworkOfThePhoneByItsPrefixLength() throws Exception {
        FCastSubnet home = new FCastSubnet(ip("192.168.0.57"), 24);

        assertTrue(home.contains(ip("192.168.0.239")));
        assertTrue(home.contains(ip("192.168.0.1")));
        assertFalse(home.contains(ip("192.168.1.239")));
        assertFalse(home.contains(ip("172.17.0.1")));
    }

    @Test
    public void thePrefixNeedNotEndOnAByteBoundary() throws Exception {
        FCastSubnet small = new FCastSubnet(ip("10.0.0.130"), 25); // 10.0.0.128 to 10.0.0.255

        assertTrue(small.contains(ip("10.0.0.200")));
        assertFalse(small.contains(ip("10.0.0.5")));
        FCastSubnet wide = new FCastSubnet(ip("172.20.5.5"), 12); // 172.16.0.0 to 172.31.255.255
        assertTrue(wide.contains(ip("172.17.0.1")));
        assertTrue(wide.contains(ip("172.31.255.1")));
        assertFalse(wide.contains(ip("172.32.0.1")));
    }

    @Test
    public void zeroAndFullPrefixesAndOtherEdgeCases() throws Exception {
        assertTrue(new FCastSubnet(ip("10.1.2.3"), 0).contains(ip("8.8.8.8")));
        assertTrue(new FCastSubnet(ip("10.1.2.3"), 32).contains(ip("10.1.2.3")));
        assertFalse(new FCastSubnet(ip("10.1.2.3"), 32).contains(ip("10.1.2.4")));
        assertTrue("a prefix that is too long counts as the full address", new FCastSubnet(ip("10.1.2.3"), 99).contains(ip("10.1.2.3")));
        assertFalse(new FCastSubnet(ip("10.1.2.3"), 24).contains(null));
        assertFalse(new FCastSubnet(null, 24).contains(ip("10.1.2.3")));
    }

    @Test
    public void ipv4AndIpv6NeverMatchEachOther() throws Exception {
        assertFalse(new FCastSubnet(ip("192.168.0.1"), 24).contains(ip("fd12:3456:789a::5")));
        FCastSubnet v6 = new FCastSubnet(ip("fd12:3456:789a::1"), 64);
        assertTrue(v6.contains(ip("fd12:3456:789a::5")));
        assertFalse(v6.contains(ip("fd12:3456:789b::5")));
        assertFalse(v6.contains(ip("192.168.0.1")));
    }

    @Test
    public void theEndpointsAreSplitInTheOrderOfTheLink() {
        List<FCastEndpoint> all = new ArrayList<>();
        for (String text : new String[]{"172.19.0.1", "192.168.122.1", "192.168.0.239", "172.17.0.1", "192.168.0.7", "pc.lan"}) {
            all.add(FCastEndpoint.parse(text));
        }
        try {
            List<FCastSubnet> subnets = Collections.singletonList(new FCastSubnet(ip("192.168.0.57"), 24));

            assertEquals(Arrays.asList("192.168.0.239:46899", "192.168.0.7:46899"), texts(FCastSubnet.inside(subnets, all)));
            assertEquals(Arrays.asList("172.19.0.1:46899", "192.168.122.1:46899", "172.17.0.1:46899", "pc.lan:46899"),
                    texts(FCastSubnet.outside(subnets, all)));
            assertEquals("no networks known: everything is outside, in order", 6, FCastSubnet.outside(Collections.<FCastSubnet>emptyList(), all).size());
            assertTrue(FCastSubnet.inside(Collections.<FCastSubnet>emptyList(), all).isEmpty());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static List<String> texts(List<FCastEndpoint> endpoints) {
        List<String> texts = new ArrayList<>();
        for (FCastEndpoint endpoint : endpoints) {
            texts.add(endpoint.toString());
        }
        return texts;
    }
}
