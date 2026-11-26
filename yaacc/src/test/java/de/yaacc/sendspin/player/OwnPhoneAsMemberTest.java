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
package de.yaacc.sendspin.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import de.yaacc.multiroom.protocol.MicroClock;
import de.yaacc.sendspin.SendspinClientPairings;
import de.yaacc.sendspin.StringSetStore;
import de.yaacc.sendspin.secure.Identity;
import de.yaacc.sendspin.session.Envelope;
import de.yaacc.sendspin.session.FakePlayer;
import de.yaacc.sendspin.session.SecureChannel;
import de.yaacc.sendspin.session.SendspinMessages;
import de.yaacc.sendspin.session.ServerHandshake;

import org.junit.After;
import org.junit.Test;

import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** The phone as a member of a group: the app's own hub plays on its own player over the loopback address. */
public class OwnPhoneAsMemberTest {

    private static final class MapStore implements StringSetStore, SendspinPlayerHost.Store {
        final Map<String, Set<String>> map = new HashMap<>();

        @Override
        public Set<String> get(String key) {
            Set<String> f = map.get(key);
            return f == null ? new HashSet<String>() : new HashSet<>(f);
        }

        @Override
        public void put(String key, Set<String> values) {
            map.put(key, new HashSet<>(values));
        }

        @Override
        public int volume() {
            return 70;
        }

        @Override
        public boolean muted() {
            return false;
        }

        @Override
        public int outputDelayMs() {
            return 0;
        }

        @Override
        public void save(int v, boolean m, int d) {
        }

        @Override
        public String lastPlaybackServer() {
            return null;
        }

        @Override
        public void setLastPlaybackServer(String serverId) {
        }
    }

    private final MapStore store = new MapStore();
    private final SendspinPlayerHost host = new SendspinPlayerHost(Identity.generate(new SecureRandom()),
            new SendspinClientPairings(store, new SecureRandom()), () -> false /* the setting: no unpaired servers */, () -> false, "Phone",
            (offsets, clock) -> null, MicroClock.monotonic(), new SecureRandom(), store, null);
    private FakePlayer link;

    @After
    public void tearDown() throws Exception {
        host.close();
        if (link != null) {
            link.close();
        }
    }

    /** What the player's hello says about unpaired servers when the connection is local (or not). */
    private boolean helloAdmitsUnpaired(boolean local) throws Exception {
        link = new FakePlayer().open();
        Identity server = Identity.generate(new SecureRandom());
        Future<ServerHandshake.Result> s = link.onHub(
                () -> ServerHandshake.perform(link.playerWs, server, id -> ServerHandshake.PskChoice.sentinel(), new SecureRandom()));
        host.accept(link.hubLink(), local);
        SecureChannel channel = s.get(5, TimeUnit.SECONDS).channel;
        channel.sendJson(SendspinMessages.serverHello("Server"));
        while (true) {
            SecureChannel.Inbound in = channel.receive();
            if (!in.isJson()) {
                continue;
            }
            Envelope e = in.envelope();
            if ("client/hello".equals(e.type())) {
                return SendspinMessages.parseClientHello(e.payload()).unpairedAccess;
            }
        }
    }

    @Test(timeout = 30000)
    public void aLocalConnectionNeedsNoPairingEvenWhenThePlayerAdmitsNoUnpairedServers() throws Exception {
        assertTrue(helloAdmitsUnpaired(true));
    }

    @Test(timeout = 30000)
    public void aConnectionFromTheNetworkStillNeedsPairing() throws Exception {
        assertFalse(helloAdmitsUnpaired(false));
    }

    @Test
    public void theLoopbackAddressIsReadButTheOtherAddressesOfThePhoneAreNot() throws Exception {
        assertTrue(SendspinPlayerService.acceptsAddress(InetAddress.getByName("127.0.0.1")));
        assertFalse("not the local network", SendspinPlayerService.acceptsAddress(InetAddress.getByName("8.8.8.8")));
        assertFalse(SendspinPlayerService.acceptsAddress(null));
    }

    @Test
    public void thePortOfThePlayerIsKnownWhileItRuns() {
        assertEquals(0, SendspinPlayerRuntime.port());
        SendspinPlayerRuntime.setPort(8928);
        assertEquals(8928, SendspinPlayerRuntime.port());
        SendspinPlayerRuntime.setPort(0);
        assertNull(SendspinPlayerRuntime.host());
    }
}
