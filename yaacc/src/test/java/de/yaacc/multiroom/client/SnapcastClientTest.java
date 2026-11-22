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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SnapcastClientTest {

    @Test
    public void theClientIsOffByDefault() {
        assertFalse(SnapcastClient.ENABLED_DEFAULT);
    }

    @Test
    public void aHostWithoutAPortGetsTheStreamPortNotTheControlPort() {
        SnapcastClient.Request r = SnapcastClient.check("192.168.1.10", "");
        assertEquals(SnapcastClient.Outcome.OK, r.getOutcome());
        assertEquals(1704, r.getEndpoint().getPort());
        assertEquals(0, r.getLatencyMs());
    }

    @Test
    public void aGivenPortIsKept() {
        assertEquals(4000, SnapcastClient.check("snap.local:4000", "10").getEndpoint().getPort());
    }

    @Test
    public void badHostsAreTypedAndNothingIsResolved() {
        assertEquals(SnapcastClient.Outcome.EMPTY_HOST, SnapcastClient.check("  ", "").getOutcome());
        assertEquals(SnapcastClient.Outcome.EMPTY_HOST, SnapcastClient.check(null, "").getOutcome());
        for (String bad : new String[]{"http://host", "user:pw@host", "host/path", "ho st", "0.0.0.0", "host:0", "host:70000",
                "224.0.0.1", "[::1", "ho_st"}) {
            assertEquals(bad, SnapcastClient.Outcome.INVALID_HOST, SnapcastClient.check(bad, "").getOutcome());
        }
    }

    @Test
    public void theLatencyIsParsedAndClamped() {
        assertEquals(Integer.valueOf(0), SnapcastClient.parseLatency(""));
        assertEquals(Integer.valueOf(0), SnapcastClient.parseLatency(null));
        assertEquals(Integer.valueOf(120), SnapcastClient.parseLatency(" 120 "));
        assertEquals(Integer.valueOf(120), SnapcastClient.parseLatency("+120"));
        assertEquals(Integer.valueOf(-45), SnapcastClient.parseLatency("-45"));
        assertEquals(Integer.valueOf(2000), SnapcastClient.parseLatency("99999"));
        assertEquals(Integer.valueOf(-2000), SnapcastClient.parseLatency("-99999"));
    }

    @Test
    public void textThatIsNoWholeNumberIsRefused() {
        for (String bad : new String[]{"-", "+", "1.5", "12ms", "0x10", "1e3", "999999999999", "--1"}) {
            assertNull(bad, SnapcastClient.parseLatency(bad));
        }
        assertEquals(SnapcastClient.Outcome.INVALID_LATENCY, SnapcastClient.check("host", "abc").getOutcome());
    }

    // ---- security review of Group 8: the gate is checked again at the start, public addresses are warned about

    @Test
    public void theEnabledSettingIsReadSafelyAndOffByDefault() {
        assertFalse(SnapcastClient.isEnabled(null));
        assertFalse(SnapcastClient.isEnabled((key, def) -> def));
        assertFalse("a damaged preference is off", SnapcastClient.isEnabled((key, def) -> {
            throw new ClassCastException("not a boolean");
        }));
        assertEquals(true, SnapcastClient.isEnabled((key, def) -> SnapcastClient.KEY_ENABLED.equals(key)));
    }

    @Test
    public void onlyAddressesOutsideTheLocalRangesAreCalledPublic() {
        String[][] cases = {{"192.168.1.5", "LOCAL"}, {"10.0.0.2", "LOCAL"}, {"172.16.5.5", "LOCAL"}, {"127.0.0.1", "LOCAL"},
                {"169.254.1.1", "LOCAL"}, {"[fe80:0:0:0:0:0:0:1]", "LOCAL"}, {"[fd00:0:0:0:0:0:0:1]", "LOCAL"},
                {"8.8.8.8", "PUBLIC"}, {"100.64.0.1", "PUBLIC"}, {"[2001:db8:0:0:0:0:0:1]", "PUBLIC"},
                {"snap.local", "NAME"}, {"example.org", "NAME"}};
        for (String[] c : cases) {
            SnapcastClient.Request r = SnapcastClient.check(c[0], "");
            assertEquals(c[0], SnapcastClient.Outcome.OK, r.getOutcome());
            assertEquals(c[0], c[1], SnapcastClient.localityOf(r.getEndpoint()).name());
        }
    }

    @Test
    public void aServerOutsideTheLocalNetworkDoesNotGetTheNameOfThePhone() {
        de.yaacc.multiroom.discovery.SnapcastEndpoint local = SnapcastClient.check("192.168.1.5", "").getEndpoint();
        de.yaacc.multiroom.discovery.SnapcastEndpoint remote = SnapcastClient.check("8.8.8.8", "").getEndpoint();
        de.yaacc.multiroom.discovery.SnapcastEndpoint name = SnapcastClient.check("snap.local", "").getEndpoint();
        assertEquals("Anna's phone", SnapcastClient.helloHostName(local, "Anna's phone"));
        assertEquals("a name may resolve anywhere; it is the user's choice and it is not looked up here", "Anna's phone",
                SnapcastClient.helloHostName(name, "Anna's phone"));
        assertEquals("YAACC", SnapcastClient.helloHostName(remote, "Anna's phone"));
    }

    @Test
    public void theHostNameOfTheHelloIsDecidedFromTheResolvedAddress() throws Exception {
        de.yaacc.multiroom.discovery.SnapcastResolver publicLookup = new de.yaacc.multiroom.discovery.SnapcastResolver(
                host -> java.net.InetAddress.getByAddress(new byte[]{8, 8, 8, 8}), 1000);
        de.yaacc.multiroom.discovery.SnapcastResolver localLookup = new de.yaacc.multiroom.discovery.SnapcastResolver(
                host -> java.net.InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 1, 7}), 1000);
        for (String typed : new String[]{"dyn.example.org", "0x08.0x08.0x08.0x08"}) {
            SnapcastClient.Request request = SnapcastClient.check(typed, "");
            assertEquals(typed, SnapcastClient.Outcome.OK, request.getOutcome());
            assertEquals("a name for the parser", SnapcastClient.Locality.NAME,
                    SnapcastClient.localityOf(request.getEndpoint()));
            assertEquals("the typed text says nothing about it", "Anna's phone",
                    SnapcastClient.helloHostName(request.getEndpoint(), "Anna's phone"));
            assertEquals("the resolved address does", "YAACC", SnapcastClient.helloHostName(
                    publicLookup.resolve(request.getEndpoint()), "Anna's phone"));
            assertEquals("Anna's phone", SnapcastClient.helloHostName(
                    localLookup.resolve(request.getEndpoint()), "Anna's phone"));
        }
        assertEquals("YAACC", SnapcastClient.helloHostName(java.net.InetAddress.getByName("::ffff:8.8.8.8"), "x"));
        assertEquals("x", SnapcastClient.helloHostName(java.net.InetAddress.getByName("127.0.0.1"), "x"));
    }

    @Test
    public void theVersionOfTheHelloIsTheServerNameAndTheAppVersion() {
        String[][] cases = {
                // name, app version, public address, expected
                {"Anna's phone", "6.1.0", "false", "Anna's phone 6.1.0"},
                {"Yaacc", "6.1.0", "false", "Yaacc 6.1.0"},
                {null, "6.1.0", "false", "YAACC 6.1.0"},
                {"", "6.1.0", "false", "YAACC 6.1.0"},
                {"   ", "6.1.0", "false", "YAACC 6.1.0"},
                {"Wohnzimmer Küche", "6.1.0-beta.2", "false", "Wohnzimmer Küche 6.1.0-beta.2"},
                {"a\r\nb\u0000c\u007fd\u202ee", "6.1.0", "false", "abcde 6.1.0"},
                {"<b>x</b> \"q\" a&b `c` \\d", "6.1.0", "false", "bx/b q ab c d 6.1.0"},
                {"\u0001\u0002\u202e\u2028", "6.1.0", "false", "YAACC 6.1.0"},
                {"a   b \t c", "6.1.0", "false", "a b c 6.1.0"},
                {"Anna's phone", "6.1.0", "true", "YAACC 6.1.0"},
                {null, "6.1.0", "true", "YAACC 6.1.0"},
                {"Yaacc", "6.1.0", "true", "YAACC 6.1.0"},
                {"Anna's phone", null, "false", "Anna's phone"},
                {"Anna's phone", "", "false", "Anna's phone"},
                {"Anna's phone", null, "true", "YAACC"},
                {"Anna's phone", "6.1.0\n<x>", "false", "Anna's phone 6.1.0x"},
        };
        for (String[] c : cases) {
            assertEquals(java.util.Arrays.toString(c), c[3],
                    SnapcastClient.helloVersion(c[0], c[1], Boolean.parseBoolean(c[2])));
        }
    }

    @Test
    public void theVersionOfTheHelloIsAtMost40CharactersAndKeepsTheAppVersion() {
        String tooLong = "A very long name of a server that goes on and on and on";
        assertEquals("the name is cut, the version stays whole and no space is left at the cut",
                "A very long name of a server that 6.1.0", SnapcastClient.helloVersion(tooLong, "6.1.0", false));
        String exact = "1234567890123456789012345678901234"; // 34 characters + space + "6.1.0" = 40
        assertEquals(exact + " 6.1.0", SnapcastClient.helloVersion(exact, "6.1.0", false));
        assertEquals(exact + " 6.1.0", SnapcastClient.helloVersion(exact + "x", "6.1.0", false));
        assertEquals("x 6.1.0", SnapcastClient.helloVersion("x", "6.1.0", false));
        assertEquals("no version: the name alone is cut to 40", 40,
                SnapcastClient.helloVersion(tooLong, null, false).length());
        // an absurd app version cannot push the result over the limit either
        String absurd = "1.2.3-" + "z".repeat(80);
        assertTrue(SnapcastClient.helloVersion("x", absurd, false).length() <= 40);
        assertTrue(SnapcastClient.helloVersion(tooLong, absurd, false).length() <= 40);
        assertTrue(SnapcastClient.helloVersion(tooLong, absurd, true).length() <= 40);
        assertEquals("YAACC", SnapcastClient.helloVersion("   ", "", false));
    }

    @Test
    public void theWriteAheadIsAWholeNumberOfMillisecondsClampedTo40To350() {
        assertEquals(Integer.valueOf(250), SnapcastClient.parseWriteAhead(""));
        assertEquals(Integer.valueOf(250), SnapcastClient.parseWriteAhead(null));
        assertEquals(Integer.valueOf(250), SnapcastClient.parseWriteAhead("  "));
        assertEquals(Integer.valueOf(250), SnapcastClient.parseWriteAhead(" 250 "));
        assertEquals(Integer.valueOf(40), SnapcastClient.parseWriteAhead("40"));
        assertEquals(Integer.valueOf(350), SnapcastClient.parseWriteAhead("350"));
        assertEquals(Integer.valueOf(40), SnapcastClient.parseWriteAhead("0"));
        assertEquals(Integer.valueOf(350), SnapcastClient.parseWriteAhead("99999"));
        assertEquals(Integer.valueOf(350), SnapcastClient.parseWriteAhead("999999"));
        assertNull(SnapcastClient.parseWriteAhead("1234567"));
        assertNull(SnapcastClient.parseWriteAhead("-5"));
        assertNull(SnapcastClient.parseWriteAhead("12a"));
        assertNull(SnapcastClient.parseWriteAhead("1.5"));
        assertEquals(250, SnapcastClient.DEFAULT_WRITE_AHEAD_MS);
        assertEquals(40, SnapcastClient.clampWriteAhead(-3));
        assertEquals(350, SnapcastClient.clampWriteAhead(Integer.MAX_VALUE));
    }

    /**
     * The check box "FLAC stall guard" reaches the configuration of the engine (the service builds it with this).
     */
    @Test
    public void theFlacGuardChoiceReachesTheEngineConfiguration() {
        assertEquals(true, SnapcastClient.engineConfig(250, true).flacTimeGuard);
        assertEquals(false, SnapcastClient.engineConfig(250, false).flacTimeGuard);
        assertEquals("the write-ahead is clamped on the way", 350, SnapcastClient.engineConfig(9_999, true).maxAheadMs);
        assertEquals(true, SnapcastClient.engineConfig(250, true).smoothCorrections);
    }

    @Test
    public void thePcmStatusHasNoFlacLines() {
        EngineStatus pcm = new EngineStatus();
        pcm.codec = "pcm";
        String text = SnapcastClientStatusText.of(DeviceRestartTest.viewOf(pcm), 0);
        assertEquals(text, false, text.contains("flac positions") || text.contains("flac stall guard") || text.contains("flac stream shifted"));
        EngineStatus flac = new EngineStatus();
        flac.codec = "flac";
        flac.flacGuardState = 2;
        String flacText = SnapcastClientStatusText.of(DeviceRestartTest.viewOf(flac), 0);
        assertEquals(flacText, true, flacText.contains("flac stall guard: inactive"));
    }
}
