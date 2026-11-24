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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import de.yaacc.sendspin.secure.Identity;

import org.junit.Assume;
import org.junit.Test;

import java.security.SecureRandom;

/**
 * The hub against the reference player of {@code tools/sendspin-rig} (opt-in): {@code -Dsendspin.rig=host:port} of a player started
 * with unpaired access, {@code -Dsendspin.rig.paired=host:port} of one started without it, {@code -Dsendspin.rig.seconds} for the
 * length of the tone. Skipped without the properties. The tone is real: the player's own log shows its sync error and any dropped
 * or late audio.
 */
public class HubRigTest {

    private static Endpoint endpoint(String property) {
        String rig = System.getProperty(property);
        Assume.assumeTrue("no -D" + property + "=host:port", rig != null && !rig.isEmpty());
        int colon = rig.lastIndexOf(':');
        return new Endpoint(rig.substring(0, colon), Integer.parseInt(rig.substring(colon + 1)), "/sendspin");
    }

    private static HubConnection hub(Endpoint endpoint, ServerPolicy policy) {
        return hub(endpoint, Identity.generate(new SecureRandom()), policy);
    }

    private static HubConnection hub(Endpoint endpoint, Identity identity, ServerPolicy policy) {
        return new HubConnection(endpoint, identity, policy, new Connector.Sockets(), HubClock.SYSTEM,
                new SecureRandom(), "YAACC rig test", null);
    }

    private static void await(HubConnection hub, HubConnection.Phase phase, long millis) throws Exception {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (hub.phase() != phase && hub.phase() != HubConnection.Phase.ENDED && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(phase, hub.phase());
    }

    @Test
    public void aToneReachesTheReferencePlayerAfterTheOperatorApproves() throws Exception {
        HubConnection hub = hub(endpoint("sendspin.rig"), new ServerPolicy.InMemory());
        hub.start();
        try {
            await(hub, HubConnection.Phase.NEEDS_APPROVAL, 10_000);
            assertNotNull(hub.hello());
            System.out.println("rig: player '" + hub.hello().name + "' needs approval; unpaired access " + hub.hello().unpairedAccess);
            assertTrue("nothing is played to a player that is not approved", hub.streamer() == null);
            hub.approve();
            await(hub, HubConnection.Phase.READY, 20_000);
            int rate = hub.streamFormat().sampleRate;
            System.out.println("rig: ready, stream format " + rate + " Hz, lead " + hub.leadMicros() / 1000 + " ms, capacity " + hub.capacityBytes());

            PcmStreamer streamer = hub.streamer();
            int seconds = Integer.getInteger("sendspin.rig.seconds", 8);
            int frames = rate / 50;
            long played = 0;
            byte[] chunk = new byte[frames * 4];
            for (int i = 0; i < seconds * 50; i++) {
                for (int f = 0; f < frames; f++) {
                    short sample = (short) (Math.sin(2 * Math.PI * 440 * (played + f) / rate) * 6000);
                    chunk[4 * f] = (byte) sample;
                    chunk[4 * f + 1] = (byte) (sample >> 8);
                    chunk[4 * f + 2] = (byte) sample;
                    chunk[4 * f + 3] = (byte) (sample >> 8);
                }
                played += frames;
                assertTrue("write " + i + " failed, phase " + hub.phase() + " reason " + hub.endReason(), streamer.write(chunk, 0, chunk.length));
            }
            // a seek and a pause in the middle of it all: the player must take them without the connection breaking
            streamer.discard();
            for (int i = 0; i < 50; i++) {
                assertTrue(streamer.write(chunk, 0, chunk.length));
            }
            streamer.end();
            Thread.sleep(500);
            streamer.begin();
            for (int i = 0; i < 50; i++) {
                assertTrue(streamer.write(chunk, 0, chunk.length));
            }
            assertTrue(hub.isReady());
            assertEquals("no late chunk and no gap", 0, streamer.statistics().reanchors);
            System.out.println("rig: chunks " + streamer.statistics().chunks + ", clears " + streamer.statistics().clears
                    + ", reanchors " + streamer.statistics().reanchors + ", capacity waits " + streamer.statistics().capacityWaits);
            assertTrue(hub.setVolume(30));
            Thread.sleep(300);
            assertEquals("the player reports the volume back", Integer.valueOf(30), hub.state().player.volume);
        } finally {
            hub.close();
        }
    }

    @Test
    public void aPlayerThatDoesNotAdmitUnpairedAccessIsShownAsNeedingPairing() throws Exception {
        HubConnection hub = hub(endpoint("sendspin.rig.paired"), new ServerPolicy.InMemory());
        hub.start();
        try {
            await(hub, HubConnection.Phase.PAIRING_REQUIRED, 10_000);
            assertFalse(hub.hello().unpairedAccess);
            assertTrue(hub.streamer() == null);
        } finally {
            hub.close();
        }
    }

    /**
     * {@code -Dsendspin.rig.paired=host:port} of a player started with unpaired access off and {@code -Dsendspin.rig.token} with
     * its pairing token: the first connection pairs it (Pairing PSK, then the re-handshake to the new long-term PSK) and plays; the
     * second connection of the same server needs no token any more.
     */
    @Test
    public void aTokenPairsAPlayerThatRefusesUnpairedAccessAndTheNextConnectionNeedsNoToken() throws Exception {
        Endpoint endpoint = endpoint("sendspin.rig.paired");
        String text = System.getProperty("sendspin.rig.token");
        Assume.assumeTrue("no -Dsendspin.rig.token", text != null && !text.isEmpty());
        de.yaacc.sendspin.secure.PairingToken token = de.yaacc.sendspin.secure.PairingToken.decode(text);
        assertNotNull("the token does not decode", token);
        ServerPolicy.InMemory policy = new ServerPolicy.InMemory();
        policy.addToken(token.clientId(), token.pairingPsk());
        Identity server = Identity.generate(new SecureRandom());

        HubConnection first = hub(endpoint, server, policy);
        first.start();
        try {
            await(first, HubConnection.Phase.READY, 20_000);
            assertTrue("paired", first.isPaired());
            assertTrue(policy.isPaired(token.clientId()));
            assertTrue("the token is used up", policy.pairingPsk(token.clientId()) == null);
            System.out.println("rig: paired '" + first.hello().name + "', " + first.streamFormat().sampleRate + " Hz");
            PcmStreamer streamer = first.streamer();
            int rate = first.streamFormat().sampleRate;
            int frames = rate / 50;
            byte[] chunk = new byte[frames * 4];
            for (int i = 0; i < 100; i++) {
                assertTrue("write " + i, streamer.write(chunk, 0, chunk.length));
            }
            streamer.end();
        } finally {
            first.close();
        }

        HubConnection second = hub(endpoint, server, policy);
        second.start();
        try {
            await(second, HubConnection.Phase.READY, 20_000);
            assertTrue("the long-term PSK is used", second.isPaired());
            assertEquals(HubConnection.PairingFailure.NONE, second.pairingFailure());
            System.out.println("rig: second connection ready under the long-term PSK");
        } finally {
            second.close();
        }
    }
}
