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
package de.yaacc.sendspin.control;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import de.yaacc.sendspin.SendspinPolicy;
import de.yaacc.sendspin.StringSetStore;
import de.yaacc.sendspin.client.ControllerSession;
import de.yaacc.sendspin.hub.Connector;
import de.yaacc.sendspin.hub.Endpoint;
import de.yaacc.sendspin.hub.HubClock;
import de.yaacc.sendspin.hub.HubConnection;
import de.yaacc.sendspin.secure.Identity;
import de.yaacc.sendspin.session.FakePlayer;
import de.yaacc.sendspin.session.WsConnection;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The controller role of the server end to end on the JVM: the real server connection ({@link HubConnection}) with the controller
 * host, against the real client side ({@link ControllerSession}) over a fake line. A controller sees the state of the app's playback and
 * its commands reach the (fake) player.
 */
public class ControllerHostTest {

    private static final class Group implements PlaybackBridge.Group {
        final List<String> log = new ArrayList<>();
        volatile boolean playing = true;
        volatile int volume = 40;
        volatile String title = "Song one";
        volatile long position = 5_000L;
        volatile String art = "http://192.168.0.2/one.jpg";

        @Override public String id() { return "leader"; }
        @Override public String name() { return "Kitchen"; }
        @Override public boolean isPlaying() { return playing; }
        @Override public long activitySerial() { return 1; }
        @Override public int itemCount() { return 3; }
        @Override public long durationMs() { return 180_000L; }
        @Override public long positionMs() { return position; }
        @Override public int volume() { return volume; }
        @Override public boolean isMuted() { return false; }
        @Override public boolean supportsVolume() { return true; }
        @Override public boolean supportsMute() { return true; }
        @Override public boolean supportsPlayModes() { return true; }
        @Override public String repeat() { return "off"; }
        @Override public boolean isShuffle() { return false; }
        @Override public String title() { return title; }
        @Override public String artist() { return "Artist"; }
        @Override public String album() { return "Album"; }
        @Override public String artworkUrl() { return art; }
        @Override public int year() { return 0; }
        @Override public int track() { return 0; }
        @Override public void play() { log.add("play"); playing = true; }
        @Override public void pause() { log.add("pause"); playing = false; }
        @Override public void stop() { log.add("stop"); playing = false; }
        @Override public void next() { log.add("next"); title = "Song two"; }
        @Override public void previous() { log.add("previous"); }
        @Override public void setVolume(int p) { log.add("volume " + p); volume = p; }
        @Override public void setMuted(boolean m) { log.add("mute " + m); }
        @Override public void setRepeat(String m) { log.add("repeat " + m); }
        @Override public void setShuffle(boolean on) { log.add("shuffle " + on); }
        @Override public void seekTo(long ms) { log.add("seek " + ms); position = ms; }
    }

    private static final class MapStore implements StringSetStore {
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
    }

    private final Group group = new Group();
    private final AtomicBoolean unpairedAllowed = new AtomicBoolean(true);
    /** The fake encoder: an image of 100 kB (two messages) whose bytes tell which cover and which size it is. */
    private final List<String> encoded = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final ArtworkEncoder encoder = (url, format, width, height) -> {
        encoded.add(url + " " + format + " " + width + "x" + height);
        byte[] image = new byte[100_000];
        java.util.Arrays.fill(image, (byte) url.length());
        return image;
    };
    private final ControllerHost host = new ControllerHost(
            new ControllerLogic(() -> java.util.Collections.<PlaybackBridge.Group>singletonList(group), "Phone", new SecureRandom()),
            HubClock.SYSTEM, unpairedAllowed::get, 4, encoder);
    private final Identity serverKeys = Identity.generate(new SecureRandom());
    private final Identity clientKeys = Identity.generate(new SecureRandom());
    private final List<AutoCloseable> toClose = new ArrayList<>();

    @After
    public void tearDown() throws Exception {
        for (AutoCloseable c : toClose) {
            c.close();
        }
        host.close();
    }

    private ControllerSession connect(final SendspinPolicy policy) throws Exception {
        final FakePlayer fake = new FakePlayer().open();
        toClose.add(fake);
        Connector serverSide = (endpoint, random) -> fake.hubLink();
        HubConnection hub = new HubConnection(new Endpoint("127.0.0.1", 8927, "/sendspin"), serverKeys, policy, serverSide, HubClock.SYSTEM,
                new SecureRandom(), "Phone", null);
        hub.setControllerHost(host);
        toClose.add(hub::close);
        Connector.Link clientEnd = new Connector.Link() {
            @Override
            public WsConnection ws() {
                return fake.playerWs;
            }

            @Override
            public void close() throws IOException {
                fake.close();
            }
        };
        ControllerSession session = new ControllerSession(new Endpoint("127.0.0.1", 8927, "/sendspin"), clientKeys, true,
                (endpoint, random) -> clientEnd, HubClock.SYSTEM, new SecureRandom(), "Remote", null);
        toClose.add(session::close);
        hub.start();
        session.start();
        return session;
    }

    private static void waitFor(String what, java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(what, condition.getAsBoolean());
    }

    @Test(timeout = 60000)
    public void aControllerSeesWhatPlaysAndItsCommandsReachThePlayer() throws Exception {
        ControllerSession c = connect(new SendspinPolicy(new MapStore()));
        waitFor("active", () -> c.phase() == ControllerSession.Phase.ACTIVE && c.controller() != null && c.metadata() != null);
        assertEquals("Kitchen", c.group().groupName);
        assertTrue(c.group().playing);
        assertEquals(43, c.group().groupId.length());
        assertEquals(40, c.controller().volume);
        assertTrue(c.controller().supports("next"));
        assertTrue(c.controller().supports("seek"));
        assertEquals(Long.valueOf(180_000L), c.controller().seekMaxMs);
        assertEquals("Song one", c.metadata().title);
        assertEquals("Artist", c.metadata().artist);
        assertEquals(1000, c.metadata().progress.playbackSpeed);

        assertTrue(c.pause());
        waitFor("pause reached the player", () -> group.log.contains("pause"));
        waitFor("the group is told it stopped", () -> !c.group().playing);
        assertTrue(c.setVolume(65));
        waitFor("volume", () -> group.log.contains("volume 65") && c.controller().volume == 65);
        assertTrue(c.next());
        waitFor("the next item is shown", () -> "Song two".equals(c.metadata().title));
        assertTrue(c.seek(90_000L));
        waitFor("the jump is told", () -> group.log.contains("seek 90000") && c.positionMs() >= 89_000L);
    }

    @Test(timeout = 60000)
    public void aChangeInTheAppReachesTheControllerWithoutACommand() throws Exception {
        ControllerSession c = connect(new SendspinPolicy(new MapStore()));
        waitFor("active", () -> c.phase() == ControllerSession.Phase.ACTIVE && c.metadata() != null);
        group.title = "Changed in the app";
        host.refreshAll();
        waitFor("the new title", () -> "Changed in the app".equals(c.metadata().title));
    }

    @Test(timeout = 60000)
    public void anUnpairedControllerIsRefusedWhenTheSwitchIsOff() throws Exception {
        unpairedAllowed.set(false);
        ControllerSession c = connect(new SendspinPolicy(new MapStore()));
        Thread.sleep(1500);
        assertFalse("no controller role is active", c.phase() == ControllerSession.Phase.ACTIVE);
        assertTrue(group.log.isEmpty());
        assertNotNull(c.phase());
    }

    @Test(timeout = 60000)
    public void aNinthControllerIsNotAdmittedBeyondTheLimit() throws Exception {
        // the limit is the host's: four sessions at most
        List<ControllerSession> sessions = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ControllerSession s = connect(new SendspinPolicy(new MapStore()));
            sessions.add(s);
            waitFor("active " + i, () -> s.phase() == ControllerSession.Phase.ACTIVE);
        }
        ControllerSession fifth = connect(new SendspinPolicy(new MapStore()));
        Thread.sleep(1500);
        assertFalse(fifth.phase() == ControllerSession.Phase.ACTIVE);
    }

    @Test(timeout = 60000)
    public void theCoverOfWhatPlaysArrivesOverTheArtworkChannelAndFollowsTheItem() throws Exception {
        ControllerSession c = connect(new SendspinPolicy(new MapStore()));
        waitFor("the cover", () -> c.artwork() != null);
        byte[] first = c.artwork();
        assertEquals(100_000, first.length);
        assertEquals((byte) "http://192.168.0.2/one.jpg".length(), first[0]);
        assertTrue("the encoder was asked for the size the client declared: " + encoded, encoded.get(0).startsWith("http://192.168.0.2/one.jpg jpeg "));
        group.art = "http://192.168.0.2/two-longer.jpg";
        host.refreshAll();
        waitFor("the next cover", () -> c.artwork() != null && c.artwork()[0] == (byte) "http://192.168.0.2/two-longer.jpg".length());
        group.art = null; // an item without a cover clears the channel
        host.refreshAll();
        waitFor("cleared", () -> c.artwork() == null);
    }

    @Test(timeout = 90000)
    public void aControllerWhoseTokenWasEnteredBeforeItConnectsIsPairedAndServedEvenThoughUnpairedDevicesAreRefused() throws Exception {
        unpairedAllowed.set(false);
        de.yaacc.sendspin.SendspinClientPairings clientPairings = new de.yaacc.sendspin.SendspinClientPairings(new MapStore(), new SecureRandom());
        String token = clientPairings.token(clientKeys); // what the controlling phone shows
        SendspinPolicy policy = new SendspinPolicy(new MapStore());
        assertEquals(SendspinPolicy.PairOutcome.STARTED, policy.addDeviceToken(token)); // entered on this phone before it connects
        assertEquals(SendspinPolicy.PairOutcome.NOT_A_TOKEN, policy.addDeviceToken("hello"));

        final FakePlayer fake = new FakePlayer().open();
        toClose.add(fake);
        HubConnection hub = new HubConnection(new Endpoint("127.0.0.1", 8927, "/sendspin"), serverKeys, policy, (e, r) -> fake.hubLink(),
                HubClock.SYSTEM, new SecureRandom(), "Phone", null);
        hub.setControllerHost(host);
        toClose.add(hub::close);
        Connector.Link clientEnd = new Connector.Link() {
            @Override
            public WsConnection ws() {
                return fake.playerWs;
            }

            @Override
            public void close() throws IOException {
                fake.close();
            }
        };
        ControllerSession c = new ControllerSession(new Endpoint("127.0.0.1", 8927, "/sendspin"), clientKeys, false, (e, r) -> clientEnd,
                HubClock.SYSTEM, new SecureRandom(), "Remote", null, clientPairings);
        toClose.add(c::close);
        hub.start();
        c.start();
        waitFor("paired and active", () -> c.phase() == ControllerSession.Phase.ACTIVE && c.controller() != null);
        assertTrue(policy.isPaired(clientKeys.id()));
        assertTrue(c.pause());
        waitFor("the command of a paired controller reaches the player", () -> group.log.contains("pause"));
    }
}
