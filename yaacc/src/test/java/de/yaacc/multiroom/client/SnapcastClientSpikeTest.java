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
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class SnapcastClientSpikeTest {

    @Test
    public void theSpikeIsOffByDefault() {
        assertFalse(SnapcastClientSpike.ENABLED_DEFAULT);
    }

    @Test
    public void aHostWithoutAPortGetsTheStreamPortNotTheControlPort() {
        SnapcastClientSpike.Request r = SnapcastClientSpike.check("192.168.1.10", "");
        assertEquals(SnapcastClientSpike.Outcome.OK, r.getOutcome());
        assertEquals(1704, r.getEndpoint().getPort());
        assertEquals(0, r.getLatencyMs());
    }

    @Test
    public void aGivenPortIsKept() {
        assertEquals(4000, SnapcastClientSpike.check("snap.local:4000", "10").getEndpoint().getPort());
    }

    @Test
    public void badHostsAreTypedAndNothingIsResolved() {
        assertEquals(SnapcastClientSpike.Outcome.EMPTY_HOST, SnapcastClientSpike.check("  ", "").getOutcome());
        assertEquals(SnapcastClientSpike.Outcome.EMPTY_HOST, SnapcastClientSpike.check(null, "").getOutcome());
        for (String bad : new String[]{"http://host", "user:pw@host", "host/path", "ho st", "0.0.0.0", "host:0", "host:70000",
                "224.0.0.1", "[::1", "ho_st"}) {
            assertEquals(bad, SnapcastClientSpike.Outcome.INVALID_HOST, SnapcastClientSpike.check(bad, "").getOutcome());
        }
    }

    @Test
    public void theLatencyIsParsedAndClamped() {
        assertEquals(Integer.valueOf(0), SnapcastClientSpike.parseLatency(""));
        assertEquals(Integer.valueOf(0), SnapcastClientSpike.parseLatency(null));
        assertEquals(Integer.valueOf(120), SnapcastClientSpike.parseLatency(" 120 "));
        assertEquals(Integer.valueOf(120), SnapcastClientSpike.parseLatency("+120"));
        assertEquals(Integer.valueOf(-45), SnapcastClientSpike.parseLatency("-45"));
        assertEquals(Integer.valueOf(2000), SnapcastClientSpike.parseLatency("99999"));
        assertEquals(Integer.valueOf(-2000), SnapcastClientSpike.parseLatency("-99999"));
    }

    @Test
    public void textThatIsNoWholeNumberIsRefused() {
        for (String bad : new String[]{"-", "+", "1.5", "12ms", "0x10", "1e3", "999999999999", "--1"}) {
            assertNull(bad, SnapcastClientSpike.parseLatency(bad));
        }
        assertEquals(SnapcastClientSpike.Outcome.INVALID_LATENCY, SnapcastClientSpike.check("host", "abc").getOutcome());
    }

    // ---- security review of Group 8: the gate is checked again at the start, public addresses are warned about

    @Test
    public void theEnabledSettingIsReadSafelyAndOffByDefault() {
        assertFalse(SnapcastClientSpike.isEnabled(null));
        assertFalse(SnapcastClientSpike.isEnabled((key, def) -> def));
        assertFalse("a damaged preference is off", SnapcastClientSpike.isEnabled((key, def) -> {
            throw new ClassCastException("not a boolean");
        }));
        assertEquals(true, SnapcastClientSpike.isEnabled((key, def) -> SnapcastClientSpike.KEY_ENABLED.equals(key)));
    }

    @Test
    public void onlyAddressesOutsideTheLocalRangesAreCalledPublic() {
        String[][] cases = {{"192.168.1.5", "LOCAL"}, {"10.0.0.2", "LOCAL"}, {"172.16.5.5", "LOCAL"}, {"127.0.0.1", "LOCAL"},
                {"169.254.1.1", "LOCAL"}, {"[fe80:0:0:0:0:0:0:1]", "LOCAL"}, {"[fd00:0:0:0:0:0:0:1]", "LOCAL"},
                {"8.8.8.8", "PUBLIC"}, {"100.64.0.1", "PUBLIC"}, {"[2001:db8:0:0:0:0:0:1]", "PUBLIC"},
                {"snap.local", "NAME"}, {"example.org", "NAME"}};
        for (String[] c : cases) {
            SnapcastClientSpike.Request r = SnapcastClientSpike.check(c[0], "");
            assertEquals(c[0], SnapcastClientSpike.Outcome.OK, r.getOutcome());
            assertEquals(c[0], c[1], SnapcastClientSpike.localityOf(r.getEndpoint()).name());
        }
    }

    @Test
    public void aServerOutsideTheLocalNetworkDoesNotGetTheNameOfThePhone() {
        de.yaacc.multiroom.discovery.SnapcastEndpoint local = SnapcastClientSpike.check("192.168.1.5", "").getEndpoint();
        de.yaacc.multiroom.discovery.SnapcastEndpoint remote = SnapcastClientSpike.check("8.8.8.8", "").getEndpoint();
        de.yaacc.multiroom.discovery.SnapcastEndpoint name = SnapcastClientSpike.check("snap.local", "").getEndpoint();
        assertEquals("Anna's phone", SnapcastClientSpike.helloHostName(local, "Anna's phone"));
        assertEquals("a name may resolve anywhere; it is the user's choice and it is not looked up here", "Anna's phone",
                SnapcastClientSpike.helloHostName(name, "Anna's phone"));
        assertEquals("YAACC", SnapcastClientSpike.helloHostName(remote, "Anna's phone"));
    }

    @Test
    public void theHostNameOfTheHelloIsDecidedFromTheResolvedAddress() throws Exception {
        de.yaacc.multiroom.discovery.SnapcastResolver publicLookup = new de.yaacc.multiroom.discovery.SnapcastResolver(
                host -> java.net.InetAddress.getByAddress(new byte[]{8, 8, 8, 8}), 1000);
        de.yaacc.multiroom.discovery.SnapcastResolver localLookup = new de.yaacc.multiroom.discovery.SnapcastResolver(
                host -> java.net.InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 1, 7}), 1000);
        for (String typed : new String[]{"dyn.example.org", "0x08.0x08.0x08.0x08"}) {
            SnapcastClientSpike.Request request = SnapcastClientSpike.check(typed, "");
            assertEquals(typed, SnapcastClientSpike.Outcome.OK, request.getOutcome());
            assertEquals("a name for the parser", SnapcastClientSpike.Locality.NAME,
                    SnapcastClientSpike.localityOf(request.getEndpoint()));
            assertEquals("the typed text says nothing about it", "Anna's phone",
                    SnapcastClientSpike.helloHostName(request.getEndpoint(), "Anna's phone"));
            assertEquals("the resolved address does", "YAACC", SnapcastClientSpike.helloHostName(
                    publicLookup.resolve(request.getEndpoint()), "Anna's phone"));
            assertEquals("Anna's phone", SnapcastClientSpike.helloHostName(
                    localLookup.resolve(request.getEndpoint()), "Anna's phone"));
        }
        assertEquals("YAACC", SnapcastClientSpike.helloHostName(java.net.InetAddress.getByName("::ffff:8.8.8.8"), "x"));
        assertEquals("x", SnapcastClientSpike.helloHostName(java.net.InetAddress.getByName("127.0.0.1"), "x"));
    }

    @Test
    public void theWriteAheadIsAWholeNumberOfMillisecondsClampedTo40To350() {
        assertEquals(Integer.valueOf(250), SnapcastClientSpike.parseWriteAhead(""));
        assertEquals(Integer.valueOf(250), SnapcastClientSpike.parseWriteAhead(null));
        assertEquals(Integer.valueOf(250), SnapcastClientSpike.parseWriteAhead("  "));
        assertEquals(Integer.valueOf(250), SnapcastClientSpike.parseWriteAhead(" 250 "));
        assertEquals(Integer.valueOf(40), SnapcastClientSpike.parseWriteAhead("40"));
        assertEquals(Integer.valueOf(350), SnapcastClientSpike.parseWriteAhead("350"));
        assertEquals(Integer.valueOf(40), SnapcastClientSpike.parseWriteAhead("0"));
        assertEquals(Integer.valueOf(350), SnapcastClientSpike.parseWriteAhead("99999"));
        assertEquals(Integer.valueOf(350), SnapcastClientSpike.parseWriteAhead("999999"));
        assertNull(SnapcastClientSpike.parseWriteAhead("1234567"));
        assertNull(SnapcastClientSpike.parseWriteAhead("-5"));
        assertNull(SnapcastClientSpike.parseWriteAhead("12a"));
        assertNull(SnapcastClientSpike.parseWriteAhead("1.5"));
        assertEquals(250, SnapcastClientSpike.DEFAULT_WRITE_AHEAD_MS);
        assertEquals(40, SnapcastClientSpike.clampWriteAhead(-3));
        assertEquals(350, SnapcastClientSpike.clampWriteAhead(Integer.MAX_VALUE));
    }
}
