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
package de.yaacc.fcast.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

/** Task 2.3 (the pure part): when to ping and when to give up. */
public class FCastHeartbeatTest {

    @Test
    public void nothingHappensWhileThereIsLife() {
        FCastHeartbeat heartbeat = new FCastHeartbeat(0, 5_000, 10_000);
        assertEquals(FCastHeartbeat.Action.NONE, heartbeat.tick(0));
        assertEquals(FCastHeartbeat.Action.NONE, heartbeat.tick(4_999));
    }

    @Test
    public void aPingIsAskedOnceAfterTheSilence() {
        FCastHeartbeat heartbeat = new FCastHeartbeat(0, 5_000, 10_000);
        assertEquals(FCastHeartbeat.Action.PING, heartbeat.tick(5_000));
        assertEquals("once per silence", FCastHeartbeat.Action.NONE, heartbeat.tick(5_250));
        assertEquals(FCastHeartbeat.Action.NONE, heartbeat.tick(9_999));
    }

    @Test
    public void tenSecondsOfSilenceIsDead() {
        FCastHeartbeat heartbeat = new FCastHeartbeat(0, 5_000, 10_000);
        heartbeat.tick(5_000);
        assertEquals(FCastHeartbeat.Action.DEAD, heartbeat.tick(10_000));
        assertEquals(FCastHeartbeat.Action.DEAD, heartbeat.tick(60_000));
    }

    @Test
    public void anyPacketStartsTheCountAgainAndAllowsAnotherPing() {
        FCastHeartbeat heartbeat = new FCastHeartbeat(0, 5_000, 10_000);
        assertEquals(FCastHeartbeat.Action.PING, heartbeat.tick(5_000));
        heartbeat.onPacket(7_000);
        assertEquals(FCastHeartbeat.Action.NONE, heartbeat.tick(11_999));
        assertEquals(FCastHeartbeat.Action.PING, heartbeat.tick(12_000));
        assertEquals(FCastHeartbeat.Action.DEAD, heartbeat.tick(17_000));
    }

    @Test
    public void aLongSilenceIsDeadEvenWithoutAnEarlierTick() {
        assertEquals(FCastHeartbeat.Action.DEAD, new FCastHeartbeat(100, 5_000, 10_000).tick(10_100));
    }

    @Test
    public void theTimesMustBeInOrder() {
        for (long[] bad : new long[][]{{0, 10}, {5, 5}, {10, 5}, {-1, 5}}) {
            try {
                new FCastHeartbeat(0, bad[0], bad[1]);
                fail();
            } catch (IllegalArgumentException expected) {
                // as wanted
            }
        }
    }
}
