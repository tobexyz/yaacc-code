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

import de.yaacc.util.YaaccLogger;
import de.yaacc.sendspin.hub.HubClock;
import de.yaacc.sendspin.session.CommandThread;
import de.yaacc.sendspin.session.ControllerServerMessages;
import de.yaacc.sendspin.session.ProtocolException;
import de.yaacc.sendspin.session.SecureChannel;
import de.yaacc.sendspin.session.SendspinMessages;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * The controller role of the server on the wire (issue #325, K15, spec {@code controller-server.md}): the sessions of the controllers
 * that are connected, the state they are told when the app's playback changes and the commands they send. What a controller sees and
 * what its commands do is {@link ControllerLogic}; the handshake, the pairing and the activation are the connection's
 * ({@code HubConnection}). A change that the app does not announce is found by a poll every two seconds, but only while a controller is
 * connected.
 */
public final class ControllerHost implements AutoCloseable {

    private static final long POLL_MILLIS = 2000L;

    private final ControllerLogic logic;
    private final HubClock clock;
    private final BooleanSupplier unpairedAllowed;
    private final int maxSessions;
    private final ArtworkEncoder encoder;
    private final List<Session> sessions = new CopyOnWriteArrayList<>();
    private Thread poller;
    private volatile boolean closed;

    /**
     * @param unpairedAllowed the setting that lets a device that is not paired use the phone (read at each connection)
     * @param maxSessions     how many controllers at most are served at once
     */
    public ControllerHost(ControllerLogic logic, HubClock clock, BooleanSupplier unpairedAllowed, int maxSessions) {
        this(logic, clock, unpairedAllowed, maxSessions, null);
    }

    /** @param encoder makes the images of the artwork role; null: the role is not offered */
    public ControllerHost(ControllerLogic logic, HubClock clock, BooleanSupplier unpairedAllowed, int maxSessions, ArtworkEncoder encoder) {
        this.encoder = encoder;
        this.logic = logic;
        this.clock = clock;
        this.unpairedAllowed = unpairedAllowed;
        this.maxSessions = maxSessions;
    }

    /** Whether a controller that is not paired may be served. */
    public boolean unpairedAllowed() {
        return unpairedAllowed.getAsBoolean();
    }

    /** @return the session of a controller with these roles, or null if there is no room or the host is closed */
    public synchronized Session open(SecureChannel channel, SendspinMessages.ClientHello hello) {
        if (closed || sessions.size() >= maxSessions) {
            return null;
        }
        Session session = new Session(channel, hello.supportsController(), hello.supportsMetadata(),
                hello.supportsArtwork() && encoder != null ? new ArtworkFeed(channel, encoder, clock) : null);
        sessions.add(session);
        if (poller == null) {
            poller = new Thread(this::poll, "sendspin-controller-poll");
            poller.setDaemon(true);
            poller.start();
        }
        return session;
    }

    /** The app's playback changed: every controller is told what differs. Safe to call from any thread (not blocking the caller). */
    public void refreshAll() {
        if (sessions.isEmpty()) {
            return;
        }
        CommandThread.run(() -> {
            for (Session s : sessions) {
                s.refresh();
            }
        });
    }

    private void poll() {
        try {
            while (!closed) {
                Thread.sleep(POLL_MILLIS);
                synchronized (this) {
                    if (sessions.isEmpty()) {
                        poller = null;
                        return;
                    }
                }
                for (Session s : sessions) {
                    s.refresh();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        sessions.clear();
        if (poller != null) {
            poller.interrupt();
            poller = null;
        }
    }

    /** One controller: its roles, the group it is on and what it was last told. */
    public final class Session {
        private final SecureChannel channel;
        private final boolean controllerRole;
        private final boolean metadataRole;
        private final ControllerLogic.Attachment attachment = new ControllerLogic.Attachment();
        private ControllerLogic.View toldGroup;
        private ControllerLogic.View toldController;
        private ControllerLogic.View toldMetadata;
        private final ArtworkFeed artwork;
        private boolean open = true;

        Session(SecureChannel channel, boolean controllerRole, boolean metadataRole, ArtworkFeed artwork) {
            this.channel = channel;
            this.controllerRole = controllerRole;
            this.metadataRole = metadataRole;
            this.artwork = artwork;
        }

        /** The roles to activate: those of controller and metadata that the client listed. */
        public List<String> activeRoles() {
            List<String> roles = new ArrayList<>();
            if (controllerRole) {
                roles.add("controller@v1");
            }
            if (metadataRole) {
                roles.add("metadata@v1");
            }
            if (artwork != null) {
                roles.add("artwork@v1");
            }
            return roles;
        }

        /** After the activation: the group and the full state of each active role (the protocol asks for it promptly). */
        public synchronized void start() throws IOException {
            ControllerLogic.View view = logic.view(attachment, clock.nowMicros());
            channel.sendJson(SendspinMessages.groupUpdate(view.playing, view.groupId, view.groupName));
            channel.sendJson(ControllerServerMessages.serverState(controllerRole ? view.controller : null, metadataRole ? view.metadata : null));
            toldGroup = view;
            toldController = view;
            toldMetadata = view;
            if (artwork != null) {
                artwork.imageUrl(view.metadata.artworkUrl());
            }
        }

        /** A {@code client/state} of the controller: the artwork channels it wants (null: it declared none) and whether it is available. */
        public void clientState(java.util.List<de.yaacc.sendspin.session.ControllerMessages.ArtworkChannel> channels, boolean available) {
            if (artwork != null && channels != null) {
                artwork.configure(channels, available);
            }
        }

        /** A {@code client/command}: carried out off the connection's thread; a malformed one is ignored, not fatal. */
        public void command(final JSONObject payload) {
            if (!controllerRole) {
                return; // the controller role is not active: the protocol says such a command is not valid, it is ignored
            }
            CommandThread.run(() -> {
                try {
                    ControllerServerMessages.Command command = ControllerServerMessages.parseCommand(payload);
                    ControllerLogic.Outcome outcome;
                    synchronized (Session.this) {
                        outcome = logic.handle(attachment, command);
                    }
                    if (outcome == ControllerLogic.Outcome.FAILED) {
                        YaaccLogger.d(getClass().getName(), "a controller command failed");
                    }
                    refresh();
                } catch (ProtocolException ignored) {
                    // a malformed command changes nothing
                }
            });
        }

        /** Tells the controller what changed since it was told last. */
        public synchronized void refresh() {
            if (!open) {
                return;
            }
            try {
                ControllerLogic.View view = logic.view(attachment, clock.nowMicros());
                if (artwork != null) {
                    artwork.imageUrl(view.metadata.artworkUrl());
                }
                if (view.groupChangedSince(toldGroup)) {
                    channel.sendJson(SendspinMessages.groupUpdate(view.playing, view.groupId, view.groupName));
                    toldGroup = view;
                }
                boolean controller = controllerRole && view.controllerChangedSince(toldController);
                boolean metadata = metadataRole && view.metadataChangedSince(toldMetadata);
                if (controller || metadata) {
                    channel.sendJson(ControllerServerMessages.serverState(controller ? view.controller : null, metadata ? view.metadata : null));
                    if (controller) {
                        toldController = view;
                    }
                    if (metadata) {
                        toldMetadata = view;
                    }
                }
            } catch (IOException e) {
                open = false; // the connection's own loop ends the session
            }
        }

        public synchronized void close() {
            open = false;
            if (artwork != null) {
                artwork.close();
            }
            sessions.remove(this);
        }
    }
}
