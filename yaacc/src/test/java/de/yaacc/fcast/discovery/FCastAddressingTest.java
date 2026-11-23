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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Task 3.1: endpoints, ids, the service type and the TXT hints. */
public class FCastAddressingTest {

    // ---- endpoint

    @Test
    public void aHostWithoutPortGetsTheFCastPort() {
        FCastEndpoint endpoint = FCastEndpoint.parse("192.168.0.239");
        assertEquals(46899, endpoint.getPort());
        assertEquals("192.168.0.239:46899", endpoint.toString());
        assertEquals("192.168.0.239", endpoint.getHost());
        assertNotNull(endpoint.getAddress());
    }

    @Test
    public void aPortIsKeptAndTheHostNameIsNotResolved() {
        FCastEndpoint endpoint = FCastEndpoint.parse("ntbktobe-t14:5000");
        assertEquals("ntbktobe-t14:5000", endpoint.toString());
        assertEquals(5000, endpoint.getPort());
        assertNull("a host name is not looked up while parsing", endpoint.getAddress());
    }

    @Test
    public void anIpv6LiteralIsBracketedAndCanonical() {
        FCastEndpoint a = FCastEndpoint.parse("[fe80::1]:46899");
        FCastEndpoint b = FCastEndpoint.parse("[FE80:0:0:0:0:0:0:1]");
        assertEquals(a, b);
        assertTrue(a.toString().startsWith("[") && a.toString().endsWith("]:46899"));
    }

    @Test
    public void badTextIsRefused() {
        String[] bad = {null, "", " ", "http://192.168.0.1", "192.168.0.1:0", "192.168.0.1:70000", "192.168.0.1:abc", "a b", "host\n", "ünï.example",
                "[1.2.3.4]", "0.0.0.0", "224.0.0.1", "255.255.255.255", "host:1:2", "fe80::1%eth0", "a/b", "user@host"};
        for (String text : bad) {
            assertNull("accepted: " + text, FCastEndpoint.parse(text));
        }
    }

    @Test
    public void theSameReceiverWrittenTwoWaysIsOneEndpoint() {
        assertEquals(FCastEndpoint.parse("192.168.0.239"), FCastEndpoint.parse("192.168.0.239:46899"));
        assertEquals(FCastEndpoint.parse("192.168.0.239").hashCode(), FCastEndpoint.parse("192.168.0.239:46899").hashCode());
        assertNotEquals(FCastEndpoint.parse("192.168.0.239"), FCastEndpoint.parse("192.168.0.239:46898"));
    }

    @Test
    public void canonicalParsingAcceptsOnlyTheCanonicalForm() {
        assertNotNull(FCastEndpoint.parseCanonical("192.168.0.239:46899"));
        assertNull("no port is not canonical", FCastEndpoint.parseCanonical("192.168.0.239"));
        assertNull(FCastEndpoint.parseCanonical("[FE80:0:0:0:0:0:0:1]:46899"));
    }

    @Test
    public void anEndpointFromAResolvedAddress() throws Exception {
        FCastEndpoint endpoint = FCastEndpoint.fromAddress(InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 0, (byte) 239}), 46899);
        assertEquals("192.168.0.239:46899", endpoint.toString());
        assertNull(FCastEndpoint.fromAddress(null, 46899));
        assertNull(FCastEndpoint.fromAddress(InetAddress.getByAddress(new byte[]{1, 2, 3, 4}), 0));
        assertNull(FCastEndpoint.fromAddress(InetAddress.getByAddress(new byte[]{1, 2, 3, 4}), 65536));
        assertNull(FCastEndpoint.fromAddress(InetAddress.getByAddress(new byte[]{0, 0, 0, 0}), 46899));
    }

    // ---- id

    @Test
    public void theIdIsThePrefixAndTheCanonicalEndpoint() {
        FCastEndpoint endpoint = FCastEndpoint.parse("192.168.0.239");
        String id = FCastReceiverId.of(endpoint);
        assertEquals("fcast:192.168.0.239:46899", id);
        assertTrue(FCastReceiverId.isFCastId(id));
        assertEquals(endpoint, FCastReceiverId.parse(id));
    }

    @Test
    public void anIpv6IdIsParsedFromTheRight() {
        FCastEndpoint endpoint = FCastEndpoint.parse("[fe80::1]:5000");
        assertEquals(endpoint, FCastReceiverId.parse(FCastReceiverId.of(endpoint)));
    }

    @Test
    public void aMalformedIdIsRefused() {
        char[] longHost = new char[400];
        Arrays.fill(longHost, 'a');
        String[] bad = {null, "", "fcast:", "fcast:192.168.0.239", "fcast:192.168.0.239:70000", "snapcast:192.168.0.239:1705", "FCAST:192.168.0.239:46899",
                "fcast:192.168.0.239:46899 ", "fcast:" + new String(longHost) + ":46899", "uuid:1234", "fcast:[FE80:0:0:0:0:0:0:1]:46899"};
        for (String id : bad) {
            assertNull("accepted: " + id, FCastReceiverId.parse(id));
        }
        assertFalse(FCastReceiverId.isFCastId("snapcast:1:2"));
        assertFalse(FCastReceiverId.isFCastId(null));
    }

    // ---- service type

    @Test
    public void thePlatformsWritingsOfTheTypeAreRecognised() {
        for (String reported : new String[]{"_fcast._tcp", "_fcast._tcp.", "_FCast._TCP.", " _fcast._tcp.local.", "_fcast._tcp.local"}) {
            assertTrue(reported, FCastServiceType.isFCast(reported));
            assertEquals("_fcast._tcp", FCastServiceType.canonical(reported));
        }
        for (String other : new String[]{null, "", "_snapcast._tcp.", "_fcast._udp.", "_fcastx._tcp.", "fcast"}) {
            assertFalse(String.valueOf(other), FCastServiceType.isFCast(other));
        }
    }

    @Test
    public void theServiceIdIsTypeAndName() {
        assertEquals("_fcast._tcp|FCast-pc", FCastServiceType.serviceId("_fcast._tcp.", "FCast-pc"));
        assertEquals("_fcast._tcp|", FCastServiceType.serviceId("_fcast._tcp.", null));
    }

    // ---- TXT hints

    private static Map<String, byte[]> txt(String... keyValue) {
        Map<String, byte[]> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValue.length; i += 2) {
            map.put(keyValue[i], keyValue[i + 1] == null ? null : keyValue[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        return map;
    }

    private static final String FP = "iGGi8SWNXUQMcuWmmXqAeCMpbvcIEQjyKxluLIPAnig=";

    @Test
    public void theDesktopReceiversRecords() {
        FCastServiceTxt hints = FCastServiceTxt.parse(txt("v", "4", "fp", FP));
        assertEquals(Integer.valueOf(4), hints.getProtocolVersion());
        assertEquals(FP, hints.getFingerprint());
        assertNull(hints.getAppName());
    }

    @Test
    public void theAndroidReceiversRecords() {
        FCastServiceTxt hints = FCastServiceTxt.parse(txt("version", "3", "appName", "FCast Receiver", "appVersion", "17"));
        assertEquals(Integer.valueOf(3), hints.getProtocolVersion());
        assertEquals("FCast Receiver", hints.getAppName());
        assertEquals("17", hints.getAppVersion());
        assertNull(hints.getFingerprint());
    }

    @Test
    public void keysAreCaseInsensitive() {
        FCastServiceTxt hints = FCastServiceTxt.parse(txt("V", "4", "FP", FP, "APPNAME", "x"));
        assertEquals(Integer.valueOf(4), hints.getProtocolVersion());
        assertEquals(FP, hints.getFingerprint());
        assertEquals("x", hints.getAppName());
    }

    @Test
    public void valuesOfTheWrongShapeAreIgnored() {
        String[][] bad = {{"v", "four"}, {"v", "0"}, {"v", "-1"}, {"v", "99999"}, {"v", ""}, {"v", "3.5"}, {"fp", "short"}, {"fp", FP + "x"},
                {"fp", FP.replace("=", "!")}, {"fp", "iGGi8SWNXUQMcuWmmXqAeCMpbvcIEQjyKxluLIPAnig"}};
        for (String[] pair : bad) {
            FCastServiceTxt hints = FCastServiceTxt.parse(txt(pair[0], pair[1]));
            assertNull(pair[0] + "=" + pair[1], hints.getProtocolVersion());
            assertNull(pair[0] + "=" + pair[1], hints.getFingerprint());
        }
    }

    @Test
    public void textHintsAreCleanedAndCut() {
        char[] chars = new char[1000];
        Arrays.fill(chars, 'x');
        FCastServiceTxt hints = FCastServiceTxt.parse(txt("appName", "Ap\u0000p\n‮", "appVersion", "1​.2"));
        assertEquals("App", hints.getAppName());
        assertEquals("1.2", hints.getAppVersion());
        assertSame("a value of 1000 bytes is dropped, not cut", FCastServiceTxt.EMPTY, FCastServiceTxt.parse(txt("appName", new String(chars))));
        assertEquals(60, FCastServiceTxt.parse(txt("appName", new String(chars, 0, 200))).getAppName().length());
    }

    @Test
    public void nothingUsefulIsTheEmptyHints() {
        assertSame(FCastServiceTxt.EMPTY, FCastServiceTxt.parse(null));
        assertSame(FCastServiceTxt.EMPTY, FCastServiceTxt.parse(new HashMap<String, byte[]>()));
        assertSame(FCastServiceTxt.EMPTY, FCastServiceTxt.parse(txt("flag", null, "other", "x")));
        assertSame("a flag without value", FCastServiceTxt.EMPTY, FCastServiceTxt.parse(txt("v", null)));
    }

    @Test
    public void onlyThirtyTwoAttributesAreRead() {
        Map<String, byte[]> many = new LinkedHashMap<>();
        for (int i = 0; i < 40; i++) {
            many.put("k" + i, "x".getBytes(StandardCharsets.UTF_8));
        }
        many.put("v", "4".getBytes(StandardCharsets.UTF_8)); // the 41st
        assertNull(FCastServiceTxt.parse(many).getProtocolVersion());
    }

    @Test
    public void vWinsOverVersionWhenBothAreThere() {
        assertEquals(Integer.valueOf(4), FCastServiceTxt.parse(txt("version", "3", "v", "4")).getProtocolVersion());
        assertEquals(Integer.valueOf(4), FCastServiceTxt.parse(txt("v", "4", "version", "3")).getProtocolVersion());
    }
}
