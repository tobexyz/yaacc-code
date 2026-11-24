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

import de.yaacc.multiroom.SnapcastDiscoveryGate;
import de.yaacc.multiroom.control.MonotonicClock;
import de.yaacc.sendspin.discovery.SendspinDirectory;
import de.yaacc.sendspin.discovery.SendspinNsd;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The Sendspin players of the app (issue #325): the players the user added by address, the ones the network announced (mDNS
 * {@code _sendspin._tcp}, while a receivers page is open and the search is on) and the ones that were selected in the last run (kept
 * until they are seen again), one {@link HubConnection} for each while the switch is on, the operator's approvals and the identity of
 * this server. The app part of the hub is this class; everything it decides is in the pure classes of this package and of
 * {@code de.yaacc.sendspin.hub} and {@code .discovery}.
 * <p>
 * A connection is made when the receivers are listed (it gives the row its state and the player its stream format, which the audio
 * tap needs before ExoPlayer is built) and again when the user plays to a player. It does not give up the player's other servers:
 * with no activities a connection ranks lowest and is displaced by any server that plays. After the guidance of
 * {@code client/goodbye} a connection that ended because the player went to another server or demands pairing is not made again
 * by a list refresh, only by the user; the others are retried after a pause. At most {@value SendspinSettings#MAX_LISTED} players
 * are listed and connected to.
 * <p>
 * Not yet: the Keystore wrapping of the identity key (it lives in the app-private preferences; backup is off), pairing, groups, the
 * client-initiated connection mode.
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
    private final SendspinDirectory directory = new SendspinDirectory(MonotonicClock.SYSTEM, SendspinDirectory.DEFAULT_GRACE_MILLIS);
    private final SendspinNsd nsd;
    private final SnapcastDiscoveryGate gate;
    private final SharedPreferences.OnSharedPreferenceChangeListener settingListener;
    private final List<SendspinDirectory.Listener> listeners = new CopyOnWriteArrayList<>();
    private final Map<Endpoint, HubConnection> connections = new HashMap<>();
    private final Map<Endpoint, Long> lastAttempt = new HashMap<>();
    private final Map<Endpoint, Integer> rates = new HashMap<>();
    private final Map<String, SendspinReceiverDevice> devices = new HashMap<>();
    private Identity identity;

    private SendspinHub(Context context) {
        final Context app = context.getApplicationContext();
        this.preferences = PreferenceManager.getDefaultSharedPreferences(app);
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
        this.nsd = new SendspinNsd(app, directory);
        directory.addListener(this::fire);
        this.gate = new SnapcastDiscoveryGate(() -> SendspinSettings.isDiscoveryEnabled(preferences::getBoolean),
                new SnapcastDiscoveryGate.Discovery() {
                    @Override
                    public void start() {
                        nsd.start();
                    }

                    @Override
                    public void stop() {
                        nsd.stop();
                    }
                }, directory::clear);
        this.settingListener = (changed, key) -> {
            if (SendspinSettings.isDiscoveryRelevantKey(key)) {
                gate.settingChanged();
            }
        };
        preferences.registerOnSharedPreferenceChangeListener(settingListener);
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

    // ------------------------------------------------------------------ discovery and change events

    /** A receivers page became visible: the search starts if Sendspin and the search are switched on. */
    public void pageStarted() {
        gate.pageStarted();
    }

    /** A receivers page was hidden: the search stops when no page needs it. */
    public void pageStopped() {
        gate.pageStopped();
    }

    /** Told when the players the app lists changed (one was announced or went, a name arrived). May be called on any thread. */
    public void addChangeListener(SendspinDirectory.Listener listener) {
        listeners.add(listener);
    }

    public void removeChangeListener(SendspinDirectory.Listener listener) {
        listeners.remove(listener);
    }

    private void fire() {
        for (SendspinDirectory.Listener listener : listeners) {
            listener.changed();
        }
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

    // ------------------------------------------------------------------ the players that are listed

    /** A player that is listed: where it is and the name its announcement gave. */
    private static final class Listed {
        final Endpoint endpoint;
        final String announcedName;

        Listed(Endpoint endpoint, String announcedName) {
            this.endpoint = endpoint;
            this.announcedName = announcedName;
        }
    }

    private static String keyOf(Endpoint endpoint) {
        return endpoint.host() + ":" + endpoint.port();
    }

    /**
     * The players in the order of their importance (found, then added, then remembered), one per address and port, at most
     * {@value SendspinSettings#MAX_LISTED}. A found player brings its own path; the others use the default one.
     */
    private synchronized List<Listed> listed() {
        Map<String, Listed> byKey = new LinkedHashMap<>();
        for (SendspinDirectory.Entry entry : directory.snapshot()) {
            byKey.put(keyOf(entry.endpoint()), new Listed(entry.endpoint(), entry.name()));
        }
        for (Endpoint endpoint : players.list()) {
            if (!byKey.containsKey(keyOf(endpoint))) {
                byKey.put(keyOf(endpoint), new Listed(endpoint, null));
            }
        }
        for (String text : new TreeSet<>(store.get(SendspinSettings.KEY_REMEMBERED))) {
            Endpoint endpoint = SendspinAddress.parse(text);
            if (endpoint != null && !byKey.containsKey(keyOf(endpoint))) {
                byKey.put(keyOf(endpoint), new Listed(endpoint, null));
            }
        }
        List<Listed> result = new ArrayList<>(byKey.values());
        while (result.size() > SendspinSettings.MAX_LISTED) {
            result.remove(result.size() - 1);
        }
        return result;
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
        HubConnection made = new HubConnection(endpoint, identity(), approvals, connector, clock, random, SERVER_NAME, this::connectionChanged);
        connections.put(endpoint, made);
        made.start();
        return made;
    }

    /** A connection changed: if the player's name is now known and the list shows another, the lists are told. Connection thread. */
    private void connectionChanged(HubConnection connection) {
        boolean stale = false;
        synchronized (this) {
            SendspinReceiverDevice device = devices.get(SendspinAddress.idOf(connection.endpoint()));
            if (device != null && connection.hello() != null && !device.showsSame(connection.hello().name)) {
                stale = true;
            }
        }
        if (stale) {
            fire();
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

    /** Removes an added player from the list and ends its connection (a player that is announced stays while it is announced). */
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
        for (Listed player : listed()) {
            SendspinReceiverDevice device = deviceFor(player, acquire(player.endpoint, false));
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
        Endpoint wanted = SendspinAddress.endpointOf(receiverId);
        if (wanted == null) {
            return null;
        }
        for (Listed player : listed()) {
            if (keyOf(player.endpoint).equals(keyOf(wanted))) {
                return deviceFor(player, connections.get(player.endpoint));
            }
        }
        return null;
    }

    private SendspinReceiverDevice deviceFor(Listed player, HubConnection connection) {
        String announced = connection != null && connection.hello() != null && !connection.hello().name.isEmpty()
                ? connection.hello().name : player.announcedName;
        String id = SendspinAddress.idOf(player.endpoint);
        SendspinReceiverDevice cached = devices.get(id);
        if (cached != null && cached.showsSame(announced) && cached.getEndpoint().equals(player.endpoint)) {
            return cached;
        }
        SendspinReceiverDevice made = SendspinReceiverDevice.of(player.endpoint, announced);
        if (made != null) {
            devices.put(id, made);
        }
        return made;
    }

    /**
     * The selection changed: the selected Sendspin players are remembered (and the others forgotten), so that a selected player that
     * was only found by the search is still listed after a restart, until it is seen again.
     */
    @Override
    public void selectionChanged(Collection<String> receiverIds) {
        Set<String> selected = new HashSet<>();
        if (receiverIds != null) {
            for (String id : receiverIds) {
                Endpoint endpoint = SendspinAddress.endpointOf(id);
                if (endpoint != null && selected.size() < SendspinSettings.MAX_LISTED) {
                    selected.add(SendspinAddress.format(endpoint));
                }
            }
        }
        if (!selected.equals(store.get(SendspinSettings.KEY_REMEMBERED))) {
            store.put(SendspinSettings.KEY_REMEMBERED, selected);
        }
    }
}
