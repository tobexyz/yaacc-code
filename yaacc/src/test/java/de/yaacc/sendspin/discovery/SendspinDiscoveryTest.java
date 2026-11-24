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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import de.yaacc.sendspin.hub.Endpoint;

import org.junit.Test;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** The service type, the TXT record and the directory of announced players, with a clock the test moves. */
public class SendspinDiscoveryTest {

    private static Map<String, byte[]> txt(String... pairs) {
        Map<String, byte[]> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        return map;
    }

    private static InetAddress ip(int a, int b, int c, int d) throws Exception {
        return InetAddress.getByAddress(new byte[]{(byte) a, (byte) b, (byte) c, (byte) d});
    }

    private static final SendspinServiceTxt DEFAULT_TXT = SendspinServiceTxt.parse(txt("path", "/sendspin", "name", "Kitchen"));

    @Test
    public void theServiceTypeIsRecognizedWhateverDotsAndCaseThePlatformReports() {
        assertTrue(SendspinServiceType.isSendspin("_sendspin._tcp"));
        assertTrue(SendspinServiceType.isSendspin("_sendspin._tcp."));
        assertTrue(SendspinServiceType.isSendspin("._sendspin._tcp"));
        assertTrue(SendspinServiceType.isSendspin("_SendSpin._TCP.local."));
        assertFalse(SendspinServiceType.isSendspin("_sendspin-server._tcp"));
        assertFalse(SendspinServiceType.isSendspin("_fcast._tcp"));
        assertFalse(SendspinServiceType.isSendspin(null));
        assertEquals("_sendspin._tcp|Kitchen", SendspinServiceType.serviceId("_sendspin._tcp", "Kitchen"));
    }

    @Test
    public void theTxtGivesPathAndNameAndDefaultsAMissingPath() {
        SendspinServiceTxt full = SendspinServiceTxt.parse(txt("path", "/player/1", "name", "Living‮room"));
        assertEquals("/player/1", full.path());
        assertFalse(full.name().contains("‮"));
        assertEquals("/sendspin", SendspinServiceTxt.parse(txt("name", "x")).path());
        assertEquals("/sendspin", SendspinServiceTxt.parse(null).path());
        assertEquals("/a", SendspinServiceTxt.parse(txt("PATH", "/a")).path());
    }

    @Test
    public void aPathThatIsNotPathAbsoluteRefusesTheService() {
        String[] bad = {"sendspin", "//evil", "/a b", "/a?x=1", "/a#f", "/a\r\nX: 1", "/%zz", "/%4", "/ä", "http://x/", "",
                new String(new char[SendspinServiceTxt.MAX_PATH + 1]).replace('\0', 'a')};
        for (String path : bad) {
            assertNull("accepted: " + path, SendspinServiceTxt.parse(txt("path", path)));
        }
        assertNotNull(SendspinServiceTxt.parse(txt("path", "/a/b-c_d.e~f%20g:h@i+j")));
        Map<String, byte[]> notUtf8 = new HashMap<>();
        notUtf8.put("path", new byte[]{'/', (byte) 0xC3, 0x28});
        assertNull(SendspinServiceTxt.parse(notUtf8));
    }

    @Test
    public void anAnnouncementIsListedWithItsEndpointAndName() throws Exception {
        SendspinDirectory directory = new SendspinDirectory(() -> 0L, 30_000L);
        assertTrue(directory.serviceFound("_sendspin._tcp|Kitchen", "Kitchen", ip(192, 168, 0, 7), 8928, DEFAULT_TXT));
        List<SendspinDirectory.Entry> list = directory.snapshot();
        assertEquals(1, list.size());
        assertEquals(new Endpoint("192.168.0.7", 8928, "/sendspin"), list.get(0).endpoint());
        assertEquals("Kitchen", list.get(0).name());
    }

    @Test
    public void theNameIsTheTxtNameNotTheInstanceNameThatIsTheClientIdOfARealPlayer() throws Exception {
        // what the reference player announces: the instance name is its client id, the TXT has name and path
        String clientId = "K1qnrUZAl-kRJr79yDG5GwdcGAefWB9rws3cwI6X9E8";
        SendspinDirectory directory = new SendspinDirectory(() -> 0L, 30_000L);
        directory.serviceFound("_sendspin._tcp|" + clientId, clientId, ip(192, 168, 0, 7), 8928,
                SendspinServiceTxt.parse(txt("name", "RigPlayer", "path", "/sendspin")));
        assertEquals("RigPlayer", directory.snapshot().get(0).name());
        // without a TXT name the id is no name: the card shows the address
        SendspinDirectory without = new SendspinDirectory(() -> 0L, 30_000L);
        without.serviceFound("x", clientId, ip(192, 168, 0, 7), 8928, SendspinServiceTxt.parse(txt("path", "/sendspin")));
        assertEquals("", without.snapshot().get(0).name());
        // an instance name that is a name is used when the TXT has none
        SendspinDirectory named = new SendspinDirectory(() -> 0L, 30_000L);
        named.serviceFound("y", "Kitchen", ip(192, 168, 0, 8), 8928, SendspinServiceTxt.parse(txt("path", "/sendspin")));
        assertEquals("Kitchen", named.snapshot().get(0).name());
    }

    @Test
    public void onlyLocalNetworkAddressesAndUsablePortsAreAccepted() throws Exception {
        SendspinDirectory directory = new SendspinDirectory(() -> 0L, 30_000L);
        assertFalse("loopback", directory.serviceFound("a", "a", InetAddress.getByName("127.0.0.1"), 8928, DEFAULT_TXT));
        assertFalse("public", directory.serviceFound("b", "b", ip(8, 8, 8, 8), 8928, DEFAULT_TXT));
        assertFalse("unspecified", directory.serviceFound("c", "c", InetAddress.getByName("0.0.0.0"), 8928, DEFAULT_TXT));
        assertFalse("low port", directory.serviceFound("d", "d", ip(192, 168, 0, 7), 80, DEFAULT_TXT));
        assertFalse("high port", directory.serviceFound("e", "e", ip(192, 168, 0, 7), 70000, DEFAULT_TXT));
        assertFalse("no address", directory.serviceFound("f", "f", null, 8928, DEFAULT_TXT));
        assertFalse("refused txt", directory.serviceFound("g", "g", ip(192, 168, 0, 7), 8928, null));
        assertFalse("link local v6", directory.serviceFound("h", "h", InetAddress.getByName("fe80::1"), 8928, DEFAULT_TXT));
        assertTrue("unique local v6", directory.serviceFound("i", "i", InetAddress.getByName("fd00::1"), 8928, DEFAULT_TXT));
        assertTrue("10/8", directory.serviceFound("j", "j", ip(10, 1, 2, 3), 8928, DEFAULT_TXT));
        List<SendspinDirectory.Entry> listed = directory.snapshot();
        assertEquals(2, listed.size());
        java.util.Set<String> hosts = new java.util.HashSet<>();
        for (SendspinDirectory.Entry entry : listed) {
            hosts.add(entry.endpoint().host());
        }
        assertTrue(hosts.toString(), hosts.contains("10.1.2.3"));
        assertTrue(hosts.toString(), hosts.contains("fd00:0:0:0:0:0:0:1"));
    }

    @Test
    public void aLostServiceStaysForTheGracePeriodAndThenGoes() throws Exception {
        final long[] now = {1000};
        SendspinDirectory directory = new SendspinDirectory(() -> now[0], 30_000L);
        directory.serviceFound("s", "Kitchen", ip(192, 168, 0, 7), 8928, DEFAULT_TXT);
        directory.serviceLost("s");
        now[0] += 29_000;
        assertEquals(1, directory.snapshot().size());
        now[0] += 2_000;
        assertEquals(0, directory.snapshot().size());
    }

    @Test
    public void aServiceThatComesBackInsideTheGraceIsNotLost() throws Exception {
        final long[] now = {1000};
        SendspinDirectory directory = new SendspinDirectory(() -> now[0], 30_000L);
        directory.serviceFound("s", "Kitchen", ip(192, 168, 0, 7), 8928, DEFAULT_TXT);
        directory.serviceLost("s");
        now[0] += 20_000;
        directory.serviceFound("s", "Kitchen", ip(192, 168, 0, 7), 8928, DEFAULT_TXT);
        now[0] += 20_000;
        assertEquals("the new announcement ended the loss", 1, directory.snapshot().size());
    }

    @Test
    public void twoServiceNamesAtOneAddressAreOnePlayer() throws Exception {
        SendspinDirectory directory = new SendspinDirectory(() -> 0L, 30_000L);
        directory.serviceFound("a", "Kitchen", ip(192, 168, 0, 7), 8928, DEFAULT_TXT);
        directory.serviceFound("b", "Kitchen (2)", ip(192, 168, 0, 7), 8928, DEFAULT_TXT);
        assertEquals(1, directory.snapshot().size());
    }

    @Test
    public void aNameThatMovesToAnotherAddressIsTheNewEndpoint() throws Exception {
        SendspinDirectory directory = new SendspinDirectory(() -> 0L, 30_000L);
        directory.serviceFound("a", "Kitchen", ip(192, 168, 0, 7), 8928, DEFAULT_TXT);
        directory.serviceFound("a", "Kitchen", ip(192, 168, 0, 9), 8928, DEFAULT_TXT);
        List<SendspinDirectory.Entry> list = directory.snapshot();
        assertEquals(1, list.size());
        assertEquals("192.168.0.9", list.get(0).endpoint().host());
    }

    @Test
    public void aFloodDoesNotPushOutWhatIsKnownButAKnownServiceCanStillMove() throws Exception {
        SendspinDirectory directory = new SendspinDirectory(() -> 0L, 30_000L);
        for (int i = 0; i < SendspinDirectory.MAX_DISCOVERED; i++) {
            assertTrue(directory.serviceFound("s" + i, "p" + i, ip(192, 168, 1, i + 1), 8928, DEFAULT_TXT));
        }
        assertFalse(directory.serviceFound("extra", "extra", ip(192, 168, 2, 1), 8928, DEFAULT_TXT));
        assertTrue(directory.serviceFound("s0", "p0", ip(192, 168, 3, 1), 8928, DEFAULT_TXT));
        assertEquals(SendspinDirectory.MAX_DISCOVERED, directory.snapshot().size());
    }

    @Test
    public void theListIsSortedByNameAndCleared() throws Exception {
        SendspinDirectory directory = new SendspinDirectory(() -> 0L, 30_000L);
        directory.serviceFound("b", "b", ip(192, 168, 0, 2), 8928, SendspinServiceTxt.parse(txt("name", "bath")));
        directory.serviceFound("a", "a", ip(192, 168, 0, 1), 8928, SendspinServiceTxt.parse(txt("name", "Attic")));
        assertEquals("Attic", directory.snapshot().get(0).name());
        directory.clear();
        assertTrue(directory.snapshot().isEmpty());
    }

    @Test
    public void listenersHearRealChangesOnly() throws Exception {
        SendspinDirectory directory = new SendspinDirectory(() -> 0L, 30_000L);
        final AtomicInteger changes = new AtomicInteger();
        directory.addListener(changes::incrementAndGet);
        directory.serviceFound("a", "Kitchen", ip(192, 168, 0, 7), 8928, DEFAULT_TXT);
        assertEquals(1, changes.get());
        directory.serviceFound("a", "Kitchen", ip(192, 168, 0, 7), 8928, DEFAULT_TXT); // the same again
        assertEquals(1, changes.get());
        directory.serviceFound("a", "Kitchen", ip(192, 168, 0, 8), 8928, DEFAULT_TXT);
        assertEquals(2, changes.get());
        directory.clear();
        assertEquals(3, changes.get());
        directory.clear();
        assertEquals("nothing to forget", 3, changes.get());
    }

    @Test
    public void theTypeOfAServerIsNotTheTypeOfAPlayer() {
        assertEquals("_sendspin-server._tcp", SendspinServiceType.SERVER_TYPE);
        assertTrue(SendspinServiceType.matches("_sendspin-server._tcp.", SendspinServiceType.SERVER_TYPE));
        assertTrue(SendspinServiceType.matches(" _SENDSPIN-SERVER._tcp.local. ", SendspinServiceType.SERVER_TYPE));
        assertFalse(SendspinServiceType.matches("_sendspin._tcp.", SendspinServiceType.SERVER_TYPE));
        assertFalse(SendspinServiceType.matches("_sendspin-server._tcp.", SendspinServiceType.TYPE));
        assertFalse(SendspinServiceType.matches(null, SendspinServiceType.SERVER_TYPE));
        assertTrue("the old check still means a player", SendspinServiceType.isSendspin("_sendspin._tcp."));
    }
}
