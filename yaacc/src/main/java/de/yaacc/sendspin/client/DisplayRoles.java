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
package de.yaacc.sendspin.client;

import java.util.List;

import de.yaacc.sendspin.session.ControllerMessages;
import de.yaacc.sendspin.session.ProtocolException;

/**
 * What a client with the display roles shows (issue #325, task 11.5): the {@code metadata} (title, artist, album, progress; a scheduled
 * update is applied when its time has come), the {@code artwork} (the album image) and the {@code color} (colours derived from the
 * audio). The state of one server connection, locked by its own monitor: the connection thread writes it, a display reads it.
 * <p>
 * The roles a client offers are chosen by its operator (see {@link #roles}); this class holds the state of the roles that are active.
 */
public final class DisplayRoles {

    public static final String METADATA = "metadata@v1";
    public static final String ARTWORK = "artwork@v1";
    public static final String COLOR = "color@v1";

    /** The one artwork channel this client asks for: the album image, JPEG, a square the server scales to fit and pads with black. */
    public static final String ARTWORK_FORMAT = "jpeg";
    public static final int ARTWORK_SIZE = 512;

    private final TrackPosition.Tracker metadata = new TrackPosition.Tracker();
    private final ArtworkStream artwork = new ArtworkStream();
    private ControllerMessages.Color color;
    private ControllerMessages.Color pendingColor;

    /** @return the display roles of the given choices, in the order the roles are listed in {@code client/hello} */
    public static List<String> roles(boolean showMetadata, boolean showArtwork, boolean showColor) {
        List<String> roles = new java.util.ArrayList<>(3);
        if (showMetadata) {
            roles.add(METADATA);
        }
        if (showArtwork) {
            roles.add(ARTWORK);
        }
        if (showColor) {
            roles.add(COLOR);
        }
        return roles;
    }

    /** A {@code server/state} arrived: its metadata and color objects, for the roles that are active. */
    public synchronized void accept(ControllerMessages.ServerState state, long serverNowMicros, boolean metadataActive, boolean colorActive) {
        if (state.metadata != null && metadataActive) {
            metadata.accept(state.metadata, serverNowMicros);
        }
        if (state.color != null && colorActive) {
            if (state.color.timestamp > serverNowMicros) {
                pendingColor = state.color;
            } else {
                color = state.color;
                pendingColor = null;
            }
        }
    }

    public synchronized ControllerMessages.Metadata metadata(long serverNowMicros) {
        return metadata.current(serverNowMicros);
    }

    public synchronized ControllerMessages.Metadata pendingMetadata(long serverNowMicros) {
        metadata.current(serverNowMicros);
        return metadata.pending();
    }

    /** The position in the current track in milliseconds, or -1 if the server reports none. */
    public synchronized long positionMs(long serverNowMicros) {
        return TrackPosition.positionMs(metadata.current(serverNowMicros), serverNowMicros);
    }

    /** The colours to show at this time: a scheduled update whose time has come is applied first; null if there are none. */
    public synchronized ControllerMessages.Color color(long serverNowMicros) {
        if (pendingColor != null && pendingColor.timestamp <= serverNowMicros) {
            color = pendingColor;
            pendingColor = null;
        }
        return color;
    }

    public synchronized void startArtwork(List<ControllerMessages.ArtworkChannel> channels) {
        artwork.start(channels);
    }

    public synchronized void endArtwork() {
        artwork.end();
    }

    /** @return true if the image changed; a malformed message is a protocol error (the specification: the connection ends) */
    public synchronized boolean onArtworkMessage(int id, byte[] body, long serverNowMicros) throws ProtocolException {
        long before = artwork.version();
        artwork.onMessage(id, body, serverNowMicros);
        return artwork.version() != before;
    }

    /** The album image now (JPEG or PNG bytes), or null. */
    public synchronized byte[] artwork(long serverNowMicros) {
        return artwork.current(0, serverNowMicros);
    }

    /** Increases whenever the image changes, so that a display decodes it again only then. */
    public synchronized long artworkVersion(long serverNowMicros) {
        artwork.current(0, serverNowMicros);
        return artwork.version();
    }

    /** Forgets what a role showed when the server takes that role away. */
    public synchronized void clear(boolean keepMetadata, boolean keepArtwork, boolean keepColor) {
        if (!keepMetadata) {
            metadata.clear();
        }
        if (!keepArtwork) {
            artwork.end();
        }
        if (!keepColor) {
            color = null;
            pendingColor = null;
        }
    }
}
