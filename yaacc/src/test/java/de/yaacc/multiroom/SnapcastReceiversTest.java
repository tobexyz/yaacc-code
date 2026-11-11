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
package de.yaacc.multiroom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import de.yaacc.multiroom.control.MonotonicClock;
import de.yaacc.multiroom.control.RetryScheduler;
import de.yaacc.multiroom.control.SnapcastClient;
import de.yaacc.multiroom.control.SnapcastGroup;
import de.yaacc.multiroom.control.SnapcastServerInfo;
import de.yaacc.multiroom.control.SnapcastServerStatus;
import de.yaacc.multiroom.control.SnapcastVolume;
import de.yaacc.multiroom.discovery.SnapcastDirectory;
import de.yaacc.multiroom.discovery.SnapcastEndpoint;
import de.yaacc.multiroom.discovery.SnapcastServerKey;

/**
 * The receiver side of the Snapcast servers: connections are opened lazily (only while a page is visible, or for
 * a retained or chosen server), closed after a grace period, the last known groups stay listed while a server is
 * offline, and a server or group that is gone disappears. Plain JVM with fake links, a manual clock and scheduler.
 */
public class SnapcastReceiversTest {

    private static final long GRACE = 30_000L;
    private static final SnapcastGroupDevice.Labels LABELS = new SnapcastGroupDevice.Labels("Snapcast group", "offline");

    private static final class FakeLink implements SnapcastServerLink {
        final SnapcastServerKey key;
        SnapcastServerStatus status;
        boolean online;
        boolean started;
        boolean closed;
        int retries;
        final List<Listener> listeners = new ArrayList<>();

        FakeLink(SnapcastServerKey key) {
            this.key = key;
        }

        @Override
        public SnapcastServerKey getKey() {
            return key;
        }

        @Override
        public SnapcastServerStatus getStatus() {
            return status;
        }

        @Override
        public boolean isOnline() {
            return online;
        }

        @Override
        public void addListener(Listener listener) {
            listeners.add(listener);
        }

        @Override
        public void removeListener(Listener listener) {
            listeners.remove(listener);
        }

        @Override
        public void start() {
            started = true;
        }

        @Override
        public void retry() {
            retries++;
        }

        /** Like {@code SnapcastSession.close()}: tells the listeners once that the link is offline, then drops them. */
        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            online = false;
            for (Listener listener : new ArrayList<>(listeners)) {
                listener.onLinkChanged(this);
            }
            listeners.clear();
        }

        void update(SnapcastServerStatus newStatus, boolean nowOnline) {
            status = newStatus;
            online = nowOnline;
            for (Listener listener : new ArrayList<>(listeners)) {
                listener.onLinkChanged(this);
            }
        }
    }

    private static final class ManualScheduler implements RetryScheduler {
        final List<Runnable> tasks = new ArrayList<>();
        final List<Long> delays = new ArrayList<>();

        @Override
        public void schedule(Runnable task, long delayMillis) {
            tasks.add(task);
            delays.add(delayMillis);
        }

        void runAll() {
            List<Runnable> snapshot = new ArrayList<>(tasks);
            tasks.clear();
            delays.clear();
            for (Runnable task : snapshot) {
                task.run();
            }
        }
    }

    private final AtomicLong now = new AtomicLong(1_000);
    private final MonotonicClock clock = now::get;
    private final ManualScheduler scheduler = new ManualScheduler();
    private final List<FakeLink> links = new ArrayList<>();
    private final AtomicInteger changes = new AtomicInteger();
    private SnapcastDirectory directory;
    private SnapcastReceivers receivers;

    @Before
    public void setUp() {
        directory = new SnapcastDirectory(clock, GRACE);
        receivers = new SnapcastReceivers(directory, endpoint -> {
            FakeLink link = new FakeLink(endpoint.toKey());
            links.add(link);
            return link;
        }, scheduler, () -> LABELS, GRACE);
        receivers.addChangeListener(changes::incrementAndGet);
    }

    private static InetAddress ip(int a, int b, int c, int d) throws Exception {
        return InetAddress.getByAddress(new byte[]{(byte) a, (byte) b, (byte) c, (byte) d});
    }

    private void server(int last) throws Exception {
        directory.serviceFound("S" + last, ip(10, 0, 0, last), 1705);
    }

    private static SnapcastServerKey key(int last) {
        return SnapcastEndpoint.parse("10.0.0." + last).toKey();
    }

    private static SnapcastClient client(String id, String name) {
        return new SnapcastClient(id, name, "", true, new SnapcastVolume(50, false), 0);
    }

    private static SnapcastGroup group(String id, String name, String... clientNames) {
        List<SnapcastClient> clients = new ArrayList<>();
        for (String clientName : clientNames) {
            clients.add(client(id + "-" + clientName, clientName));
        }
        return new SnapcastGroup(id, name, false, "Default", clients);
    }

    private static SnapcastServerStatus status(SnapcastGroup... groups) {
        return new SnapcastServerStatus(new SnapcastServerInfo("", "", "", 0, 0), Arrays.asList(groups), Collections.emptyList());
    }

    private FakeLink linkOf(int last) {
        for (FakeLink link : links) {
            if (link.key.equals(key(last)) && !link.closed) {
                return link;
            }
        }
        return null;
    }

    // ---- lazy connections

    @Test
    public void nothingIsOpenedBeforeAPageIsVisible() throws Exception {
        server(2);
        server(3);

        assertTrue(links.isEmpty());
        assertTrue(receivers.groups().isEmpty());
    }

    @Test
    public void aVisiblePageOpensALinkToEveryListedServer() throws Exception {
        server(2);
        server(3);

        receivers.pageStarted();

        assertEquals(2, links.size());
        assertTrue(links.get(0).started);
        assertTrue(links.get(1).started);
    }

    @Test
    public void aServerThatAppearsWhileThePageIsVisibleGetsALink() throws Exception {
        receivers.pageStarted();
        server(2);

        assertEquals(1, links.size());
        assertTrue(links.get(0).started);
    }

    @Test
    public void aServerThatAppearsWhileNoPageIsVisibleGetsNoLink() throws Exception {
        server(2);
        assertTrue(links.isEmpty());
    }

    @Test
    public void aLinkIsOpenedOncePerServer() throws Exception {
        server(2);
        receivers.pageStarted();
        receivers.pageStarted();
        server(2);

        assertEquals(1, links.size());
    }

    @Test
    public void aSecondVisibleAnnouncementOfAnOfflineServerRetriesIt() throws Exception {
        server(2);
        receivers.pageStarted();
        receivers.pageStopped();
        receivers.pageStarted();

        assertEquals("the same link, asked to connect now", 1, links.size());
        assertEquals(1, links.get(0).retries);
    }

    @Test
    public void thePageComingBackInTheGraceKeepsTheLinks() throws Exception {
        server(2);
        receivers.pageStarted();
        receivers.pageStopped();
        receivers.pageStarted();
        scheduler.runAll();

        assertFalse(links.get(0).closed);
    }

    @Test
    public void afterTheGraceTheLinksAreClosed() throws Exception {
        server(2);
        receivers.pageStarted();
        scheduler.runAll(); // the expiry tick of the visible page
        receivers.pageStopped();
        long closeDelay = scheduler.delays.get(scheduler.delays.size() - 1);
        assertEquals(GRACE, closeDelay);

        scheduler.runAll();

        assertTrue(links.get(0).closed);
    }

    @Test
    public void twoPagesNeedTwoStops() throws Exception {
        server(2);
        receivers.pageStarted();
        receivers.pageStarted();
        receivers.pageStopped();
        scheduler.runAll();

        assertFalse(links.get(0).closed);

        receivers.pageStopped();
        scheduler.runAll();

        assertTrue(links.get(0).closed);
    }

    @Test
    public void aStopWithoutAStartDoesNothing() throws Exception {
        server(2);
        receivers.pageStopped();
        receivers.pageStarted();
        scheduler.runAll();

        assertFalse(links.get(0).closed);
    }

    @Test
    public void aRetainedServerStaysOpenWithoutAPage() throws Exception {
        server(2);
        server(3);
        receivers.retain(key(2));
        assertEquals(1, links.size());

        receivers.pageStarted();
        receivers.pageStopped();
        scheduler.runAll();

        assertFalse("retained", linkOf(2) == null);
        assertNull("not retained: closed", linkOf(3));

        receivers.release(key(2));
        scheduler.runAll();
        assertNull(linkOf(2));
    }

    @Test
    public void retainingAServerThatIsNotListedOpensNothing() throws Exception {
        receivers.retain(key(9));
        assertTrue(links.isEmpty());
    }

    // ---- wake

    @Test
    public void wakeOpensOnlyTheListedServersOfTheChosenGroups() throws Exception {
        server(2);
        server(3);
        String chosen = SnapcastGroupDevice.idOf(key(2), "g1");
        String chosenButNotListed = SnapcastGroupDevice.idOf(key(9), "g1");

        receivers.wake(Arrays.asList(chosen, chosenButNotListed, "LOCAL_UID", "snapcast:garbage", "uuid:abc", null));

        assertEquals(1, links.size());
        assertEquals(key(2), links.get(0).key);
        assertTrue(links.get(0).started);
    }

    @Test
    public void aWokenServerIsClosedAgainAfterTheGrace() throws Exception {
        server(2);
        receivers.wake(Collections.singletonList(SnapcastGroupDevice.idOf(key(2), "g1")));

        scheduler.runAll();

        assertTrue(links.get(0).closed);
    }

    @Test
    public void wakingWithNoSnapcastIdsDoesNothing() throws Exception {
        server(2);
        receivers.wake(Collections.singletonList("LOCAL_UID"));
        receivers.wake(Collections.<String>emptyList());
        receivers.wake(null);

        assertTrue(links.isEmpty());
        assertTrue(scheduler.tasks.isEmpty());
    }

    // ---- the groups

    @Test
    public void groupsComeFromTheStatusOfTheLinksSortedByName() throws Exception {
        server(2);
        server(3);
        receivers.pageStarted();
        linkOf(2).update(status(group("g-b", "bedroom", "x"), group("g-a", "Attic", "y")), true);
        linkOf(3).update(status(group("g-c", "Cellar", "z")), true);

        List<SnapcastGroupDevice> groups = receivers.groups();

        assertEquals(3, groups.size());
        assertEquals("Attic (Snapcast)", groups.get(0).getDetails().getFriendlyName());
        assertEquals("bedroom (Snapcast)", groups.get(1).getDetails().getFriendlyName());
        assertEquals("Cellar (Snapcast)", groups.get(2).getDetails().getFriendlyName());
    }

    @Test
    public void aGroupIsFoundByItsIdAndOnlyByIt() throws Exception {
        server(2);
        receivers.pageStarted();
        linkOf(2).update(status(group("g-a", "Attic", "y")), true);

        SnapcastGroupDevice found = receivers.findGroup(SnapcastGroupDevice.idOf(key(2), "g-a"));

        assertNotNull(found);
        assertEquals("Attic (Snapcast)", found.getDetails().getFriendlyName());
        assertNull(receivers.findGroup(SnapcastGroupDevice.idOf(key(2), "g-zzz")));
        assertNull(receivers.findGroup(SnapcastGroupDevice.idOf(key(9), "g-a")));
        assertNull(receivers.findGroup("snapcast:garbage"));
        assertNull(receivers.findGroup("LOCAL_UID"));
        assertNull(receivers.findGroup(null));
    }

    @Test
    public void aGroupThatVanishesFromTheStatusDisappears() throws Exception {
        server(2);
        receivers.pageStarted();
        linkOf(2).update(status(group("g-a", "Attic", "y"), group("g-b", "Bath", "z")), true);
        assertEquals(2, receivers.groups().size());

        linkOf(2).update(status(group("g-b", "Bath", "z")), true);

        assertEquals(1, receivers.groups().size());
        assertNull(receivers.findGroup(SnapcastGroupDevice.idOf(key(2), "g-a")));
    }

    @Test
    public void anOfflineServersLastGroupsStayListedAndAreMarked() throws Exception {
        server(2);
        receivers.pageStarted();
        linkOf(2).update(status(group("g-a", "Attic", "y")), true);
        assertFalse(receivers.groups().get(0).isOffline());

        linkOf(2).update(linkOf(2).status, false);

        assertEquals(1, receivers.groups().size());
        assertTrue(receivers.groups().get(0).isOffline());
        assertEquals("Attic (Snapcast, offline)", receivers.groups().get(0).getDetails().getFriendlyName());
    }

    @Test
    public void afterTheLinkIsClosedTheLastGroupsStayListedAsOffline() throws Exception {
        server(2);
        receivers.pageStarted();
        linkOf(2).update(status(group("g-a", "Attic", "y")), true);
        receivers.pageStopped();
        scheduler.runAll();
        assertTrue(links.get(0).closed);

        assertEquals(1, receivers.groups().size());
        assertTrue(receivers.groups().get(0).isOffline());
        assertNotNull("a stored selection still finds it", receivers.findGroup(SnapcastGroupDevice.idOf(key(2), "g-a")));
    }

    @Test
    public void aServerThatLeavesTheDirectoryTakesItsGroupsAndItsLinkWithIt() throws Exception {
        server(2);
        receivers.pageStarted();
        linkOf(2).update(status(group("g-a", "Attic", "y")), true);
        directory.serviceLost("S2");
        assertEquals("still listed during the grace", 1, receivers.groups().size());

        now.addAndGet(GRACE);

        assertTrue(receivers.groups().isEmpty());
        assertNull(receivers.findGroup(SnapcastGroupDevice.idOf(key(2), "g-a")));
        assertTrue(links.get(0).closed);
    }

    @Test
    public void aManualServerKeepsItsGroupsAndLink() throws Exception {
        directory.setManualServers(Collections.singletonList(SnapcastEndpoint.parse("10.0.0.2")));
        receivers.pageStarted();
        linkOf(2).update(status(group("g-a", "Attic", "y")), true);

        now.addAndGet(GRACE * 10);

        assertEquals(1, receivers.groups().size());
        assertFalse(links.get(0).closed);
    }

    @Test
    public void groupsWithAnUnsafeIdAreLeftOut() throws Exception {
        server(2);
        receivers.pageStarted();
        linkOf(2).update(status(group("g:evil", "Evil", "y"), group("g-ok", "Good", "z")), true);

        assertEquals(1, receivers.groups().size());
        assertEquals("Good (Snapcast)", receivers.groups().get(0).getDetails().getFriendlyName());
    }

    @Test
    public void atMost64GroupsAreListed() throws Exception {
        server(2);
        server(3);
        receivers.pageStarted();
        List<SnapcastGroup> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(group("a" + i, "A" + i, "x"));
        }
        List<SnapcastGroup> more = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            more.add(group("b" + i, "B" + i, "x"));
        }
        linkOf(2).update(status(many.toArray(new SnapcastGroup[0])), true);
        linkOf(3).update(status(more.toArray(new SnapcastGroup[0])), true);

        assertEquals(SnapcastReceivers.MAX_GROUPS, receivers.groups().size());
    }

    @Test
    public void aLinkWithoutAStatusYetListsNothing() throws Exception {
        server(2);
        receivers.pageStarted();

        assertTrue(receivers.groups().isEmpty());
    }

    // ---- change notifications

    @Test
    public void listenersAreToldWhenALinkOrTheServerListChanges() throws Exception {
        server(2);
        receivers.pageStarted();
        int before = changes.get();

        linkOf(2).update(status(group("g-a", "Attic", "y")), true);
        assertTrue(changes.get() > before);

        int afterLink = changes.get();
        server(3);
        assertTrue(changes.get() > afterLink);
    }

    @Test
    public void aRemovedListenerIsNotToldAgain() throws Exception {
        AtomicInteger count = new AtomicInteger();
        SnapcastReceivers.ChangeListener listener = count::incrementAndGet;
        receivers.addChangeListener(listener);
        server(2);
        int seen = count.get();
        assertTrue(seen > 0);

        receivers.removeChangeListener(listener);
        server(3);

        assertEquals(seen, count.get());
    }

    @Test
    public void aLinkThatCouldNotBeOpenedLeavesTheServerListedWithoutGroups() throws Exception {
        SnapcastReceivers failing = new SnapcastReceivers(directory, endpoint -> {
            throw new IllegalStateException("no network");
        }, scheduler, () -> LABELS, GRACE);
        server(2);

        failing.pageStarted();

        assertTrue(failing.groups().isEmpty());
    }

    @Test
    public void closeEverythingReleasesAllLinks() throws Exception {
        server(2);
        server(3);
        receivers.pageStarted();

        receivers.shutdown();

        for (FakeLink link : links) {
            assertTrue(link.closed);
        }
        assertTrue(receivers.groups().isEmpty());
    }

    // ---- a stored selection before the first status (a cold start)

    @Test
    public void aGroupOfAListedServerWithoutAStatusIsPending() throws Exception {
        directory.setManualServers(Collections.singletonList(SnapcastEndpoint.parse("10.0.0.2")));

        assertTrue(receivers.isPending(SnapcastGroupDevice.idOf(key(2), "g-a")));
        assertNull("it is not a device yet", receivers.findGroup(SnapcastGroupDevice.idOf(key(2), "g-a")));
        assertTrue(receivers.groups().isEmpty());
    }

    @Test
    public void aGroupIsNotPendingOnceTheStatusIsThere() throws Exception {
        server(2);
        receivers.pageStarted();
        String present = SnapcastGroupDevice.idOf(key(2), "g-a");
        String absent = SnapcastGroupDevice.idOf(key(2), "g-zzz");
        assertTrue(receivers.isPending(present));

        linkOf(2).update(status(group("g-a", "Attic", "y")), true);

        assertFalse("found: it is a device now", receivers.isPending(present));
        assertNotNull(receivers.findGroup(present));
        assertEquals(1, receivers.groups().size());
        assertFalse("the server answered and does not have it: gone", receivers.isPending(absent));
    }

    @Test
    public void aGroupOfAServerThatIsNotListedIsNotPending() throws Exception {
        server(2);

        assertFalse(receivers.isPending(SnapcastGroupDevice.idOf(key(9), "g-a")));
    }

    @Test
    public void aGroupIsNotPendingAnyMoreWhenItsServerLeaves() throws Exception {
        server(2);
        String id = SnapcastGroupDevice.idOf(key(2), "g-a");
        assertTrue(receivers.isPending(id));

        directory.serviceLost("S2");
        assertTrue("still listed during the grace", receivers.isPending(id));
        now.addAndGet(GRACE);

        assertFalse(receivers.isPending(id));
    }

    @Test
    public void somethingThatIsNotAGroupIdIsNeverPending() throws Exception {
        server(2);

        assertFalse(receivers.isPending(null));
        assertFalse(receivers.isPending("LOCAL_UID"));
        assertFalse(receivers.isPending("uuid:abc"));
        assertFalse(receivers.isPending("snapcast:garbage"));
        assertFalse(receivers.isPending("snapcast:10.0.0.2:1705:bad id"));
    }

    @Test
    public void noSourceHasAPendingGroup() {
        assertFalse(SnapcastGroupSource.NONE.isPending("snapcast:10.0.0.2:1705:g-a"));
    }

    @Test
    public void aRememberedServerIsConnectedWhenAPageStarts() throws Exception {
        directory.setRememberedServers(Collections.singletonList(SnapcastEndpoint.parse("10.0.0.2")));
        String id = SnapcastGroupDevice.idOf(key(2), "g-a");
        assertTrue(receivers.isPending(id));
        assertTrue("nothing is connected before a page or a wake", links.isEmpty());

        receivers.pageStarted();

        assertEquals(1, links.size());
        assertTrue(links.get(0).started);
        linkOf(2).update(status(group("g-a", "Attic", "y")), true);
        assertNotNull(receivers.findGroup(id));
    }

    @Test
    public void wakingTheChosenGroupConnectsARememberedServerWithoutAPage() throws Exception {
        directory.setRememberedServers(Collections.singletonList(SnapcastEndpoint.parse("10.0.0.2")));

        receivers.wake(Collections.singletonList(SnapcastGroupDevice.idOf(key(2), "g-a")));

        assertEquals(1, links.size());
        assertTrue(links.get(0).started);
    }

    // ---- one device per group while nothing changed

    @Test
    public void repeatedSnapshotsGiveTheSameInstances() throws Exception {
        server(2);
        receivers.pageStarted();
        linkOf(2).update(status(group("g-a", "Attic", "y"), group("g-b", "Bath", "z")), true);

        List<SnapcastGroupDevice> first = receivers.groups();
        List<SnapcastGroupDevice> second = receivers.groups();

        assertEquals(2, first.size());
        assertSame(first.get(0), second.get(0));
        assertSame(first.get(1), second.get(1));
        assertSame("the UDN object is the same, DeviceDiffCallback compares it with ==",
                first.get(0).getIdentity().getUdn(), second.get(0).getIdentity().getUdn());
    }

    @Test
    public void aGroupFoundByIdIsTheInstanceOfTheList() throws Exception {
        server(2);
        receivers.pageStarted();
        linkOf(2).update(status(group("g-a", "Attic", "y")), true);

        assertSame(receivers.groups().get(0), receivers.findGroup(SnapcastGroupDevice.idOf(key(2), "g-a")));
    }

    @Test
    public void aStatusThatChangesNothingVisibleKeepsTheInstances() throws Exception {
        server(2);
        receivers.pageStarted();
        linkOf(2).update(status(group("g-a", "Attic", "y")), true);
        SnapcastGroupDevice before = receivers.groups().get(0);

        linkOf(2).update(status(group("g-a", "Attic", "y")), true); // a new status object, the same group

        assertSame(before, receivers.groups().get(0));
    }

    @Test
    public void aChangeGivesANewInstanceForThatGroupOnly() throws Exception {
        server(2);
        receivers.pageStarted();
        linkOf(2).update(status(group("g-a", "Attic", "y"), group("g-b", "Bath", "z")), true);
        SnapcastGroupDevice attic = receivers.groups().get(0);
        SnapcastGroupDevice bath = receivers.groups().get(1);

        linkOf(2).update(status(group("g-a", "Attic", "y", "y2"), group("g-b", "Bath", "z")), true);

        List<SnapcastGroupDevice> after = receivers.groups();
        assertNotSame("a member was added", attic, after.get(0));
        assertEquals(2, after.get(0).getClientCount());
        assertSame("the other group is untouched", bath, after.get(1));
    }

    @Test
    public void aNewNameAGoingOfflineAndANewStreamEachGiveANewInstance() throws Exception {
        server(2);
        receivers.pageStarted();
        linkOf(2).update(status(group("g-a", "Attic", "y")), true);
        SnapcastGroupDevice first = receivers.groups().get(0);

        linkOf(2).update(status(group("g-a", "Loft", "y")), true);
        SnapcastGroupDevice renamed = receivers.groups().get(0);
        assertNotSame(first, renamed);
        assertEquals("Loft (Snapcast)", renamed.getDetails().getFriendlyName());

        linkOf(2).update(linkOf(2).status, false);
        SnapcastGroupDevice offline = receivers.groups().get(0);
        assertNotSame(renamed, offline);
        assertTrue(offline.isOffline());

        linkOf(2).update(status(new SnapcastGroup("g-a", "Loft", false, "Other stream", Collections.singletonList(client("c", "y")))), true);
        SnapcastGroupDevice otherStream = receivers.groups().get(0);
        assertNotSame(offline, otherStream);
        assertEquals("Other stream", otherStream.getStreamId());
    }

    @Test
    public void anOfflineGroupKeepsItsInstance() throws Exception {
        server(2);
        receivers.pageStarted();
        linkOf(2).update(status(group("g-a", "Attic", "y")), true);
        linkOf(2).update(linkOf(2).status, false);
        SnapcastGroupDevice offline = receivers.groups().get(0);

        assertSame(offline, receivers.groups().get(0));
    }

    @Test
    public void aVanishedGroupIsForgottenAndComesBackAsANewInstance() throws Exception {
        server(2);
        receivers.pageStarted();
        linkOf(2).update(status(group("g-a", "Attic", "y"), group("g-b", "Bath", "z")), true);
        SnapcastGroupDevice attic = receivers.groups().get(0);
        linkOf(2).update(status(group("g-b", "Bath", "z")), true);
        assertEquals(1, receivers.groups().size());

        linkOf(2).update(status(group("g-a", "Attic", "y"), group("g-b", "Bath", "z")), true);

        assertNotSame("the old snapshot was evicted", attic, receivers.groups().get(0));
    }

    @Test
    public void aLanguageChangeGivesNewInstances() throws Exception {
        final String[] offline = {"offline"};
        SnapcastReceivers localized = new SnapcastReceivers(directory, endpoint -> {
            FakeLink link = new FakeLink(endpoint.toKey());
            links.add(link);
            return link;
        }, scheduler, () -> new SnapcastGroupDevice.Labels("Snapcast group", offline[0]), GRACE);
        server(2);
        localized.pageStarted();
        linkOf(2).update(status(group("g-a", "Attic", "y")), true);
        linkOf(2).update(linkOf(2).status, false);
        SnapcastGroupDevice english = localized.groups().get(0);

        offline[0] = "abgeschaltet";

        SnapcastGroupDevice german = localized.groups().get(0);
        assertNotSame(english, german);
        assertEquals("Attic (Snapcast, abgeschaltet)", german.getDetails().getFriendlyName());
    }

    @Test
    public void shutdownForgetsTheInstances() throws Exception {
        server(2);
        receivers.pageStarted();
        linkOf(2).update(status(group("g-a", "Attic", "y")), true);
        assertEquals(1, receivers.groups().size());

        receivers.shutdown();

        assertTrue(receivers.groups().isEmpty());
    }

    @Test
    public void theFirst64GroupsInServerOrderAreListed() throws Exception {
        server(2);
        server(3);
        receivers.pageStarted();
        List<SnapcastGroup> many = new ArrayList<>();
        List<SnapcastGroup> more = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(group("a" + i, "A" + i, "x"));
            more.add(group("b" + i, "B" + i, "x"));
        }
        linkOf(2).update(status(many.toArray(new SnapcastGroup[0])), true);
        linkOf(3).update(status(more.toArray(new SnapcastGroup[0])), true);

        List<SnapcastGroupDevice> listed = receivers.groups();

        assertEquals(SnapcastReceivers.MAX_GROUPS, listed.size());
        int fromFirstServer = 0;
        for (SnapcastGroupDevice device : listed) {
            if (device.getServerKey().equals(key(2))) {
                fromFirstServer++;
            }
        }
        assertEquals("all 40 of the first server, then 24 of the second", 40, fromFirstServer);
    }

    // ---- the list of servers is read under the lock (no stale snapshot)

    @Test
    public void aServerThatLeavesWhileThePageStartsGetsNoLink() throws Exception {
        // X is typed in; Y was lost and its grace is over, so reading the list sweeps Y and tells the listeners
        directory.setManualServers(Collections.singletonList(SnapcastEndpoint.parse("10.0.0.2")));
        server(3);
        directory.serviceLost("S3");
        now.addAndGet(GRACE);
        final boolean[] removed = {false};
        // runs first (before the receivers' own listener) on the thread that sweeps: X is taken off the manual list
        // right after the sweep has built its answer - the answer is out of date when pageStarted gets it
        directory.addListener(() -> {
            if (!removed[0]) {
                removed[0] = true;
                directory.setManualServers(Collections.<SnapcastEndpoint>emptyList());
            }
        });

        receivers.pageStarted();

        assertTrue("the server is not listed any more: no link to it", links.isEmpty());
    }

    @Test
    public void aServerThatLeavesWhileItIsRetainedGetsNoLink() throws Exception {
        directory.setManualServers(Collections.singletonList(SnapcastEndpoint.parse("10.0.0.2")));
        server(3);
        directory.serviceLost("S3");
        now.addAndGet(GRACE);
        final boolean[] removed = {false};
        directory.addListener(() -> {
            if (!removed[0]) {
                removed[0] = true;
                directory.setManualServers(Collections.<SnapcastEndpoint>emptyList());
            }
        });

        receivers.retain(key(2));

        assertTrue(links.isEmpty());
    }

    @Test
    public void aServerThatLeavesWhileItsGroupIsWokenGetsNoLink() throws Exception {
        directory.setManualServers(Collections.singletonList(SnapcastEndpoint.parse("10.0.0.2")));
        server(3);
        directory.serviceLost("S3");
        now.addAndGet(GRACE);
        final boolean[] removed = {false};
        directory.addListener(() -> {
            if (!removed[0]) {
                removed[0] = true;
                directory.setManualServers(Collections.<SnapcastEndpoint>emptyList());
            }
        });

        receivers.wake(Collections.singletonList(SnapcastGroupDevice.idOf(key(2), "g-a")));

        assertTrue(links.isEmpty());
    }

    // ---- the state of one group for the rows and the sheet

    @Test
    public void theStateOfAGroupHasTheGroupTheStatusAndTheEndpoint() throws Exception {
        server(2);
        receivers.pageStarted();
        linkOf(2).update(status(group("g-a", "Attic", "y")), true);

        SnapcastReceivers.GroupState state = receivers.stateOf(SnapcastGroupDevice.idOf(key(2), "g-a"));

        assertNotNull(state);
        assertEquals("g-a", state.getGroup().getId());
        assertEquals(1, state.getStatus().getGroups().size());
        assertTrue(state.isOnline());
        assertEquals("10.0.0.2:1705", state.getEndpointText());
        assertEquals(key(2), state.getServerKey());
        assertEquals("g-a", state.getGroupId());
    }

    @Test
    public void anUnreachableServersGroupIsNotRemovedAtOnceItIsMarkedOffline() throws Exception {
        server(2);
        receivers.pageStarted();
        linkOf(2).update(status(group("g-a", "Attic", "y")), true);

        linkOf(2).update(linkOf(2).status, false);
        now.addAndGet(5 * GRACE); // the server is still listed (a manual or remembered server, or just seen)
        SnapcastReceivers.GroupState state = receivers.stateOf(SnapcastGroupDevice.idOf(key(2), "g-a"));

        assertFalse(state.isOnline());
        assertNotNull("the last known group is still there", state.getGroup());
        assertEquals(1, receivers.groups().size());
    }

    @Test
    public void theStateOfAGroupThatTheServerNoLongerReportsHasNoGroup() throws Exception {
        server(2);
        receivers.pageStarted();
        linkOf(2).update(status(group("g-a", "Attic", "y")), true);
        linkOf(2).update(status(group("g-b", "Cellar", "z")), true);

        SnapcastReceivers.GroupState state = receivers.stateOf(SnapcastGroupDevice.idOf(key(2), "g-a"));

        assertNotNull(state);
        assertNull(state.getGroup());
        assertNotNull(state.getStatus());
    }

    @Test
    public void theStateOfAGroupOfAServerThatHasNotAnsweredYetHasNoStatus() throws Exception {
        server(2);
        receivers.pageStarted();

        SnapcastReceivers.GroupState state = receivers.stateOf(SnapcastGroupDevice.idOf(key(2), "g-a"));

        assertNotNull(state);
        assertNull(state.getGroup());
        assertNull(state.getStatus());
    }

    @Test
    public void thereIsNoStateForAnIdThatIsMalformedOrOfAServerThatIsNotListed() throws Exception {
        server(2);
        receivers.pageStarted();

        assertNull(receivers.stateOf(null));
        assertNull(receivers.stateOf("not-a-snapcast-id"));
        assertNull(receivers.stateOf("snapcast:10.0.0.2:1705:bad id"));
        assertNull(receivers.stateOf(SnapcastGroupDevice.idOf(key(9), "g-a")));
    }

    // ---- a server that leaves while a link is retained or woken (security review W1, S46)

    @Test
    public void aServerThatLeavesTheDirectoryHasItsRetainedLinkClosedAndForgotten() throws Exception {
        server(2);
        receivers.retain(key(2));
        FakeLink link = linkOf(2);

        directory.discoveryStopped(); // the server was only discovered: it leaves at once

        assertTrue("the link is closed although it was retained: the owner is told through it", link.closed);
        assertNull(receivers.getLink(key(2)));
    }

    @Test
    public void theClosedLinkOfARemovedServerTellsItsListenersItIsOffline() throws Exception {
        server(2);
        receivers.retain(key(2));
        FakeLink link = linkOf(2);
        final List<Boolean> seen = new ArrayList<>();
        link.addListener(l -> seen.add(l.isOnline()));
        link.online = true;

        directory.discoveryStopped();

        assertEquals(1, seen.size());
        assertFalse(seen.get(0));
    }

    @Test
    public void aServerThatIsAddedAgainAfterItsRemovalDoesNotKeepTheOldRetainOrWake() throws Exception {
        server(2);
        receivers.retain(key(2));
        receivers.wake(Collections.singletonList(SnapcastGroupDevice.idOf(key(2), "g-a")));
        assertEquals(1, links.size());
        directory.discoveryStopped();
        assertTrue(links.get(0).closed);

        server(2); // found again before any grace has run, no page is visible

        assertEquals("no link without a page, a player or a woken group", 1, links.size());
    }

    @Test
    public void releasingAKeyThatWasForgottenDoesNothing() throws Exception {
        server(2);
        receivers.retain(key(2));
        directory.discoveryStopped();
        int scheduled = scheduler.tasks.size();

        receivers.release(key(2));

        assertEquals(scheduled, scheduler.tasks.size());
    }

    @Test
    public void aLinkThatIsClosedByTheIdleCloseTellsItsOtherListenersExactlyOnce() throws Exception {
        server(2);
        receivers.pageStarted();
        FakeLink link = linkOf(2);
        final int[] told = {0};
        link.addListener(l -> told[0]++);
        receivers.pageStopped();

        scheduler.runAll();

        assertTrue(link.closed);
        assertEquals("the one listener of the test, once; the receivers took theirs off before the close", 1, told[0]);
    }
}
