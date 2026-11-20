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
package de.yaacc.fcast.discovery;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import de.yaacc.fcast.protocol.FCastText;
import de.yaacc.multiroom.control.MonotonicClock;
import de.yaacc.multiroom.discovery.SnapcastAddressFilter;

/**
 * The FCast receivers the app knows, from three sources that are merged by endpoint (one receiver found by mDNS and typed in by
 * hand is one entry): <b>discovered</b> ones (mDNS announcements, which are not authenticated and so are filtered and capped),
 * <b>manual</b> ones (typed in, pasted or scanned: the user chose them) and <b>remembered</b> ones (the selected receivers of
 * the last run, so that a selection survives a restart before the first scan; shown as offline until they are seen). A smaller
 * sibling of the Snapcast directory (decision D15). Thread safe; the listeners are called outside the lock.
 *
 * <p><b>What a hostile network can do.</b> Anybody on the LAN can announce {@code _fcast._tcp}. A discovered receiver is accepted only at an
 * address of the local network ({@link SnapcastAddressFilter}: no loopback, no public address) and at a port from 1024 up; at most
 * {@value #MAX_DISCOVERED} discovered receivers and {@value #MAX_SERVICE_IDS} service names are kept, and a flood does <b>not</b> push out the
 * entries that are known (the new announcement is refused: evicting the old ones would let an attacker replace the real receivers).
 * Names are display text, cleaned and cut.
 *
 * <p><b>Staleness.</b> The platform reports a service that goes away ({@link #serviceLost}); a scan that starts again marks what it
 * knew as unconfirmed for {@code graceMillis}, and what is not announced again in that time is dropped (checked whenever the
 * entries are read: no timer). Nothing is dropped while no scan runs (nobody could confirm it).
 */
public final class FCastDirectory {
    /** The most discovered receivers. */
    public static final int MAX_DISCOVERED = 64;
    /** The most service names remembered for the lost events. */
    public static final int MAX_SERVICE_IDS = 128;
    /** The most entries of all kinds. */
    public static final int MAX_ENTRIES = 64 + 16 + 16;
    /** The lowest port accepted from an announcement (an announcement must not aim the app at a system service). */
    public static final int MIN_DISCOVERED_PORT = 1024;
    /** How long what a restarted scan knew may stay unconfirmed. */
    public static final long DEFAULT_GRACE_MILLIS = 30_000L;

    private static final int MAX_DISPLAY_NAME = 64;

    /** Told after every change; called outside the directory's lock, so it may read the directory. */
    public interface Listener {
        void changed();
    }

    /** A name and an address that the user or an earlier run gave. */
    public static final class Known {
        private final FCastEndpoint endpoint;
        private final String name;

        public Known(FCastEndpoint endpoint, String name) {
            this.endpoint = endpoint;
            this.name = name == null ? "" : name;
        }

        public FCastEndpoint getEndpoint() {
            return endpoint;
        }

        public String getName() {
            return name;
        }
    }

    /** One receiver, as of the moment it was read. Immutable. */
    public static final class Entry {
        private final FCastEndpoint endpoint;
        private final String displayName;
        private final FCastServiceTxt hints;
        private final boolean manual;
        private final boolean discovered;

        Entry(FCastEndpoint endpoint, String displayName, FCastServiceTxt hints, boolean manual, boolean discovered) {
            this.endpoint = endpoint;
            this.displayName = displayName;
            this.hints = hints;
            this.manual = manual;
            this.discovered = discovered;
        }

        public FCastEndpoint getEndpoint() {
            return endpoint;
        }

        /** The receiver id of the lists ({@link FCastReceiverId}). */
        public String getId() {
            return FCastReceiverId.of(endpoint);
        }

        /** The name to show: the announced name, else the name that was typed or remembered, else the address. Display text. */
        public String getDisplayName() {
            return displayName;
        }

        /** What the receiver announced in its TXT record (hints; {@link FCastServiceTxt#EMPTY} if it was not discovered). */
        public FCastServiceTxt getHints() {
            return hints;
        }

        public boolean isManual() {
            return manual;
        }

        public boolean isDiscovered() {
            return discovered;
        }

        /** True for a receiver that is only remembered from the last run and has not been seen in this one. */
        public boolean isOffline() {
            return !manual && !discovered;
        }
    }

    private static final class Node {
        final FCastEndpoint endpoint;
        String manualName;       // null: not manual
        String rememberedName;   // null: not remembered
        String discoveredName;   // the instance name of the latest announcement
        FCastServiceTxt hints = FCastServiceTxt.EMPTY;
        final Set<String> serviceIds = new LinkedHashSet<>();
        long unconfirmedUntil;   // 0: confirmed (or not discovered)

        Node(FCastEndpoint endpoint) {
            this.endpoint = endpoint;
        }

        boolean isDiscovered() {
            return !serviceIds.isEmpty();
        }

        boolean isUseless() {
            return manualName == null && rememberedName == null && !isDiscovered();
        }
    }

    private final MonotonicClock clock;
    private final long graceMillis;
    private final Map<FCastEndpoint, Node> nodes = new HashMap<>();
    private final Map<String, FCastEndpoint> byServiceId = new HashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public FCastDirectory(MonotonicClock clock, long graceMillis) {
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
            try {
                listener.changed();
            } catch (RuntimeException ignored) {
                // one listener failing must not stop the others
            }
        }
    }

    // ------------------------------------------------------------------ discovered

    /**
     * An announcement was resolved.
     *
     * @param serviceId   the key of the service for the lost events ({@link FCastServiceType#serviceId})
     * @param serviceName the instance name (display text)
     * @param address     the resolved address (a number: no name lookup)
     * @param hints       the TXT hints, may be null
     * @return true if the receiver is in the directory now, false if the announcement was refused (address, port or a cap)
     */
    public boolean serviceFound(String serviceId, String serviceName, InetAddress address, int port, FCastServiceTxt hints) {
        if (serviceId == null || serviceId.isEmpty() || serviceId.length() > 300 || port < MIN_DISCOVERED_PORT || port > 65535
                || !SnapcastAddressFilter.isAcceptedForDiscovery(address)) {
            return false;
        }
        FCastEndpoint endpoint = FCastEndpoint.fromAddress(address, port);
        if (endpoint == null) {
            return false;
        }
        boolean changed;
        synchronized (this) {
            expireLocked();
            Node node = nodes.get(endpoint);
            boolean alreadyDiscovered = node != null && node.isDiscovered();
            if (!alreadyDiscovered && countDiscovered() >= MAX_DISCOVERED && !byServiceId.containsKey(serviceId)) {
                return false; // a flood does not push out what is known
            }
            if (!byServiceId.containsKey(serviceId) && byServiceId.size() >= MAX_SERVICE_IDS) {
                return false;
            }
            if (node == null) {
                if (nodes.size() >= MAX_ENTRIES) {
                    return false;
                }
                node = new Node(endpoint);
                nodes.put(endpoint, node);
            }
            changed = moveService(serviceId, node);
            String name = FCastText.clean(serviceName, MAX_DISPLAY_NAME);
            FCastServiceTxt newHints = hints == null ? FCastServiceTxt.EMPTY : hints;
            if (!name.equals(node.discoveredName) || node.hints != newHints || node.unconfirmedUntil != 0) {
                changed = true;
            }
            node.discoveredName = name;
            node.hints = newHints;
            node.unconfirmedUntil = 0;
        }
        if (changed) {
            fire();
        }
        return true;
    }

    /** Points the service id at this node; the node it pointed at before loses it (a receiver that came back at another address). @return true if anything changed */
    private boolean moveService(String serviceId, Node node) {
        FCastEndpoint before = byServiceId.get(serviceId);
        if (node.endpoint.equals(before)) {
            return false;
        }
        if (before != null) {
            Node old = nodes.get(before);
            if (old != null) {
                old.serviceIds.remove(serviceId);
                if (!old.isDiscovered()) {
                    old.discoveredName = null;
                    old.hints = FCastServiceTxt.EMPTY;
                    old.unconfirmedUntil = 0;
                }
                if (old.isUseless()) {
                    nodes.remove(before);
                }
            }
        }
        byServiceId.put(serviceId, node.endpoint);
        node.serviceIds.add(serviceId);
        return true;
    }

    /** The platform says the service is gone. */
    public void serviceLost(String serviceId) {
        boolean changed = false;
        synchronized (this) {
            FCastEndpoint endpoint = byServiceId.remove(serviceId);
            if (endpoint != null) {
                Node node = nodes.get(endpoint);
                if (node != null) {
                    node.serviceIds.remove(serviceId);
                    if (!node.isDiscovered()) {
                        node.discoveredName = null;
                        node.hints = FCastServiceTxt.EMPTY;
                        node.unconfirmedUntil = 0;
                        if (node.isUseless()) {
                            nodes.remove(endpoint);
                        }
                    }
                    changed = true;
                }
            }
        }
        if (changed) {
            fire();
        }
    }

    /** A scan starts: what the last one found is unconfirmed until it is announced again (or the grace time is over). */
    public void discoveryRestarted() {
        synchronized (this) {
            long until = clock.nowMillis() + graceMillis;
            for (Node node : nodes.values()) {
                if (node.isDiscovered()) {
                    node.unconfirmedUntil = until;
                }
            }
        }
    }

    /** The scan ended: nothing can confirm or deny an entry any more, so none is dropped for staleness. */
    public void discoveryStopped() {
        synchronized (this) {
            for (Node node : nodes.values()) {
                node.unconfirmedUntil = 0;
            }
        }
    }

    /** The discovery was switched off: forget everything it found (manual and remembered receivers stay). */
    public void clearDiscovered() {
        boolean changed = false;
        synchronized (this) {
            for (Iterator<Node> it = nodes.values().iterator(); it.hasNext(); ) {
                Node node = it.next();
                if (node.isDiscovered()) {
                    changed = true;
                    node.serviceIds.clear();
                    node.discoveredName = null;
                    node.hints = FCastServiceTxt.EMPTY;
                    node.unconfirmedUntil = 0;
                    if (node.isUseless()) {
                        it.remove();
                    }
                }
            }
            byServiceId.clear();
        }
        if (changed) {
            fire();
        }
    }

    // ------------------------------------------------------------------ manual and remembered

    /** Replaces the manual receivers (the stored list). @return how many are in the directory as manual ones */
    public int setManual(Collection<Known> receivers) {
        return setKnown(receivers, true);
    }

    /** Replaces the remembered receivers (the selection of the last run). @return how many are remembered */
    public int setRemembered(Collection<Known> receivers) {
        return setKnown(receivers, false);
    }

    private int setKnown(Collection<Known> receivers, boolean manual) {
        int count = 0;
        synchronized (this) {
            for (Node node : nodes.values()) {
                if (manual) {
                    node.manualName = null;
                } else {
                    node.rememberedName = null;
                }
            }
            for (Known known : receivers) {
                if (known == null || known.getEndpoint() == null) {
                    continue;
                }
                Node node = nodes.get(known.getEndpoint());
                if (node == null) {
                    if (nodes.size() >= MAX_ENTRIES) {
                        continue;
                    }
                    node = new Node(known.getEndpoint());
                    nodes.put(known.getEndpoint(), node);
                }
                String name = FCastText.clean(known.getName(), MAX_DISPLAY_NAME);
                if (manual) {
                    node.manualName = name;
                } else {
                    node.rememberedName = name;
                }
                count++;
            }
            for (Iterator<Node> it = nodes.values().iterator(); it.hasNext(); ) {
                if (it.next().isUseless()) {
                    it.remove();
                }
            }
        }
        fire();
        return count;
    }

    // ------------------------------------------------------------------ reading

    /** The receivers, sorted by name (case insensitive) and then by address. Drops what a scan did not confirm in time. */
    public List<Entry> snapshot() {
        boolean dropped;
        List<Entry> result = new ArrayList<>();
        synchronized (this) {
            dropped = expireLocked();
            for (Node node : nodes.values()) {
                result.add(entryOf(node));
            }
        }
        Collections.sort(result, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                int byName = a.getDisplayName().toLowerCase(Locale.ROOT).compareTo(b.getDisplayName().toLowerCase(Locale.ROOT));
                return byName != 0 ? byName : a.getEndpoint().toString().compareTo(b.getEndpoint().toString());
            }
        });
        if (dropped) {
            fire();
        }
        return result;
    }

    /** @return the receiver at this endpoint, or null */
    public Entry find(FCastEndpoint endpoint) {
        synchronized (this) {
            expireLocked();
            Node node = nodes.get(endpoint);
            return node == null ? null : entryOf(node);
        }
    }

    /** @return the receiver with this id ({@code fcast:...}), or null */
    public Entry findById(String id) {
        FCastEndpoint endpoint = FCastReceiverId.parse(id);
        return endpoint == null ? null : find(endpoint);
    }

    private Entry entryOf(Node node) {
        String name = firstNotEmpty(node.discoveredName, node.manualName, node.rememberedName);
        if (name.isEmpty()) {
            name = node.endpoint.toString();
        }
        return new Entry(node.endpoint, name, node.hints, node.manualName != null, node.isDiscovered());
    }

    private static String firstNotEmpty(String... names) {
        for (String name : names) {
            if (name != null && !name.isEmpty()) {
                return name;
            }
        }
        return "";
    }

    private int countDiscovered() {
        int count = 0;
        for (Node node : nodes.values()) {
            if (node.isDiscovered()) {
                count++;
            }
        }
        return count;
    }

    /** Drops the discovered state of what a restarted scan did not confirm in time. @return true if something was dropped */
    private boolean expireLocked() {
        long now = clock.nowMillis();
        boolean dropped = false;
        for (Iterator<Node> it = nodes.values().iterator(); it.hasNext(); ) {
            Node node = it.next();
            if (node.isDiscovered() && node.unconfirmedUntil != 0 && now >= node.unconfirmedUntil) {
                for (String id : node.serviceIds) {
                    byServiceId.remove(id);
                }
                node.serviceIds.clear();
                node.discoveredName = null;
                node.hints = FCastServiceTxt.EMPTY;
                node.unconfirmedUntil = 0;
                dropped = true;
                if (node.isUseless()) {
                    it.remove();
                }
            }
        }
        return dropped;
    }
}
