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
package de.yaacc.multiroom.discovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.InetAddress;

/**
 * Manual entries are validated strictly and without any name lookup; the address filter lets only the local ranges of
 * a discovered server through. Plain JVM, every address is a literal.
 */
public class SnapcastEndpointTest {

    private static InetAddress address(int... bytes) throws Exception {
        byte[] raw = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            raw[i] = (byte) bytes[i];
        }
        return InetAddress.getByAddress(raw);
    }

    private static void assertValid(String text, String host, int port) {
        SnapcastEndpoint endpoint = SnapcastEndpoint.parse(text);
        assertNotNull(text, endpoint);
        assertEquals(text, host, endpoint.getHost());
        assertEquals(text, port, endpoint.getPort());
    }

    private static void assertInvalid(String text) {
        assertNull("should be refused: " + text, SnapcastEndpoint.parse(text));
    }

    @Test
    public void aHostNameWithoutAPortGetsTheDefaultControlPort() {
        assertValid("snapserver", "snapserver", 1705);
        assertValid("snap.local", "snap.local", 1705);
    }

    @Test
    public void aHostNameIsLowerCased() {
        assertValid("SnapServer.Local:2000", "snapserver.local", 2000);
    }

    @Test
    public void anIpv4LiteralWithAndWithoutPort() {
        assertValid("192.168.1.20", "192.168.1.20", 1705);
        assertValid("192.168.1.20:1999", "192.168.1.20", 1999);
        assertTrue(SnapcastEndpoint.parse("192.168.1.20").getAddress() != null);
    }

    @Test
    public void anIpv6LiteralIsBracketedWithAPortAndBareWithout() {
        SnapcastEndpoint bracketed = SnapcastEndpoint.parse("[fd00::5]:1706");
        assertNotNull(bracketed);
        assertEquals(1706, bracketed.getPort());
        assertEquals("fd00:0:0:0:0:0:0:5", bracketed.getHost());
        SnapcastEndpoint bare = SnapcastEndpoint.parse("fd00::5");
        assertNotNull(bare);
        assertEquals(1705, bare.getPort());
        assertEquals(bracketed.getHost(), bare.getHost());
        assertNotNull(SnapcastEndpoint.parse("[::1]"));
        assertNotNull(SnapcastEndpoint.parse("::1"));
    }

    @Test
    public void differentWritingsOfOneAddressAreOneEndpoint() {
        assertEquals(SnapcastEndpoint.parse("[fd00::5]:1705"), SnapcastEndpoint.parse("[FD00:0:0:0:0:0:0:5]"));
        assertEquals(SnapcastEndpoint.parse("[::ffff:192.168.1.7]"), SnapcastEndpoint.parse("192.168.1.7"));
    }

    @Test
    public void thePortMustBeBetween1And65535() {
        assertValid("h:1", "h", 1);
        assertValid("h:65535", "h", 65535);
        assertInvalid("h:0");
        assertInvalid("h:65536");
        assertInvalid("h:-1");
        assertInvalid("h:+5");
        assertInvalid("h:");
        assertInvalid("h:12a");
        assertInvalid("h:999999999999999999999");
        assertInvalid("[::1]:0");
        assertInvalid("[::1]:");
    }

    @Test
    public void schemesPathsUserInfoAndWhitespaceAreRefused() {
        assertInvalid("tcp://snapserver");
        assertInvalid("http://snapserver:1705");
        assertInvalid("snapserver/path");
        assertInvalid("snapserver:1705/x");
        assertInvalid("user@snapserver");
        assertInvalid("user:pw@snapserver:1705");
        assertInvalid("snap server");
        assertInvalid(" snapserver");
        assertInvalid("snapserver ");
        assertInvalid("snapserver\n");
        assertInvalid("snap\tserver");
        assertInvalid("snapserver?x=1");
        assertInvalid("snapserver#frag");
        assertInvalid("snap%20server");
    }

    @Test
    public void emptyAndHugeInputIsRefused() {
        assertInvalid(null);
        assertInvalid("");
        assertInvalid(":1705");
        assertInvalid(StringRepeat.of("a", 300));
        assertInvalid(StringRepeat.of("a", 64) + ".local");
    }

    @Test
    public void hostNamesFollowTheLabelRules() {
        assertInvalid("-bad.local");
        assertInvalid("bad-.local");
        assertInvalid("bad..local");
        assertInvalid(".local");
        assertInvalid("local.");
        assertInvalid("under_score");
        assertInvalid("snäpserver");
        assertInvalid("sn\u0000apserver");
        assertValid("a-b.c-d.example", "a-b.c-d.example", 1705);
    }

    @Test
    public void aLeadingZeroInAnIpv4PartIsRefused() {
        // inet_aton and many tools read 010 as octal 8: the same text must not mean two hosts
        assertInvalid("010.0.0.1");
        assertInvalid("10.00.0.1");
        assertInvalid("10.0.000.1");
        assertInvalid("10.0.0.01");
        assertInvalid("192.168.001.5:1705");
        assertInvalid("[::ffff:010.0.0.1]");
        assertValid("10.0.0.1", "10.0.0.1", 1705);
        assertValid("0.0.0.1", "0.0.0.1", 1705);
        assertValid("10.0.0.0", "10.0.0.0", 1705);
        assertValid("100.200.250.255", "100.200.250.255", 1705);
    }

    @Test
    public void aNumericLookingNameIsNotAHostName() {
        assertInvalid("1.2.3");
        assertInvalid("256.1.1.1");
        assertInvalid("1.2.3.4.5");
        assertInvalid("12345");
        assertInvalid("0x7f.1");
    }

    @Test
    public void brokenIpv6LiteralsAreRefused() {
        assertInvalid("[::1");
        assertInvalid("::1]");
        assertInvalid("[::1]x");
        assertInvalid("[:::1]");
        assertInvalid("[1::2::3]");
        assertInvalid("[12345::1]");
        assertInvalid("[g::1]");
        assertInvalid("[1:2:3:4:5:6:7:8:9]");
        assertInvalid("[1:2:3:4:5:6:7]");
        assertInvalid("[fe80::1%wlan0]");
        assertInvalid("fe80::1%wlan0");
        assertInvalid("[]");
        assertInvalid("1:2:3");
    }

    @Test
    public void unspecifiedAndMulticastLiteralsAreRefused() {
        assertInvalid("0.0.0.0");
        assertInvalid("[::]");
        assertInvalid("224.0.0.251");
        assertInvalid("[ff02::fb]");
        assertInvalid("255.255.255.255");
    }

    @Test
    public void aManualEntryMayBeAPublicAddressBecauseTheUserChoseIt() {
        assertValid("8.8.8.8:1705", "8.8.8.8", 1705);
    }

    @Test
    public void theTextFormReadsBackAsTheSameEndpoint() {
        for (String text : new String[]{"snap.local", "10.0.0.2:1999", "[fd00::5]:1706", "[::1]"}) {
            SnapcastEndpoint endpoint = SnapcastEndpoint.parse(text);
            assertEquals(endpoint, SnapcastEndpoint.parse(endpoint.toString()));
        }
        assertEquals("[fd00:0:0:0:0:0:0:5]:1706", SnapcastEndpoint.parse("[fd00::5]:1706").toString());
        assertEquals("snap.local:1705", SnapcastEndpoint.parse("snap.local").toString());
    }

    @Test
    public void anEndpointOfADiscoveredAddressKeepsTheAddressItConnectsTo() throws Exception {
        InetAddress address = address(192, 168, 1, 9);
        SnapcastEndpoint endpoint = SnapcastEndpoint.fromAddress(address, 1705);
        assertEquals(SnapcastEndpoint.parse("192.168.1.9:1705"), endpoint);
        assertEquals(address, endpoint.getAddress());
        assertNull(SnapcastEndpoint.parse("snap.local").getAddress());
    }

    @Test
    public void aBadPortOrAddressForADiscoveredEndpointGivesNothing() throws Exception {
        assertNull(SnapcastEndpoint.fromAddress(null, 1705));
        assertNull(SnapcastEndpoint.fromAddress(address(10, 0, 0, 1), 0));
        assertNull(SnapcastEndpoint.fromAddress(address(10, 0, 0, 1), 70000));
    }

    // ---- the address filter for discovered servers

    @Test
    public void loopbackPrivateAndLinkLocalAddressesAreAccepted() throws Exception {
        assertTrue(SnapcastAddressFilter.isAccepted(address(127, 0, 0, 1)));
        assertTrue(SnapcastAddressFilter.isAccepted(address(10, 1, 2, 3)));
        assertTrue(SnapcastAddressFilter.isAccepted(address(172, 16, 0, 1)));
        assertTrue(SnapcastAddressFilter.isAccepted(address(172, 31, 255, 254)));
        assertTrue(SnapcastAddressFilter.isAccepted(address(192, 168, 0, 5)));
        assertTrue(SnapcastAddressFilter.isAccepted(address(169, 254, 7, 7)));
        assertTrue(SnapcastAddressFilter.isAccepted(InetAddress.getByAddress(new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1})));
        assertTrue("link-local fe80::/10", SnapcastAddressFilter.isAccepted(
                InetAddress.getByAddress(new byte[]{(byte) 0xfe, (byte) 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1})));
        assertTrue("unique-local fc00::/7 (fc)", SnapcastAddressFilter.isAccepted(
                InetAddress.getByAddress(new byte[]{(byte) 0xfc, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1})));
        assertTrue("unique-local fc00::/7 (fd)", SnapcastAddressFilter.isAccepted(
                InetAddress.getByAddress(new byte[]{(byte) 0xfd, 0x12, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1})));
    }

    @Test
    public void publicSharedUnspecifiedAndMulticastAddressesAreRefused() throws Exception {
        assertFalse(SnapcastAddressFilter.isAccepted(null));
        assertFalse(SnapcastAddressFilter.isAccepted(address(8, 8, 8, 8)));
        assertFalse(SnapcastAddressFilter.isAccepted(address(172, 32, 0, 1)));
        assertFalse(SnapcastAddressFilter.isAccepted(address(172, 15, 255, 255)));
        assertFalse("carrier-grade NAT range", SnapcastAddressFilter.isAccepted(address(100, 64, 0, 1)));
        assertFalse(SnapcastAddressFilter.isAccepted(address(0, 0, 0, 0)));
        assertFalse(SnapcastAddressFilter.isAccepted(address(224, 0, 0, 251)));
        assertFalse(SnapcastAddressFilter.isAccepted(address(255, 255, 255, 255)));
        assertFalse("global unicast v6", SnapcastAddressFilter.isAccepted(
                InetAddress.getByAddress(new byte[]{0x20, 0x01, 0x0d, (byte) 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1})));
        assertFalse("v6 multicast", SnapcastAddressFilter.isAccepted(
                InetAddress.getByAddress(new byte[]{(byte) 0xff, 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xfb})));
        assertFalse("deprecated site-local fec0::/10", SnapcastAddressFilter.isAccepted(
                InetAddress.getByAddress(new byte[]{(byte) 0xfe, (byte) 0xc0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1})));
        assertFalse("v6 unspecified", SnapcastAddressFilter.isAccepted(InetAddress.getByAddress(new byte[16])));
    }

    /** Java 11 has no String.repeat in the Android API level of the app's tests; a tiny helper. */
    private static final class StringRepeat {
        static String of(String s, int n) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < n; i++) {
                b.append(s);
            }
            return b.toString();
        }
    }
}
