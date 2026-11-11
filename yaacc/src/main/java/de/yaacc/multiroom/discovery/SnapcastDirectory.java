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
package de.yaacc.multiroom.discovery;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import de.yaacc.multiroom.control.MonotonicClock;
import de.yaacc.multiroom.control.SnapcastDisplayText;

/**
 * The snapservers YAACC knows: the ones found by mDNS and the ones the user typed in, merged by control endpoint
 * ({@link SnapcastServerKey}). Pure and thread safe; the clock is injected.
 *
 * <ul>
 *   <li><b>Merge.</b> One endpoint is one entry, however often and under however many service names it is seen, and
 *   whether it was found or typed. (A manual entry written as a host name is a different endpoint from the address
 *   mDNS reports; to merge them, type the address.)</li>
 *   <li><b>Loss.</b> When the last service name of a discovered entry is lost, the entry stays for a grace period
 *   (so that a server that restarts, or an mDNS hiccup, does not make its receivers flicker out of the list and out
 *   of the stored selection) and is then removed. A sighting inside the grace period cancels the loss. A manual entry
 *   is never removed by what discovery does.</li>
 *   <li><b>Remembered.</b> The server of a selected group is remembered across a restart
 *   ({@link #setRememberedServers}): it is listed from the start, without being announced, and is not aged out by
 *   time (nothing is scanned outside the page lifetime, so nothing could confirm it). When discovery starts again it
 *   gets the same grace as any discovered entry: seen in that time it stays, otherwise it goes.</li>
 *   <li><b>Cap.</b> At most {@link #MAX_SERVERS} entries. A lost entry makes room first; manual entries may also
 *   take the place of a discovered one, never the other way round.</li>
 *   <li><b>Trust.</b> A discovered address must pass {@link SnapcastAddressFilter}. Service names come from the
 *   network: they are kept to match "lost" events and as display text (cleaned and cut), never as an identity.</li>
 * </ul>
 */
public final class SnapcastDirectory {

    /** The most servers listed (discovered and manual together). */
    public static final int MAX_SERVERS = 16;
    /** The most service names remembered, so that a flood of names does not grow the directory. */
    public static final int MAX_SERVICE_NAMES = 32;
    /** How long a lost server stays listed. */
    public static final long DEFAULT_GRACE_MILLIS = 30_000L;

    /**
     * The lowest port taken from an announcement. Any host of the network (or an app on the phone) can announce a
     * service, and a port below 1024 is never a Snapcast control port in practice (the default is 1705): the phone
     * would be made to open connections to system services (security review S18). A server the user types in may use
     * any port.
     */
    public static final int MIN_DISCOVERED_PORT = 1024;

    private static final int MAX_DISPLAY_NAME = 64;
    private static final int MAX_SERVICE_NAME = 256;

    /** Told when the list of servers changed; called on the thread that made the change, without a lock held. */
    public interface Listener {
        void onServersChanged();
    }

    /** One server of the list. Immutable. */
    public static final class Entry {
        private final SnapcastEndpoint endpoint;
        private final String displayName;
        private final boolean manual;
        private final boolean discovered;
        private final boolean lost;
        private final boolean remembered;

        Entry(SnapcastEndpoint endpoint, String displayName, boolean manual, boolean discovered, boolean lost,
              boolean remembered) {
            this.endpoint = endpoint;
            this.displayName = displayName;
            this.manual = manual;
            this.discovered = discovered;
            this.lost = lost;
            this.remembered = remembered;
        }

        public SnapcastServerKey getKey() {
            return endpoint.toKey();
        }

        /** Where to connect; carries the address of a discovered server. */
        public SnapcastEndpoint getEndpoint() {
            return endpoint;
        }

        /** The name the network gave the service, as display text (cleaned and cut); may be empty. */
        public String getDisplayName() {
            return displayName;
        }

        /** The user typed this server in. */
        public boolean isManual() {
            return manual;
        }

        /** The server is announced on the network right now. */
        public boolean isDiscovered() {
            return discovered;
        }

        /** Discovered once and no longer announced; still listed for the grace period. */
        public boolean isLost() {
            return lost;
        }

        /** The server holds a selected group and was remembered from an earlier run (see {@link #setRememberedServers}). */
        public boolean isRemembered() {
            return remembered;
        }
    }

    private static final class Node {
        SnapcastEndpoint endpoint;
        String displayName = "";
        boolean manual;
        boolean remembered;
        final Set<String> services = new HashSet<>();
        long lostAt = -1;
        final long order;

        Node(SnapcastEndpoint endpoint, long order) {
            this.endpoint = endpoint;
            this.order = order;
        }
    }

    private final MonotonicClock clock;
    private final long graceMillis;
    private final Object lock = new Object();
    private final Map<SnapcastServerKey, Node> nodes = new LinkedHashMap<>();
    private final Map<String, SnapcastServerKey> byServiceName = new HashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private long nextOrder;
    // false between discoveryStopped() and the next discoveryRestarted(); guarded by lock. A directory that was never
    // told to stop takes sightings: the scan starts only when it is wanted, so nothing arrives before.
    private boolean browsing = true;

    public SnapcastDirectory(MonotonicClock clock, long graceMillis) {
        this.clock = clock;
        this.graceMillis = graceMillis;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /**
     * A service was found and resolved.
     *
     * @return true if the server is listed now (new or already known), false if it was refused: an address outside
     * the local ranges, a port outside {@value #MIN_DISCOVERED_PORT} to 65535, no or an oversized service name, or the
     * list is full
     */
    public boolean serviceFound(String serviceName, InetAddress address, int port) {
        if (serviceName == null || serviceName.isEmpty() || serviceName.length() > MAX_SERVICE_NAME
                || !SnapcastAddressFilter.isAccepted(address) || port < MIN_DISCOVERED_PORT) {
            return false;
        }
        SnapcastEndpoint endpoint = SnapcastEndpoint.fromAddress(address, port);
        if (endpoint == null) {
            return false;
        }
        SnapcastServerKey key = endpoint.toKey();
        boolean changed = false;
        boolean listed;
        synchronized (lock) {
            changed |= expireLocked();
            SnapcastServerKey previous = byServiceName.get(serviceName);
            if (!browsing) {
                // a late callback of a scan that was switched off (security review S41): nothing is listed
                listed = false;
            } else if (previous == null && byServiceName.size() >= MAX_SERVICE_NAMES) {
                listed = nodes.containsKey(key);
            } else {
                Node node = nodes.get(key);
                if (node == null && !makeRoomLocked(false)) {
                    listed = false;
                } else {
                    if (previous != null && !previous.equals(key)) {
                        changed |= detachLocked(serviceName, previous);
                    }
                    if (node == null) {
                        node = new Node(endpoint, nextOrder++);
                        nodes.put(key, node);
                        changed = true;
                    }
                    String display = SnapcastDisplayText.of(serviceName, MAX_DISPLAY_NAME);
                    changed |= node.services.add(serviceName) || node.lostAt >= 0
                            || !display.equals(node.displayName) || !sameAddress(node.endpoint, endpoint);
                    node.lostAt = -1;
                    node.displayName = display;
                    node.endpoint = endpoint;
                    byServiceName.put(serviceName, key);
                    listed = true;
                }
            }
        }
        finish(changed);
        return listed;
    }

    private static boolean sameAddress(SnapcastEndpoint a, SnapcastEndpoint b) {
        return a.getAddress() == null ? b.getAddress() == null : a.getAddress().equals(b.getAddress());
    }

    /** A service is gone (a "lost" event names only the service). */
    public void serviceLost(String serviceName) {
        boolean changed = false;
        synchronized (lock) {
            changed |= expireLocked();
            SnapcastServerKey key = serviceName == null ? null : byServiceName.get(serviceName);
            if (key != null) {
                changed |= detachLocked(serviceName, key);
            }
        }
        finish(changed);
    }

    /** Removes the service name from the entry; the entry becomes lost when it has none left and is not manual. */
    private boolean detachLocked(String serviceName, SnapcastServerKey key) {
        byServiceName.remove(serviceName);
        Node node = nodes.get(key);
        if (node == null) {
            return false;
        }
        boolean changed = node.services.remove(serviceName);
        if (node.services.isEmpty() && !node.manual && node.lostAt < 0) {
            node.lostAt = clock.nowMillis();
            changed = true;
        }
        return changed;
    }

    /**
     * Discovery starts again (the receivers page came back): nothing is known to be announced until it is seen
     * again, so every discovered entry that is not manual starts its grace period now.
     */
    public void discoveryRestarted() {
        boolean changed = false;
        synchronized (lock) {
            browsing = true;
            changed |= expireLocked();
            byServiceName.clear();
            long now = clock.nowMillis();
            for (Node node : nodes.values()) {
                if (!node.services.isEmpty()) {
                    node.services.clear();
                    changed = true;
                }
                if (!node.manual && node.lostAt < 0) {
                    node.lostAt = now;
                    changed = true;
                }
            }
        }
        finish(changed);
    }

    /**
     * Discovery is switched off (the setting, or no page is visible and it is not wanted): nothing is announced any more
     * as far as this list can know. A server that only discovery knew goes at once (its grace period is for a hiccup,
     * not for a switch); a typed-in or remembered server stays, and stays for good: it is no longer waiting for a sighting
     * that can not come, so the grace period it got when the scan began is cancelled.
     */
    public void discoveryStopped() {
        boolean changed;
        synchronized (lock) {
            browsing = false;
            changed = expireLocked();
            byServiceName.clear();
            for (Iterator<Map.Entry<SnapcastServerKey, Node>> it = nodes.entrySet().iterator(); it.hasNext(); ) {
                Node node = it.next().getValue();
                if (!node.manual && !node.remembered) {
                    it.remove();
                    changed = true;
                    continue;
                }
                if (!node.services.isEmpty() || node.lostAt >= 0) {
                    node.services.clear();
                    node.lostAt = -1;
                    changed = true;
                }
            }
        }
        finish(changed);
    }

    /**
     * Replaces the manual servers. An entry that is no longer on the list but is still announced stays as a
     * discovered one; otherwise it goes at once.
     *
     * @return how many of the given servers are listed (the list may hold {@link #MAX_SERVERS} at most)
     */
    public int setManualServers(Collection<SnapcastEndpoint> endpoints) {
        boolean changed;
        int accepted = 0;
        synchronized (lock) {
            changed = expireLocked();
            Set<SnapcastServerKey> wanted = new HashSet<>();
            List<SnapcastEndpoint> ordered = new ArrayList<>();
            for (SnapcastEndpoint endpoint : endpoints == null ? Collections.<SnapcastEndpoint>emptyList() : endpoints) {
                if (endpoint != null && ordered.size() < MAX_SERVERS && wanted.add(endpoint.toKey())) {
                    ordered.add(endpoint);
                }
            }
            for (Iterator<Map.Entry<SnapcastServerKey, Node>> it = nodes.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<SnapcastServerKey, Node> entry = it.next();
                Node node = entry.getValue();
                if (node.manual && !wanted.contains(entry.getKey())) {
                    node.manual = false;
                    changed = true;
                    if (node.services.isEmpty() && !node.remembered) {
                        it.remove();
                    }
                }
            }
            for (SnapcastEndpoint endpoint : ordered) {
                SnapcastServerKey key = endpoint.toKey();
                Node node = nodes.get(key);
                if (node == null) {
                    if (!makeRoomLocked(true)) {
                        continue;
                    }
                    node = new Node(endpoint, nextOrder++);
                    nodes.put(key, node);
                    changed = true;
                }
                if (!node.manual) {
                    node.manual = true;
                    node.lostAt = -1;
                    changed = true;
                }
                accepted++;
            }
        }
        finish(changed);
        return accepted;
    }

    /**
     * Replaces the remembered servers: the servers that hold a selected group, kept from an earlier run so that the
     * selection can be reconnected. Only an endpoint with an address that passes {@link SnapcastAddressFilter} is
     * taken (a remembered server was discovered once, so it was filtered then; it is filtered again, a stored value
     * is not trusted). A remembered server is listed at once and does not age out by time; when the list
     * is full it takes only the place of a lost entry (a server that is announced or typed in is never pushed out). One that is no longer remembered goes unless it is announced or typed in.
     *
     * @return how many of the given servers are remembered now
     */
    public int setRememberedServers(Collection<SnapcastEndpoint> endpoints) {
        boolean changed;
        int accepted = 0;
        synchronized (lock) {
            changed = expireLocked();
            Set<SnapcastServerKey> wanted = new HashSet<>();
            List<SnapcastEndpoint> ordered = new ArrayList<>();
            for (SnapcastEndpoint endpoint : endpoints == null ? Collections.<SnapcastEndpoint>emptyList() : endpoints) {
                if (endpoint != null && ordered.size() < MAX_SERVERS && SnapcastAddressFilter.isAccepted(endpoint.getAddress())
                        && wanted.add(endpoint.toKey())) {
                    ordered.add(endpoint);
                }
            }
            for (Iterator<Map.Entry<SnapcastServerKey, Node>> it = nodes.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<SnapcastServerKey, Node> entry = it.next();
                Node node = entry.getValue();
                if (node.remembered && !wanted.contains(entry.getKey())) {
                    node.remembered = false;
                    changed = true;
                    if (node.services.isEmpty() && !node.manual) {
                        it.remove();
                    }
                }
            }
            for (SnapcastEndpoint endpoint : ordered) {
                SnapcastServerKey key = endpoint.toKey();
                Node node = nodes.get(key);
                if (node == null) {
                    if (!makeRoomLocked(false)) {
                        continue;
                    }
                    node = new Node(endpoint, nextOrder++);
                    nodes.put(key, node);
                    changed = true;
                }
                if (!node.remembered) {
                    node.remembered = true;
                    changed = true;
                }
                accepted++;
            }
        }
        finish(changed);
        return accepted;
    }

    /** Makes room for one more entry if the list is full: a lost entry goes first, then (for a manual one) a discovered one. */
    private boolean makeRoomLocked(boolean forManual) {
        if (nodes.size() < MAX_SERVERS) {
            return true;
        }
        SnapcastServerKey victim = null;
        long victimLostAt = Long.MAX_VALUE;
        for (Map.Entry<SnapcastServerKey, Node> entry : nodes.entrySet()) {
            Node node = entry.getValue();
            if (!node.manual && node.lostAt >= 0 && node.lostAt < victimLostAt) {
                victim = entry.getKey();
                victimLostAt = node.lostAt;
            }
        }
        if (victim == null && forManual) {
            long oldest = Long.MAX_VALUE;
            for (Map.Entry<SnapcastServerKey, Node> entry : nodes.entrySet()) {
                Node node = entry.getValue();
                if (!node.manual && node.order < oldest) {
                    victim = entry.getKey();
                    oldest = node.order;
                }
            }
        }
        if (victim == null) {
            return false;
        }
        removeLocked(victim);
        return true;
    }

    private void removeLocked(SnapcastServerKey key) {
        nodes.remove(key);
        for (Iterator<SnapcastServerKey> it = byServiceName.values().iterator(); it.hasNext(); ) {
            if (it.next().equals(key)) {
                it.remove();
            }
        }
    }

    private boolean expireLocked() {
        boolean removed = false;
        long now = clock.nowMillis();
        List<SnapcastServerKey> gone = new ArrayList<>();
        for (Map.Entry<SnapcastServerKey, Node> entry : nodes.entrySet()) {
            Node node = entry.getValue();
            if (isExpired(node, now)) {
                gone.add(entry.getKey());
            }
        }
        for (SnapcastServerKey key : gone) {
            removeLocked(key);
            removed = true;
        }
        return removed;
    }

    /** Removes the servers whose grace period is over. @return true if the list changed */
    public boolean expire() {
        boolean changed;
        synchronized (lock) {
            changed = expireLocked();
        }
        finish(changed);
        return changed;
    }

    /** The servers, sorted by key (a stable order). Servers past their grace period are not in it. */
    public List<Entry> servers() {
        List<Entry> result = new ArrayList<>();
        boolean changed;
        synchronized (lock) {
            changed = expireLocked();
            for (Map.Entry<SnapcastServerKey, Node> entry : nodes.entrySet()) {
                result.add(entryOf(entry.getValue()));
            }
        }
        finish(changed);
        sortByKey(result);
        return result;
    }

    /**
     * Like {@link #servers()}, but quiet: servers past their grace period are left out instead of being removed, and no
     * listener is called. For code that holds a lock of its own and must not run foreign code under it; whoever
     * calls {@link #servers()}, {@link #expire()} or any change later sweeps the left-out entries and tells the
     * listeners.
     */
    public List<Entry> snapshot() {
        List<Entry> result = new ArrayList<>();
        synchronized (lock) {
            long now = clock.nowMillis();
            for (Node node : nodes.values()) {
                if (!isExpired(node, now)) {
                    result.add(entryOf(node));
                }
            }
        }
        sortByKey(result);
        return result;
    }

    private boolean isExpired(Node node, long now) {
        return !node.manual && node.lostAt >= 0 && now - node.lostAt >= graceMillis;
    }

    private static void sortByKey(List<Entry> entries) {
        Collections.sort(entries, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return a.getKey().asString().compareTo(b.getKey().asString());
            }
        });
    }

    /** @return the entry for the key, or null */
    public Entry find(SnapcastServerKey key) {
        boolean changed;
        Entry result;
        synchronized (lock) {
            changed = expireLocked();
            Node node = nodes.get(key);
            result = node == null ? null : entryOf(node);
        }
        finish(changed);
        return result;
    }

    private static Entry entryOf(Node node) {
        return new Entry(node.endpoint, node.displayName, node.manual, !node.services.isEmpty(), node.lostAt >= 0,
                node.remembered);
    }

    /** For tests: the number of service names remembered. */
    int knownServiceNameCount() {
        synchronized (lock) {
            return byServiceName.size();
        }
    }

    private void finish(boolean changed) {
        if (changed) {
            for (Listener listener : listeners) {
                listener.onServersChanged();
            }
        }
    }
}
