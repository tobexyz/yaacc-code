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

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import de.yaacc.sendspin.hub.Connector;
import de.yaacc.sendspin.hub.Endpoint;
import de.yaacc.sendspin.hub.HubClock;
import de.yaacc.sendspin.hub.HubConnection;
import de.yaacc.sendspin.secure.Base64Url;
import de.yaacc.sendspin.secure.Identity;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Sendspin players of the app (task 4.3 of the spec, first slice): the players the user added by address, one
 * {@link HubConnection} for each while the switch is on, the operator's approvals and the identity of this server. The app part
 * of the hub is this class; everything it decides is in the pure classes of this package and of {@code de.yaacc.sendspin.hub}.
 * <p>
 * A connection is made when the receivers are listed (it gives the row its state and the player its stream format, which the audio
 * tap needs before ExoPlayer is built) and again when the user plays to a player. It does not give up the player's other servers:
 * with no activities a connection ranks lowest and is displaced by any server that plays. After the guidance of
 * {@code client/goodbye} a connection that ended because the player went to another server or demands pairing is not made again
 * by a list refresh, only by the user; the others are retried after a pause.
 * <p>
 * Not in this slice: discovery by mDNS (players are added by address), the Keystore wrapping of the identity key (it lives in the
 * app-private preferences; backup is off), pairing, groups.
 */
public final class SendspinHub implements SendspinReceiverSource {

    private static final long RETRY_PAUSE_MILLIS = 15_000L;
    private static final String SERVER_NAME = "YAACC";

    private static SendspinHub shared;

    private final SharedPreferences preferences;
    private final StringSetStore store;
    private final SendspinPlayers players;
    private final SendspinApprovals approvals;
    private final Connector connector;
    private final HubClock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<Endpoint, HubConnection> connections = new HashMap<>();
    private final Map<Endpoint, Long> lastAttempt = new HashMap<>();
    private final Map<Endpoint, Integer> rates = new HashMap<>();
    private final Map<String, SendspinReceiverDevice> devices = new HashMap<>();
    private Identity identity;

    private SendspinHub(Context context) {
        this.preferences = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        this.store = new StringSetStore() {
            @Override
            public Set<String> get(String key) {
                Set<String> stored;
                try {
                    stored = preferences.getStringSet(key, null);
                } catch (ClassCastException damaged) {
                    stored = null;
                }
                return stored == null ? new HashSet<String>() : new HashSet<>(stored);
            }

            @Override
            public void put(String key, Set<String> values) {
                preferences.edit().putStringSet(key, new HashSet<>(values)).apply();
            }
        };
        this.players = new SendspinPlayers(store);
        this.approvals = new SendspinApprovals(store);
        this.connector = new Connector.Sockets();
        this.clock = HubClock.SYSTEM;
    }

    public static synchronized SendspinHub getShared(Context context) {
        if (shared == null) {
            shared = new SendspinHub(context);
        }
        return shared;
    }

    public boolean isEnabled() {
        return SendspinSettings.isEnabled(preferences::getBoolean);
    }

    /** The players the user added; the settings dialog edits them. */
    public SendspinPlayers players() {
        return players;
    }

    public SendspinApprovals approvals() {
        return approvals;
    }

    private synchronized Identity identity() {
        if (identity == null) {
            String stored = preferences.getString(SendspinSettings.KEY_IDENTITY, null);
            byte[] key = stored == null ? null : Base64Url.decode32(stored);
            if (key != null) {
                identity = Identity.fromPrivateKey(key);
            } else {
                identity = Identity.generate(random);
                preferences.edit().putString(SendspinSettings.KEY_IDENTITY, Base64Url.encode(identity.exportPrivateKey())).apply();
            }
        }
        return identity;
    }

    // ------------------------------------------------------------------ the connections

    /** The current connection to a player, or null if there is none (yet). */
    public synchronized HubConnection connection(Endpoint endpoint) {
        return connections.get(endpoint);
    }

    /**
     * The connection to a player, made if there is none that is alive.
     *
     * @param force true for an action of the user: a connection that ended is made again whatever the reason
     */
    public synchronized HubConnection acquire(Endpoint endpoint, boolean force) {
        HubConnection current = connections.get(endpoint);
        long now = clock.nowMicros() / 1000L;
        if (current != null && current.phase() != HubConnection.Phase.ENDED) {
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
        HubConnection made = new HubConnection(endpoint, identity(), approvals, connector, clock, random, SERVER_NAME, null);
        connections.put(endpoint, made);
        made.start();
        return made;
    }

    /** What the guidance of {@code client/goodbye} lets a server do without the user: reconnect after a pause, or not at all. */
    static boolean mayRetry(HubConnection.EndReason reason) {
        switch (reason) {
            case ANOTHER_SERVER:
            case UNPAIRED:
            case PAIRING_REQUIRED:
            case UNAUTHORIZED:
            case USER_REQUEST:
            case PROTOCOL_ERROR:
            case CLOSED_BY_APP:
                return false;
            default:
                return true; // failed, shutdown, restart, concurrent attempt, other
        }
    }

    /**
     * The sample rate of the stream to a player: from its connection if the hello is known, else the one remembered from an earlier
     * connection, else 0. The audio tap needs it before the player of the app is built.
     */
    public synchronized int sampleRateOf(Endpoint endpoint) {
        HubConnection current = connections.get(endpoint);
        if (current != null && current.streamFormat() != null) {
            rates.put(endpoint, current.streamFormat().sampleRate);
        }
        Integer rate = rates.get(endpoint);
        return rate == null ? 0 : rate;
    }

    /** Ends the connection to a player and forgets it (the player stays in the list unless it is removed). */
    public synchronized void disconnect(Endpoint endpoint) {
        HubConnection current = connections.remove(endpoint);
        if (current != null) {
            current.close();
        }
        lastAttempt.remove(endpoint);
    }

    private void closeAll() {
        if (connections.isEmpty()) {
            return;
        }
        for (HubConnection connection : connections.values()) {
            connection.close();
        }
        connections.clear();
        lastAttempt.clear();
    }

    /** Removes a player from the list and ends its connection. */
    public synchronized void remove(Endpoint endpoint) {
        disconnect(endpoint);
        players.remove(endpoint);
        devices.remove(SendspinAddress.idOf(endpoint));
    }

    // ------------------------------------------------------------------ SendspinReceiverSource

    @Override
    public synchronized List<SendspinReceiverDevice> receivers() {
        if (!isEnabled()) {
            closeAll(); // switched off: nothing stays connected
            return Collections.emptyList();
        }
        List<SendspinReceiverDevice> result = new ArrayList<>();
        Set<String> current = new HashSet<>();
        for (Endpoint endpoint : players.list()) {
            SendspinReceiverDevice device = deviceFor(endpoint, acquire(endpoint, false));
            if (device != null) {
                result.add(device);
                current.add(device.getReceiverId());
            }
        }
        devices.keySet().retainAll(current);
        Collections.sort(result, new Comparator<SendspinReceiverDevice>() {
            @Override
            public int compare(SendspinReceiverDevice a, SendspinReceiverDevice b) {
                int byName = a.getName().compareToIgnoreCase(b.getName());
                return byName != 0 ? byName : a.getReceiverId().compareTo(b.getReceiverId());
            }
        });
        return result;
    }

    @Override
    public synchronized SendspinReceiverDevice findReceiver(String receiverId) {
        if (!isEnabled()) {
            return null;
        }
        Endpoint endpoint = SendspinAddress.endpointOf(receiverId);
        if (endpoint == null || !players.list().contains(endpoint)) {
            return null;
        }
        return deviceFor(endpoint, connections.get(endpoint));
    }

    private SendspinReceiverDevice deviceFor(Endpoint endpoint, HubConnection connection) {
        String announced = connection == null || connection.hello() == null ? null : connection.hello().name;
        String id = SendspinAddress.idOf(endpoint);
        SendspinReceiverDevice cached = devices.get(id);
        if (cached != null && cached.showsSame(announced)) {
            return cached;
        }
        SendspinReceiverDevice made = SendspinReceiverDevice.of(endpoint, announced);
        if (made != null) {
            devices.put(id, made);
        }
        return made;
    }

    @Override
    public void selectionChanged(Collection<String> receiverIds) {
        // the players are stored by the user, not by the selection: nothing to remember
    }
}
