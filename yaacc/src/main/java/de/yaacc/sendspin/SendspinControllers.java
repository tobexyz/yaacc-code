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
package de.yaacc.sendspin;

import de.yaacc.sendspin.client.ControllerSession;
import de.yaacc.sendspin.discovery.SendspinDirectory;
import de.yaacc.sendspin.hub.Connector;
import de.yaacc.sendspin.hub.Endpoint;
import de.yaacc.sendspin.hub.HubClock;
import de.yaacc.sendspin.secure.Identity;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The Sendspin servers the phone controls (the controller role): the addresses the user added and one {@link ControllerSession} for
 * each, made when the receivers are listed (it gives the card its state) and again when the user asks. After the guidance of the
 * protocol a session that ended because the server wants pairing, did not authorize it, unpaired it, or sent something the client
 * can not follow, or that the user closed, is not made again by a list refresh. The others are made again after a pause. Pure: the
 * Android parts (preferences, identity storage) are passed in.
 */
public final class SendspinControllers {

    /** How long a session that ended by itself waits before a list refresh makes it again. */
    static final long RETRY_PAUSE_MILLIS = 15_000L;
    private static final String CLIENT_NAME = "YAACC";

    private final SendspinPlayers servers;
    private final Supplier<Identity> identity;
    private final BooleanSupplier unpairedAllowed;
    private final Connector connector;
    private final HubClock clock;
    private final SecureRandom random;
    private final Supplier<List<SendspinDirectory.Entry>> found;
    private final java.util.function.Consumer<ControllerSession> changed;
    private final SendspinClientPairings credentials;
    private final Map<Endpoint, ControllerSession> sessions = new HashMap<>();
    private final Map<Endpoint, Long> lastAttempt = new HashMap<>();
    private volatile Supplier<de.yaacc.sendspin.player.ClientCodePairing> codes;

    /** The code-based pairing this app offers as a client (task 11.1): given to every session that is made after this call. */
    public void setCodePairing(Supplier<de.yaacc.sendspin.player.ClientCodePairing> codes) {
        this.codes = codes;
    }

    /** The operator opened the pairing window: the attempts that wait for it start. */
    public synchronized void windowOpened() {
        for (ControllerSession session : new java.util.ArrayList<>(sessions.values())) {
            session.onWindowOpened();
        }
    }

    public SendspinControllers(StringSetStore store, Supplier<Identity> identity, BooleanSupplier unpairedAllowed,
                               Supplier<List<SendspinDirectory.Entry>> found, Connector connector, HubClock clock, SecureRandom random,
                               java.util.function.Consumer<ControllerSession> changed) {
        this.servers = SendspinPlayers.servers(store);
        this.credentials = new SendspinClientPairings(store, random);
        this.identity = identity;
        this.unpairedAllowed = unpairedAllowed;
        this.found = found;
        this.connector = connector;
        this.clock = clock;
        this.random = random;
        this.changed = changed;
    }

    /** Ends the sessions with the server of this id; the user's next action makes them again. */
    public synchronized void endSessionsOf(String serverId) {
        for (ControllerSession session : new java.util.ArrayList<>(sessions.values())) {
            if (serverId != null && serverId.equals(session.serverId())) {
                session.close();
            }
        }
    }

    /** The pairing records of this app as a client: the token it shows and the servers it is paired with. */
    public SendspinClientPairings credentials() {
        return credentials;
    }

    /** The servers the user added; the settings dialog edits them. */
    public SendspinPlayers servers() {
        return servers;
    }

    /** The current session of a server, or null if there is none (yet). */
    public synchronized ControllerSession session(Endpoint endpoint) {
        return sessions.get(endpoint);
    }

    /** The name the server announced, or null. */
    public synchronized String announcedName(Endpoint endpoint) {
        ControllerSession session = sessions.get(endpoint);
        return session == null ? null : session.serverName();
    }

    /**
     * The session of a server, made if there is none that is alive.
     *
     * @param force true for an action of the user: a session that ended is made again whatever the reason
     */
    public synchronized ControllerSession acquire(Endpoint endpoint, boolean force) {
        ControllerSession current = sessions.get(endpoint);
        long now = clock.nowMicros() / 1000L;
        if (current != null && current.phase() != ControllerSession.Phase.ENDED) {
            return current;
        }
        if (current != null && !force) {
            if (!mayRetry(current.endReason())) {
                return current;
            }
            Long last = lastAttempt.get(endpoint);
            if (last != null && now - last < RETRY_PAUSE_MILLIS) {
                return current;
            }
        }
        lastAttempt.put(endpoint, now);
        ControllerSession made = new ControllerSession(endpoint, identity.get(), unpairedAllowed.getAsBoolean(), connector, clock, random,
                CLIENT_NAME, changed::accept, credentials);
        Supplier<de.yaacc.sendspin.player.ClientCodePairing> offered = codes;
        de.yaacc.sendspin.player.ClientCodePairing pairing = offered == null ? null : offered.get();
        if (pairing != null) {
            made.setCodePairing(pairing.offer(), pairing.window(), pairing);
        }
        sessions.put(endpoint, made);
        made.start();
        return made;
    }

    static boolean mayRetry(ControllerSession.EndReason reason) {
        switch (reason) {
            case PAIRING_REQUIRED:
            case UNAUTHORIZED:
            case UNPAIRED:
            case CLOSED_BY_APP:
            case PROTOCOL_ERROR:
                return false;
            default:
                return true;
        }
    }

    /** Ends the session of a server and forgets the address. */
    public synchronized void remove(Endpoint endpoint) {
        ControllerSession current = sessions.remove(endpoint);
        if (current != null) {
            current.close();
        }
        lastAttempt.remove(endpoint);
        servers.remove(endpoint);
    }

    /** Ends the session of a server and keeps the address (the user asked to reconnect, or the switch went off). */
    public synchronized void disconnect(Endpoint endpoint) {
        ControllerSession current = sessions.remove(endpoint);
        if (current != null) {
            current.close();
        }
        lastAttempt.remove(endpoint);
    }

    public synchronized void closeAll() {
        for (ControllerSession session : new ArrayList<>(sessions.values())) {
            session.close();
        }
        sessions.clear();
        lastAttempt.clear();
    }

    /** The most servers that are listed and connected to at all (added and found together). */
    static final int MAX_LISTED = 16;

    /** The servers the user added, then the ones the network announced (a server in both lists is listed once). */
    public List<Endpoint> listed() {
        List<Endpoint> result = new ArrayList<>(servers.list());
        for (SendspinDirectory.Entry entry : found.get()) {
            if (result.size() >= MAX_LISTED) {
                break;
            }
            if (!result.contains(entry.endpoint())) {
                result.add(entry.endpoint());
            }
        }
        while (result.size() > MAX_LISTED) {
            result.remove(result.size() - 1);
        }
        return result;
    }

    /** The name a server announced by mDNS (cleaned, may be empty), or null if the network did not announce it. */
    public String foundName(Endpoint endpoint) {
        for (SendspinDirectory.Entry entry : found.get()) {
            if (entry.endpoint().equals(endpoint)) {
                return entry.name();
            }
        }
        return null;
    }
}
