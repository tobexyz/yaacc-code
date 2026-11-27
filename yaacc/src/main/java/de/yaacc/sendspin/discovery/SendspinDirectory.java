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
package de.yaacc.sendspin.discovery;

import de.yaacc.multiroom.control.MonotonicClock;
import de.yaacc.multiroom.discovery.SnapcastAddressFilter;
import de.yaacc.sendspin.SendspinRowText;
import de.yaacc.sendspin.secure.Base64Url;
import de.yaacc.sendspin.hub.Endpoint;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The Sendspin players the network announced (mDNS {@code _sendspin._tcp}), as the last scan left them. Announcements are not
 * authenticated, so what is accepted is limited: only addresses of the local network (no loopback, no public address, as for the
 * other discovered devices), a port of 1024 or more, a path that is {@code path-absolute}, at most {@value #MAX_DISCOVERED} players.
 * A service that disappears stays listed for a grace period (announcements flap), then goes. Pure; the platform calls and the
 * connections are elsewhere. Thread safe; listeners are told outside the lock.
 */
public final class SendspinDirectory {

    public static final int MAX_DISCOVERED = 32;
    public static final int MIN_PORT = 1024;
    public static final long DEFAULT_GRACE_MILLIS = 30_000L;
    private static final int MAX_SERVICE_ID = 300;

    /** A player as announced. */
    public static final class Entry {
        private final Endpoint endpoint;
        private final String name;
        private final String yaacc;

        public Entry(Endpoint endpoint, String name) {
            this(endpoint, name, null);
        }

        public Entry(Endpoint endpoint, String name, String yaacc) {
            this.endpoint = endpoint;
            this.name = name;
            this.yaacc = yaacc;
        }

        /** The id a YAACC phone puts in the announcements of its player and its server, or null (see {@link SendspinServiceTxt#yaacc()}). */
        public String yaacc() {
            return yaacc;
        }

        public Endpoint endpoint() {
            return endpoint;
        }

        /** The announced name (cleaned), or the empty text. */
        public String name() {
            return name;
        }
    }

    /** Told after every change, outside the lock. */
    public interface Listener {
        void changed();
    }

    private static final class Node {
        final Endpoint endpoint;
        final String name;
        final String yaacc;
        long lostAt; // 0 while the service is present

        Node(Endpoint endpoint, String name, String yaacc) {
            this.endpoint = endpoint;
            this.name = name;
            this.yaacc = yaacc;
        }
    }

    private final MonotonicClock clock;
    private final long graceMillis;
    private final Map<String, Node> nodes = new HashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public SendspinDirectory(MonotonicClock clock, long graceMillis) {
        this.clock = clock;
        this.graceMillis = graceMillis;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void fire() {
        for (Listener listener : listeners) {
            listener.changed();
        }
    }

    /** @return true if the announcement was accepted (listed or refreshed) */
    public boolean serviceFound(String serviceId, String serviceName, InetAddress address, int port, SendspinServiceTxt txt) {
        if (serviceId == null || serviceId.isEmpty() || serviceId.length() > MAX_SERVICE_ID || txt == null || port < MIN_PORT || port > 65535
                || !SnapcastAddressFilter.isAcceptedForDiscovery(address)) {
            return false;
        }
        if (address instanceof Inet6Address && address.isLinkLocalAddress()) {
            return false; // usable only with the interface it was seen on, which an endpoint does not keep
        }
        String host = address.getHostAddress();
        int scope = host.indexOf('%');
        if (scope >= 0) {
            host = host.substring(0, scope);
        }
        Endpoint endpoint = new Endpoint(host, port, txt.path());
        // the friendly name is the TXT key "name"; the instance name of a real player is its client id (43 characters of a key),
        // which is no name for a card
        String name = txt.name();
        if (name.isEmpty() && Base64Url.decode32(serviceName) == null) {
            name = SendspinRowText.clean(serviceName);
        }
        boolean changed;
        boolean accepted = true;
        synchronized (this) {
            boolean dropped = expireLocked();
            Node old = nodes.get(serviceId);
            if (old == null && nodes.size() >= MAX_DISCOVERED) {
                accepted = false; // a flood does not push out what is known
                changed = dropped;
            } else {
                // a service that comes back inside its grace period shows nothing new
                changed = dropped || old == null || !old.endpoint.equals(endpoint) || !old.name.equals(name)
                        || !java.util.Objects.equals(old.yaacc, txt.yaacc());
                nodes.put(serviceId, new Node(endpoint, name, txt.yaacc()));
            }
        }
        if (changed) {
            fire();
        }
        return accepted;
    }

    /** The platform says the service is gone: it is listed for the grace period still. */
    public void serviceLost(String serviceId) {
        synchronized (this) {
            Node node = nodes.get(serviceId);
            if (node != null && node.lostAt == 0) {
                node.lostAt = Math.max(1, clock.nowMillis()); // still listed: the lists change when the grace period is over
            }
        }
    }

    /**
     * A new scan starts: what the earlier one found and the new one does not announce again is gone after the grace period (a browse
     * only reports the loss of services it has seen itself).
     */
    public void discoveryRestarted() {
        synchronized (this) {
            long now = Math.max(1, clock.nowMillis());
            for (Node node : nodes.values()) {
                if (node.lostAt == 0) {
                    node.lostAt = now;
                }
            }
        }
    }

    /** The scan ended (switched off): what it found is forgotten. */
    public void clear() {
        boolean changed;
        synchronized (this) {
            changed = !nodes.isEmpty();
            nodes.clear();
        }
        if (changed) {
            fire();
        }
    }

    /** The players, one per endpoint, sorted by name and address; services past their grace period are dropped first. */
    public List<Entry> snapshot() {
        boolean dropped;
        List<Entry> result = new ArrayList<>();
        synchronized (this) {
            dropped = expireLocked();
            Map<String, Entry> byEndpoint = new HashMap<>();
            for (Node node : nodes.values()) {
                String key = node.endpoint.host() + ":" + node.endpoint.port();
                Entry existing = byEndpoint.get(key);
                if (existing == null || existing.name().isEmpty()) {
                    byEndpoint.put(key, new Entry(node.endpoint, node.name, node.yaacc));
                }
            }
            result.addAll(byEndpoint.values());
        }
        Collections.sort(result, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                int byName = a.name().compareToIgnoreCase(b.name());
                return byName != 0 ? byName : a.endpoint().toString().compareTo(b.endpoint().toString());
            }
        });
        if (dropped) {
            fire();
        }
        return result;
    }

    private boolean expireLocked() {
        long now = clock.nowMillis();
        boolean dropped = false;
        for (Iterator<Node> it = nodes.values().iterator(); it.hasNext(); ) {
            Node node = it.next();
            if (node.lostAt != 0 && now - node.lostAt >= graceMillis) {
                it.remove();
                dropped = true;
            }
        }
        return dropped;
    }
}
