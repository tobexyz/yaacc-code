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
package de.yaacc.sendspin.hub;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.yaacc.sendspin.secure.Identity;

import org.junit.Assume;
import org.junit.Test;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * A group of two players of the reference player rig (opt-in): {@code -Dsendspin.rig=host:port} and {@code -Dsendspin.rig.second=host:port}
 * of two players started with unpaired access (different ports). One timeline goes to both; the players' own logs show their sync
 * error and any dropped or late audio. Skipped without the properties.
 */
public class HubGroupRigTest {

    private static Endpoint endpoint(String property) {
        String rig = System.getProperty(property);
        Assume.assumeTrue("no -D" + property + "=host:port", rig != null && !rig.isEmpty());
        int colon = rig.lastIndexOf(':');
        return new Endpoint(rig.substring(0, colon), Integer.parseInt(rig.substring(colon + 1)), "/sendspin");
    }

    private static HubConnection connect(Endpoint endpoint) throws Exception {
        HubConnection hub = new HubConnection(endpoint, Identity.generate(new SecureRandom()), new ServerPolicy.InMemory(),
                new Connector.Sockets(), HubClock.SYSTEM, new SecureRandom(), "YAACC group rig test", null);
        hub.start();
        await(hub, HubConnection.Phase.NEEDS_APPROVAL);
        hub.approve();
        await(hub, HubConnection.Phase.AVAILABLE);
        assertTrue(hub.startPlayback());
        await(hub, HubConnection.Phase.READY);
        return hub;
    }

    private static void await(HubConnection hub, HubConnection.Phase phase) throws Exception {
        long deadline = System.nanoTime() + 20_000L * 1_000_000L;
        while (hub.phase() != phase && hub.phase() != HubConnection.Phase.ENDED && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(phase, hub.phase());
    }

    private static void tone(PcmStreamer group, int rate, int seconds, long[] played) {
        int frames = rate / 50;
        byte[] chunk = new byte[frames * 4];
        for (int i = 0; i < seconds * 50; i++) {
            for (int f = 0; f < frames; f++) {
                short sample = (short) (Math.sin(2 * Math.PI * 440 * (played[0] + f) / rate) * 6000);
                chunk[4 * f] = (byte) sample;
                chunk[4 * f + 1] = (byte) (sample >> 8);
                chunk[4 * f + 2] = (byte) sample;
                chunk[4 * f + 3] = (byte) (sample >> 8);
            }
            played[0] += frames;
            assertTrue("write failed", group.write(chunk, 0, chunk.length));
        }
    }

    @Test
    public void twoPlayersPlayOneToneInSyncAndTakeALateJoinASeekAndAPause() throws Exception {
        Endpoint first = endpoint("sendspin.rig");
        Endpoint second = endpoint("sendspin.rig.second");
        HubConnection a = connect(first);
        HubConnection b = connect(second);
        try {
            List<Set<Integer>> rates = Arrays.asList(a.supportedRates(), b.supportedRates());
            int rate = GroupFormat.commonRate(rates);
            System.out.println("rig group: rates " + rates + ", common " + rate + " Hz; leads " + a.leadMicros() / 1000 + " and "
                    + b.leadMicros() / 1000 + " ms");
            assertTrue("a common rate", rate > 0);
            int seconds = Integer.getInteger("sendspin.rig.seconds", 8);
            PcmStreamer group = new PcmStreamer(HubClock.SYSTEM, rate);
            PcmStreamer.Member memberA = group.addMember(a, a);
            long[] played = {0};
            tone(group, rate, 3, played); // the first player alone
            PcmStreamer.Member memberB = group.addMember(b, b); // the second joins while the stream runs
            tone(group, rate, seconds, played);
            group.discard(); // a seek: stream/clear to both
            tone(group, rate, 2, played);
            group.end(); // a pause
            Thread.sleep(500);
            group.begin();
            tone(group, rate, 3, played);
            group.removeMember(memberB); // the second leaves, the first goes on
            tone(group, rate, 2, played);
            assertFalse(group.hasFailed());
            assertFalse(group.hasFailed(memberA));
            assertFalse(group.hasFailed(memberB));
            assertTrue(a.isReady());
            assertTrue(b.isReady());
            System.out.println("rig group: chunks " + group.statistics().chunks + ", clears " + group.statistics().clears + ", reanchors "
                    + group.statistics().reanchors + ", capacity waits " + group.statistics().capacityWaits);
            assertTrue(a.setVolume(30));
            assertTrue(b.setVolume(60));
            Thread.sleep(300);
            // both players of the rig share one PulseAudio sink and so one system volume: only that a volume is reported is checked
            assertTrue(a.state().player.volume != null && b.state().player.volume != null);
        } finally {
            a.close();
            b.close();
        }
    }
}
