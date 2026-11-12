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
package de.yaacc.multiroom.client;

import de.yaacc.multiroom.control.SnapcastClient;
import de.yaacc.multiroom.control.SnapcastDisplayText;
import de.yaacc.multiroom.control.SnapcastGroup;
import de.yaacc.multiroom.control.SnapcastServerStatus;
import de.yaacc.multiroom.control.SnapcastStream;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * What the server says about this phone's client, read from {@code Server.GetStatus} (read-only): the group the client
 * is in, the stream of that group and its state, the other streams. The owner's failing restart (no chunk for 20 s with a
 * working connection) was exactly this: the server had put the phone in a group of its own on the idle stream "Default"
 * while the playing stream had the other groups. Immutable; the texts are cleaned by the control library
 * ({@link SnapcastDisplayText}) and cut here, nothing of the server is executed or logged.
 */
public final class ServerSays {

    private static final int MAX_TEXT = 40;
    private static final int MAX_STREAMS = 12;

    /** One stream of the server: the id (to move a group onto it) and its state. */
    public static final class Stream {
        private final String id;
        private final String status;

        Stream(String id, String status) {
            this.id = id;
            this.status = status;
        }

        public String getId() {
            return id;
        }

        public String getStatus() {
            return status;
        }

        public boolean isPlaying() {
            return "playing".equals(status);
        }
    }

    private final InetAddress address;
    private final String clientId;
    private final boolean reached;
    private final boolean clientFound;
    private final boolean clientConnected;
    private final int volume;
    private final boolean clientMuted;
    private final String groupId;
    private final boolean groupMuted;
    private final String streamId;
    private final String streamStatus;
    private final int clientsInGroup;
    private final int groups;
    private final List<Stream> streams;

    private ServerSays(InetAddress address, String clientId, boolean reached, boolean clientFound, boolean clientConnected,
                       int volume, boolean clientMuted, String groupId, boolean groupMuted, String streamId,
                       String streamStatus, int clientsInGroup, int groups, List<Stream> streams) {
        this.address = address;
        this.clientId = clientId;
        this.reached = reached;
        this.clientFound = clientFound;
        this.clientConnected = clientConnected;
        this.volume = volume;
        this.clientMuted = clientMuted;
        this.groupId = groupId;
        this.groupMuted = groupMuted;
        this.streamId = streamId;
        this.streamStatus = streamStatus;
        this.clientsInGroup = clientsInGroup;
        this.groups = groups;
        this.streams = streams;
    }

    /** The control port could not be asked (not open, refused, timed out). */
    public static ServerSays unreachable(InetAddress address, String clientId) {
        return new ServerSays(address, clientId, false, false, false, 0, false, "", false, "", "", 0, 0,
                Collections.<Stream>emptyList());
    }

    /** Reads the status for this client id. */
    public static ServerSays of(SnapcastServerStatus status, InetAddress address, String clientId) {
        List<Stream> streams = new ArrayList<>();
        for (SnapcastStream stream : status.getStreams()) {
            if (streams.size() < MAX_STREAMS) {
                streams.add(new Stream(stream.getId(), SnapcastDisplayText.of(stream.getStatus(), MAX_TEXT)));
            }
        }
        SnapcastGroup group = status.findGroupOfClient(clientId);
        SnapcastClient client = status.findClient(clientId);
        if (group == null || client == null) {
            return new ServerSays(address, clientId, true, false, false, 0, false, "", false, "", "", 0,
                    status.getGroups().size(), Collections.unmodifiableList(streams));
        }
        String streamStatus = "";
        for (Stream stream : streams) {
            if (stream.getId().equals(group.getStreamId())) {
                streamStatus = stream.getStatus();
            }
        }
        return new ServerSays(address, clientId, true, true, client.isConnected(), client.getVolume().getPercent(),
                client.getVolume().isMuted(), group.getId(), group.isMuted(), group.getStreamId(), streamStatus,
                group.getClients().size(), status.getGroups().size(), Collections.unmodifiableList(streams));
    }

    public InetAddress getAddress() {
        return address;
    }

    public String getClientId() {
        return clientId;
    }

    public boolean isReached() {
        return reached;
    }

    public boolean isClientFound() {
        return clientFound;
    }

    public String getGroupId() {
        return groupId;
    }

    public String getStreamId() {
        return streamId;
    }

    public List<Stream> getStreams() {
        return streams;
    }

    /** True when the stream of the client's group is playing. */
    public boolean isOwnStreamPlaying() {
        return clientFound && "playing".equals(streamStatus);
    }

    /** The lines for the status text. */
    public String text() {
        if (!reached) {
            return "server says: the control port (1705) could not be asked\n";
        }
        StringBuilder out = new StringBuilder();
        if (!clientFound) {
            out.append("server says: it does not list this client (yet); ").append(groups).append(" groups\n");
        } else {
            out.append(String.format(Locale.ROOT,
                    "server says: my client is %s, volume %d%s; my group %s (%d client%s, %s) is on stream '%s' (%s)%n",
                    clientConnected ? "connected" : "NOT connected", volume, clientMuted ? " (muted)" : "",
                    SnapcastDisplayText.of(groupId, 8), clientsInGroup, clientsInGroup == 1 ? "" : "s",
                    groupMuted ? "muted" : "not muted", SnapcastDisplayText.of(streamId, MAX_TEXT),
                    streamStatus.isEmpty() ? "unknown" : streamStatus));
        }
        out.append("server streams: ");
        for (int i = 0; i < streams.size(); i++) {
            out.append(i == 0 ? "" : ", ").append(SnapcastDisplayText.of(streams.get(i).getId(), MAX_TEXT)).append(' ')
                    .append(streams.get(i).getStatus());
        }
        out.append(streams.isEmpty() ? "none" : "").append('\n');
        return out.toString();
    }

    /**
     * What to do when no chunk comes; empty when there is nothing to say.
     *
     * @param chunksReceived chunks the engine received in this session
     */
    public String hint(long chunksReceived) {
        if (chunksReceived > 0 || !reached) {
            return "";
        }
        if (!clientFound) {
            return "The server does not list this client: it is probably not in a group yet.";
        }
        if (!isOwnStreamPlaying()) {
            return "The server sends nothing: your group is on a stream that is idle. Move the group to the playing stream "
                    + "(Snapcast app, or the button \"Move my group to a stream\").";
        }
        boolean otherPlaying = false;
        for (Stream stream : streams) {
            otherPlaying |= stream.isPlaying() && !stream.getId().equals(streamId);
        }
        return otherPlaying ? "" : "The stream of your group is playing but no chunk has arrived yet.";
    }
}
