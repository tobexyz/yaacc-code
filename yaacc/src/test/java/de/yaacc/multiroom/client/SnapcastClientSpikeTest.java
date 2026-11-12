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
}
