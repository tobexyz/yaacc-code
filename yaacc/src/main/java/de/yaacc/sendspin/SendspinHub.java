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
import de.yaacc.sendspin.discovery.YaaccPeers;
import de.yaacc.sendspin.discovery.SendspinNsd;
import de.yaacc.sendspin.discovery.SendspinSelf;
import de.yaacc.sendspin.discovery.SendspinServerAdvert;
import de.yaacc.sendspin.discovery.SendspinServiceType;
import de.yaacc.sendspin.hub.Connector;
import de.yaacc.sendspin.hub.Endpoint;
import de.yaacc.sendspin.hub.GroupFormat;
import de.yaacc.sendspin.hub.HubClock;
import de.yaacc.sendspin.hub.HubConnection;
import de.yaacc.sendspin.hub.InboundServer;
import de.yaacc.sendspin.session.SendspinMessages;
import de.yaacc.multiroom.discovery.SnapcastAddressFilter;
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
    /** How many controllers (clients that are no players) the server serves at once. */
    private static final int MAX_CONTROLLERS = 4;

    private static SendspinHub shared;

    private final SharedPreferences preferences;
    private final StringSetStore store;
    private final SendspinPlayers players;
    private final SendspinPolicy approvals;
    private final SendspinControllers controllers;
    private final Connector connector;
    private final HubClock clock;
    private final SecureRandom random = new SecureRandom();
    private final SendspinDirectory directory = new SendspinDirectory(MonotonicClock.SYSTEM, SendspinDirectory.DEFAULT_GRACE_MILLIS);
    private final SendspinNsd nsd;
    private final SnapcastDiscoveryGate gate;
    private final SendspinDirectory serverDirectory = new SendspinDirectory(MonotonicClock.SYSTEM, SendspinDirectory.DEFAULT_GRACE_MILLIS);
    private final SendspinNsd serverNsd;
    private final SnapcastDiscoveryGate serverGate;
    private final SendspinNames names;
    private final SendspinGroups groups = new SendspinGroups();
    private final SendspinServerAdvert advert;
    private final SnapcastDiscoveryGate inboundGate;
    private InboundServer inboundServer;
    private final SharedPreferences.OnSharedPreferenceChangeListener settingListener;
    private final List<SendspinDirectory.Listener> listeners = new CopyOnWriteArrayList<>();
    private final Map<Endpoint, HubConnection> connections = new HashMap<>();
    private final Map<Endpoint, Long> lastAttempt = new HashMap<>();
    private final Map<Endpoint, Integer> rates = new HashMap<>();
    private final Map<String, SendspinReceiverDevice> devices = new HashMap<>();
    private Identity identity;
    private Identity clientIdentity;
    private final Context appContext;
    private de.yaacc.sendspin.control.ControllerHost controllerHost;

    private SendspinHub(Context context) {
        final Context app = context.getApplicationContext();
        this.appContext = app;
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
        this.approvals = new SendspinPolicy(store);
        this.names = new SendspinNames(store);
        this.connector = new Connector.Sockets();
        this.clock = HubClock.SYSTEM;
        this.controllers = new SendspinControllers(store, this::clientIdentity,
                () -> SendspinSettings.isUnpairedServersAllowed(preferences::getBoolean), serverDirectory::snapshot, connector, clock, random,
                this::controllerChanged);
        this.nsd = new SendspinNsd(app, directory);
        directory.addListener(this::fire);
        this.advert = new SendspinServerAdvert(app);
        this.inboundGate = new SnapcastDiscoveryGate(() -> SendspinSettings.isAdvertiseEnabled(preferences::getBoolean),
                new SnapcastDiscoveryGate.Discovery() {
                    @Override
                    public void start() {
                        startInbound();
                    }

                    @Override
                    public void stop() {
                        stopInbound();
                    }
                }, () -> { });
        this.serverNsd = new SendspinNsd(app, serverDirectory, SendspinServiceType.SERVER_TYPE);
        serverDirectory.addListener(this::fire);
        this.serverGate = new SnapcastDiscoveryGate(() -> SendspinSettings.isServerDiscoveryEnabled(preferences::getBoolean),
                new SnapcastDiscoveryGate.Discovery() {
                    @Override
                    public void start() {
                        serverNsd.start();
                    }

                    @Override
                    public void stop() {
                        serverNsd.stop();
                    }
                }, serverDirectory::clear);
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
            if (SendspinSettings.isServerDiscoveryRelevantKey(key)) {
                serverGate.settingChanged();
            }
            if (SendspinSettings.isAdvertiseRelevantKey(key)) {
                inboundGate.settingChanged();
            }
            if (SendspinSettings.KEY_PLAYER.equals(key)) {
                de.yaacc.sendspin.player.SendspinPlayerService.sync(app); // the phone as a player: the service follows the switch
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

    public boolean isControllerEnabled() {
        return SendspinSettings.isControllerEnabled(preferences::getBoolean);
    }

    /** The servers the phone controls (the controller role); the settings dialog edits their addresses. */
    public SendspinControllers controllers() {
        return controllers;
    }

    private synchronized Identity clientIdentity() {
        if (clientIdentity == null) {
            String stored = preferences.getString(SendspinSettings.KEY_CLIENT_IDENTITY, null);
            byte[] key = stored == null ? null : Base64Url.decode32(stored);
            if (key != null) {
                clientIdentity = Identity.fromPrivateKey(key);
            } else {
                clientIdentity = Identity.generate(random);
                preferences.edit().putString(SendspinSettings.KEY_CLIENT_IDENTITY, Base64Url.encode(clientIdentity.exportPrivateKey())).apply();
            }
        }
        return clientIdentity;
    }

    /** The players the user added; the settings dialog edits them. */
    public SendspinPlayers players() {
        return players;
    }

    public SendspinPolicy approvals() {
        return approvals;
    }

    // ------------------------------------------------------------------ discovery and change events

    /** A receivers page became visible: the search starts if Sendspin and the search are switched on. */
    public void pageStarted() {
        gate.pageStarted();
        serverGate.pageStarted();
        inboundGate.pageStarted();
    }

    /** A receivers page was hidden: the search stops when no page needs it. */
    public void pageStopped() {
        gate.pageStopped();
        serverGate.pageStopped();
        inboundGate.pageStopped();
    }

    /** Told when the players the app lists changed (one was announced or went, a name arrived). May be called on any thread. */
    public void addChangeListener(SendspinDirectory.Listener listener) {
        listeners.add(listener);
    }

    public void removeChangeListener(SendspinDirectory.Listener listener) {
        listeners.remove(listener);
    }

    // ------------------------------------------------------------------ players that connect to this phone

    /** The most players that may be connected to the listener at once. */
    private static final int MAX_INBOUND = 8;
    private static final int UPGRADE_TIMEOUT_MILLIS = 5000;

    /** Opens the listener and announces it (the gate calls this while a receivers page is visible and the switch is on). */
    private void startInbound() {
        InboundServer server = new InboundServer(SendspinAddress.DEFAULT_SERVER_PORT, "/sendspin", SendspinHub::acceptsInbound,
                this::acceptInbound, MAX_INBOUND, UPGRADE_TIMEOUT_MILLIS);
        try {
            server.start();
        } catch (java.io.IOException busy) {
            // the usual port is taken (another server on this phone): any free one, the announcement names it
            server = new InboundServer(0, "/sendspin", SendspinHub::acceptsInbound, this::acceptInbound, MAX_INBOUND,
                    UPGRADE_TIMEOUT_MILLIS);
            try {
                server.start();
            } catch (java.io.IOException failed) {
                de.yaacc.util.YaaccLogger.w(getClass().getName(), "Sendspin listener could not be opened");
                return;
            }
        }
        synchronized (this) {
            inboundServer = server;
        }
        advert.register(identity().id(), "YAACC", "/sendspin", server.port(), clientIdentity().id()); // the id joins it with the announcement of the phone's player
    }

    /** Who may connect to the listener: the local network, and never this phone itself (its own search finds its announcement). */
    static boolean acceptsInbound(java.net.InetAddress address) {
        return SnapcastAddressFilter.isAcceptedForDiscovery(address) && !SendspinSelf.isThisDevice(address);
    }

    private void stopInbound() {
        advert.unregister();
        InboundServer server;
        synchronized (this) {
            server = inboundServer;
            inboundServer = null;
        }
        if (server != null) {
            server.close(); // connections that are established stay
        }
    }

    /** A player connected to the listener: it is a connection like the others, except that nothing can dial it again. */
    private void acceptInbound(Connector.Link link, java.net.InetAddress remote) {
        String host = remote.getHostAddress();
        int scope = host.indexOf('%');
        if (scope >= 0) {
            host = host.substring(0, scope);
        }
        final Endpoint endpoint = Endpoint.inbound(host);
        synchronized (this) {
            if (!isEnabled()) {
                try {
                    link.close();
                } catch (java.io.IOException ignored) {
                    // closed
                }
                return;
            }
            HubConnection old = connections.remove(endpoint); // a player that comes back replaces the connection it still holds
            if (old != null) {
                old.close();
            }
            HubConnection made = new HubConnection(endpoint, identity(), approvals, (e, r) -> link, clock, random, SERVER_NAME,
                    this::connectionChanged, () -> SendspinSettings.isLegacyAllowed(preferences::getBoolean));
            connections.put(endpoint, made);
            made.setControllerHost(controllerHost());
            made.start();
        }
        fire();
    }

    /** The lists changed from outside (a server was removed in the settings): the pages redraw. */
    public void notifyListChanged() {
        fire();
    }

    private void fire() {
        for (SendspinDirectory.Listener listener : listeners) {
            listener.changed();
        }
        de.yaacc.sendspin.control.ControllerHost host;
        synchronized (this) {
            host = controllerHost;
        }
        if (host != null) {
            host.refreshAll(); // a controller sees a change of the groups or of the playback at once (the poll finds the rest)
        }
    }

    /**
     * The controller role of this server (K15), made when the first client that is no player connects: the playback of the app as the
     * groups of this hub. The same policy as for the players decides who may use it (paired, or the one setting for unpaired devices).
     */
    private synchronized de.yaacc.sendspin.control.ControllerHost controllerHost() {
        if (controllerHost == null) {
            controllerHost = new de.yaacc.sendspin.control.ControllerHost(
                    new de.yaacc.sendspin.control.ControllerLogic(new HubPlaybackBridge(appContext, this), SERVER_NAME, random), clock,
                    () -> SendspinSettings.isPlayerUnpairedAllowed(preferences::getBoolean), MAX_CONTROLLERS, new BitmapArtworkEncoder());
        }
        return controllerHost;
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
     * The endpoint a receiver id stands for, as the connections have it: a found player brings its own path, the id has the default one, and
     * the lookups of the groups have to meet the connection that is there (not open a second one under another path).
     */
    public synchronized Endpoint endpointOfId(String receiverId) {
        if (SendspinAddress.isSelfId(receiverId)) {
            return selfEndpoint();
        }
        Endpoint wanted = SendspinAddress.isSendspinId(receiverId) ? SendspinAddress.endpointOf(receiverId) : null;
        if (wanted == null) {
            return null;
        }
        String key = keyOf(wanted);
        for (Endpoint known : connections.keySet()) {
            if (keyOf(known).equals(key)) {
                return known;
            }
        }
        for (Listed player : listed()) {
            if (keyOf(player.endpoint).equals(key)) {
                return player.endpoint;
            }
        }
        return wanted;
    }

    /**
     * Where this phone's own player listens, as the hub dials it (over the loopback address, so that the phone can be a member of a
     * group that plays on it too); null while the player does not run. Never listed as a card of its own: the card of this phone is it.
     */
    public synchronized Endpoint selfEndpoint() {
        return selfEndpointOf(de.yaacc.sendspin.player.SendspinPlayerRuntime.port(), SendspinSettings.isPlayerEnabled(preferences::getBoolean));
    }

    static Endpoint selfEndpointOf(int playerPort, boolean playerEnabled) {
        return playerPort <= 0 || !playerEnabled ? null : new Endpoint("127.0.0.1", playerPort, "/sendspin");
    }

    /** The connection of a receiver id; made (as a list refresh does, with its pause and the end reasons) if {@code create}, else only found. */
    public synchronized HubConnection connectionById(String receiverId, boolean create) {
        Endpoint endpoint = endpointOfId(receiverId);
        if (endpoint == null) {
            return null;
        }
        return create ? acquire(endpoint, false) : connections.get(endpoint);
    }

    /**
     * The players in the order of their importance (found, then added, then remembered), one per address and port, at most
     * {@value SendspinSettings#MAX_LISTED}. A found player brings its own path; the others use the default one.
     */
    /**
     * Whether a connection that came to the listener is shown as a receiver: only when it said it is a player. Until its hello is
     * read it is not known what it is, and a client that is no player (the controller of another phone, which also finds this
     * listener) has nothing to play on, so it gets no card.
     */
    static boolean isListedInbound(SendspinMessages.ClientHello hello) {
        return hello != null && hello.supportsPlayer();
    }

    private synchronized List<Listed> listed() {
        Map<String, Listed> byKey = new LinkedHashMap<>();
        for (Map.Entry<Endpoint, HubConnection> inbound : new ArrayList<>(connections.entrySet())) {
            if (inbound.getKey().isInbound() && inbound.getValue().phase() != HubConnection.Phase.ENDED) {
                HubConnection c = inbound.getValue();
                if (isListedInbound(c.hello())) {
                    byKey.put(keyOf(inbound.getKey()), new Listed(inbound.getKey(), c.hello().name));
                }
            }
        }
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
        if (endpoint.isInbound()) {
            return current != null && current.phase() != HubConnection.Phase.ENDED ? current : null; // it has to come back by itself
        }
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
        HubConnection made = new HubConnection(endpoint, identity(), approvals, connector, clock, random, SERVER_NAME, this::connectionChanged,
                () -> SendspinSettings.isLegacyAllowed(preferences::getBoolean));
        connections.put(endpoint, made);
        made.start();
        return made;
    }

    /** A connection changed: if the player's name is now known and the list shows another, the lists are told. Connection thread. */
    /** A session of the controller changed: only a name that arrived makes the lists redraw (the rows poll the rest). */
    private void controllerChanged(de.yaacc.sendspin.client.ControllerSession session) {
        boolean stale = false;
        synchronized (this) {
            SendspinReceiverDevice device = devices.get(SendspinAddress.serverIdOf(session.endpoint()));
            if (device != null && !device.showsSame(session.serverName())) {
                stale = true;
            }
        }
        if (stale) {
            fire();
        }
    }

    /** The last name the player with this id announced, or null (for the lists of paired and approved players). */
    public String nameOfPlayer(String clientId) {
        return names.nameOf(clientId);
    }

    private void connectionChanged(HubConnection connection) {
        if (connection.clientId() != null && connection.hello() != null) {
            names.remember(connection.clientId(), connection.hello().name);
        }
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

    // ------------------------------------------------------------------ pairing and approvals

    /** Enters the pairing token of a device that has not connected yet (K15: one pairing for every role of a device). */
    public SendspinPolicy.PairOutcome addDeviceToken(String tokenText) {
        SendspinPolicy.PairOutcome outcome = approvals.addDeviceToken(tokenText);
        if (outcome == SendspinPolicy.PairOutcome.STARTED) {
            fire();
        }
        return outcome;
    }

    /** The id the player at this endpoint presented, null while there is no connection (for the message of the pairing dialog). */
    public synchronized String connectedClientId(Endpoint endpoint) {
        HubConnection current = connections.get(endpoint);
        return current == null ? null : current.clientId();
    }

    /**
     * The operator pasted a pairing token for the player at this endpoint (the dialog of its row). On {@code STARTED} the connection
     * is made again so that the pairing runs now.
     */
    public synchronized SendspinPolicy.PairOutcome pair(Endpoint endpoint, String tokenText) {
        HubConnection current = connections.get(endpoint);
        SendspinPolicy.PairOutcome outcome = approvals.pairWithToken(tokenText, current == null ? null : current.clientId());
        if (outcome == SendspinPolicy.PairOutcome.STARTED) {
            disconnect(endpoint);
            if (!endpoint.isInbound()) {
                acquire(endpoint, true); // a player that connected to us has to come back by itself, now under the Pairing PSK
            }
        }
        return outcome;
    }

    // ------------------------------------------------------------------ linking (groups)

    /** What linking two players did. */
    public enum LinkOutcome {
        LINKED, ALREADY, NOT_KNOWN, NEEDS_PAIRING, NO_COMMON_FORMAT, FULL, REFUSED
    }

    /** The groups of the players (not persisted). */
    public SendspinGroups groups() {
        return groups;
    }

    private boolean needsPairing(HubConnection connection) {
        SendspinRowText.State state = SendspinRowText.stateOf(connection.phase(), connection.endReason(), connection.pairingFailure(),
                connection.isPaired(), false, false);
        return SendspinRowText.offersPairing(state);
    }

    /**
     * Links a player into the group of a leader (the owner's decision: every player that is found can be linked, and linking is the
     * operator action that approves an unpaired player, as playing to it does). Refused if the group would have no common PCM rate or
     * if a player demands a pairing that is not done.
     */
    public synchronized LinkOutcome link(String leaderId, String memberId) {
        if (SendspinAddress.isSelfId(leaderId)) {
            return LinkOutcome.REFUSED; // the card of this phone leads nothing: it is played to, never from
        }
        Endpoint leader = endpointOfId(leaderId);
        Endpoint member = endpointOfId(memberId);
        if (leader == null || member == null) {
            return LinkOutcome.NOT_KNOWN;
        }
        HubConnection leaderConnection = acquire(leader, true);
        HubConnection memberConnection = acquire(member, true);
        if (leaderConnection == null || memberConnection == null || leaderConnection.hello() == null || memberConnection.hello() == null) {
            return LinkOutcome.NOT_KNOWN;
        }
        if (needsPairing(leaderConnection) || needsPairing(memberConnection)) {
            return LinkOutcome.NEEDS_PAIRING;
        }
        List<java.util.Set<Integer>> rates = new ArrayList<>();
        rates.add(leaderConnection.supportedRates());
        for (String other : groups.membersOf(leaderId)) {
            HubConnection c = connectionOfId(other);
            if (c != null && !other.equals(memberId)) {
                rates.add(c.supportedRates());
            }
        }
        rates.add(memberConnection.supportedRates());
        if (GroupFormat.commonRate(rates) == 0) {
            return LinkOutcome.NO_COMMON_FORMAT;
        }
        SendspinGroups.Result result = groups.link(leaderId, memberId);
        switch (result) {
            case LINKED:
                if (!memberConnection.isPaired()) {
                    memberConnection.approve(); // linking a player is the operator's action that means to use it
                }
                if (!leaderConnection.isPaired()) {
                    leaderConnection.approve();
                }
                fire();
                return LinkOutcome.LINKED;
            case ALREADY:
                return LinkOutcome.ALREADY;
            case FULL:
                return LinkOutcome.FULL;
            default:
                return LinkOutcome.REFUSED;
        }
    }

    public synchronized void unlink(String memberId) {
        groups.unlink(memberId);
        fire();
    }

    public synchronized void dissolve(String leaderId) {
        groups.dissolve(leaderId);
        fire();
    }

    private HubConnection connectionOfId(String receiverId) {
        return connectionById(receiverId, false);
    }

    /** The sample rate of the group of a leader (or of a lone player): the common rate of its members, 0 if there is none or it is not known yet. */
    public synchronized int groupRateOf(String leaderId) {
        List<java.util.Set<Integer>> rates = new ArrayList<>();
        HubConnection leader = connectionOfId(leaderId);
        if (leader == null) {
            return 0;
        }
        rates.add(leader.supportedRates());
        for (String member : groups.membersOf(leaderId)) {
            HubConnection c = connectionOfId(member);
            if (c == null) {
                continue; // a member that is not connected now joins later, if it can use the rate
            }
            rates.add(c.supportedRates());
        }
        return GroupFormat.commonRate(rates);
    }

    /** The average volume of the group of a leader, or null if none of its players reports one. */
    public synchronized Integer groupVolumeOf(String leaderId) {
        int sum = 0;
        int count = 0;
        List<String> all = new ArrayList<>();
        all.add(leaderId);
        all.addAll(groups.membersOf(leaderId));
        for (String id : all) {
            HubConnection c = connectionOfId(id);
            SendspinMessages.ClientState s = c == null ? null : c.state();
            if (s != null && s.player != null && s.player.volume != null) {
                sum += s.player.volume;
                count++;
            }
        }
        return count == 0 ? null : Math.round(sum / (float) count);
    }

    /**
     * Sets the volume of the group as the protocol's controller role does (see {@link GroupVolume}): every player is moved by the same number of points the average moves, what a player at a boundary could not take is shared out,
     * limited to 0 to 100. A lone player gets the value itself.
     */
    public synchronized void setGroupVolume(String leaderId, int percent) {
        List<String> all = new ArrayList<>();
        all.add(leaderId);
        all.addAll(groups.membersOf(leaderId));
        List<HubConnection> withVolume = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        for (String id : all) {
            HubConnection c = connectionOfId(id);
            SendspinMessages.ClientState s = c == null ? null : c.state();
            if (s != null && s.player != null && s.player.volume != null) {
                withVolume.add(c);
                current.add(s.player.volume);
            }
        }
        int[] levels = new int[current.size()];
        for (int i = 0; i < levels.length; i++) {
            levels[i] = current.get(i);
        }
        int[] targets = GroupVolume.targets(levels, percent); // the delta rule of the protocol, with what a clamped player could not take shared out
        for (int i = 0; i < targets.length; i++) {
            withVolume.get(i).setVolume(targets[i]);
        }
    }

    /** Mutes or unmutes every player of the group of a leader. */
    public synchronized void setGroupMuted(String leaderId, boolean muted) {
        List<String> all = new ArrayList<>();
        all.add(leaderId);
        all.addAll(groups.membersOf(leaderId));
        for (String id : all) {
            HubConnection c = connectionOfId(id);
            if (c != null) {
                c.setMuted(muted);
            }
        }
    }

    /** True if every player of the group of a leader that reports it is muted. */
    public synchronized boolean groupMutedOf(String leaderId) {
        List<String> all = new ArrayList<>();
        all.add(leaderId);
        all.addAll(groups.membersOf(leaderId));
        boolean any = false;
        for (String id : all) {
            HubConnection c = connectionOfId(id);
            SendspinMessages.ClientState s = c == null ? null : c.state();
            if (s != null && s.player != null && s.player.muted != null) {
                if (!s.player.muted) {
                    return false;
                }
                any = true;
            }
        }
        return any;
    }

    // ------------------------------------------------------------------ pairing as a client (the controller)

    /**
     * The pairing token of this app for the user to give to a server (Music Assistant or another): made at the first call and the
     * same until a pairing used it up or the user withdraws it.
     */
    public String clientPairingToken() {
        return controllers.credentials().token(clientIdentity());
    }

    /** The key pair of this app as a Sendspin client (the controller and the player use the same). */
    public Identity clientKeys() {
        return clientIdentity();
    }

    /** The pairing records of this app as a client. */
    public SendspinClientPairings clientPairings() {
        return controllers.credentials();
    }

    public boolean holdsClientToken() {
        return controllers.credentials().holdsPairingPsk();
    }

    public void withdrawClientToken() {
        controllers.credentials().withdrawToken();
        fire();
    }

    public boolean isPairedWithServer(String serverId) {
        return serverId != null && controllers.credentials().isPairedWith(serverId);
    }

    /** Forgets the pairing with a server and ends the session with it (the server's list entry is untouched). */
    public synchronized void unpairServer(String serverId) {
        controllers.credentials().unpair(serverId);
        controllers.endSessionsOf(serverId);
        fire();
    }

    /** The ids of the paired players, sorted. */
    public List<String> pairedIds() {
        return approvals.pairedIds();
    }

    /** The ids of the players the operator approved while they are not paired, sorted. */
    public List<String> approvedIds() {
        return approvals.approvedIds();
    }

    /** Forgets the pairing (and a pending token) of a player and ends the connections to it; they are made again by the list. */
    public synchronized void unpair(String clientId) {
        approvals.unpair(clientId);
        endConnectionsOf(clientId);
        fire();
    }

    /** Withdraws the approval of a player and ends the connections to it. */
    public synchronized void revokeApproval(String clientId) {
        approvals.revoke(clientId);
        endConnectionsOf(clientId);
        fire();
    }

    private void endConnectionsOf(String clientId) {
        for (Endpoint endpoint : new ArrayList<>(connections.keySet())) {
            HubConnection connection = connections.get(endpoint);
            if (connection != null && clientId.equals(connection.clientId())) {
                disconnect(endpoint);
            }
        }
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
        groups.remove(SendspinAddress.idOf(endpoint));
        disconnect(endpoint);
        players.remove(endpoint);
        devices.remove(SendspinAddress.idOf(endpoint));
    }

    // ------------------------------------------------------------------ SendspinReceiverSource

    @Override
    public synchronized List<SendspinReceiverDevice> receivers() {
        List<SendspinReceiverDevice> result = new ArrayList<>();
        Set<String> current = new HashSet<>();
        if (!isEnabled()) {
            closeAll(); // switched off: nothing stays connected
        } else {
            for (Listed player : listed()) {
                SendspinReceiverDevice device = deviceFor(player, acquire(player.endpoint, false));
                if (device != null) {
                    result.add(device);
                    current.add(device.getReceiverId());
                }
            }
        }
        if (!isControllerEnabled()) {
            controllers.closeAll();
        } else {
            List<SendspinDirectory.Entry> shownPlayers = shownPlayerEntries();
            for (Endpoint endpoint : controllers.listed()) {
                controllers.acquire(endpoint, false); // the session stays warm also when its card is merged into the player's
                if (isMergedServer(endpoint, shownPlayers)) {
                    continue; // one card per YAACC phone: its server is inside the card of its player (K14)
                }
                SendspinReceiverDevice device = serverDeviceFor(endpoint);
                if (device != null) {
                    result.add(device);
                    current.add(device.getReceiverId());
                }
            }
        }
        if (SendspinSettings.isPlayerEnabled(preferences::getBoolean)) {
            SendspinReceiverDevice self = selfDevice();
            if (self != null) {
                result.add(self);
                current.add(self.getReceiverId());
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

    /** The announced players that are listed as a card now. */
    private List<SendspinDirectory.Entry> shownPlayerEntries() {
        Set<String> shown = new HashSet<>();
        for (Listed player : listed()) {
            shown.add(keyOf(player.endpoint));
        }
        List<SendspinDirectory.Entry> result = new ArrayList<>();
        for (SendspinDirectory.Entry entry : directory.snapshot()) {
            if (shown.contains(keyOf(entry.endpoint()))) {
                result.add(entry);
            }
        }
        return result;
    }

    private boolean isMergedServer(Endpoint server, List<SendspinDirectory.Entry> shownPlayers) {
        for (SendspinDirectory.Entry entry : serverDirectory.snapshot()) {
            if (entry.endpoint().equals(server)) {
                return YaaccPeers.isMergedServer(entry, shownPlayers);
            }
        }
        return false;
    }

    /**
     * The server of the same YAACC phone as this player, if both announced it and this phone controls servers: the card of the player
     * then has a remote control for what that phone plays (K14). Null otherwise.
     */
    public synchronized Endpoint remoteControlOf(Endpoint player) {
        if (!isControllerEnabled()) {
            return null;
        }
        for (SendspinDirectory.Entry entry : directory.snapshot()) {
            if (keyOf(entry.endpoint()).equals(keyOf(player))) {
                Endpoint server = YaaccPeers.serverOf(entry, serverDirectory.snapshot());
                return server != null && controllers.listed().contains(server) ? server : null;
            }
        }
        return null;
    }

    /** The card of this phone as a player: one object while its name stays the same (the lists redraw a row when the object changes). */
    private SendspinReceiverDevice selfDevice() {
        String name = de.yaacc.sendspin.player.SendspinPlayerService.phoneName();
        SendspinReceiverDevice cached = devices.get(SendspinAddress.SELF_ID);
        if (cached != null && cached.showsSame(name)) {
            return cached;
        }
        SendspinReceiverDevice made = SendspinReceiverDevice.ofSelf(name);
        if (made != null) {
            devices.put(SendspinAddress.SELF_ID, made);
        }
        return made;
    }

    private SendspinReceiverDevice serverDeviceFor(Endpoint endpoint) {
        String announced = controllers.announcedName(endpoint);
        if (announced == null || announced.isEmpty()) {
            announced = controllers.foundName(endpoint); // the name of the mDNS announcement until the server says its own
        }
        String id = SendspinAddress.serverIdOf(endpoint);
        SendspinReceiverDevice cached = devices.get(id);
        if (cached != null && cached.showsSame(announced) && cached.getEndpoint().equals(endpoint)) {
            return cached;
        }
        SendspinReceiverDevice made = SendspinReceiverDevice.ofServer(endpoint, announced);
        if (made != null) {
            devices.put(id, made);
        }
        return made;
    }

    @Override
    public synchronized SendspinReceiverDevice findReceiver(String receiverId) {
        if (SendspinAddress.isSelfId(receiverId)) {
            return SendspinSettings.isPlayerEnabled(preferences::getBoolean) ? selfDevice() : null;
        }
        if (SendspinAddress.isServerId(receiverId)) {
            Endpoint server = SendspinAddress.serverEndpointOf(receiverId);
            if (server == null || !isControllerEnabled() || !controllers.listed().contains(server)) {
                return null;
            }
            return serverDeviceFor(server);
        }
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
                if (endpoint != null && !endpoint.isInbound() && selected.size() < SendspinSettings.MAX_LISTED) {
                    selected.add(SendspinAddress.format(endpoint));
                }
            }
        }
        if (!selected.equals(store.get(SendspinSettings.KEY_REMEMBERED))) {
            store.put(SendspinSettings.KEY_REMEMBERED, selected);
        }
    }
}
