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
package de.yaacc.multiroom.ui;

import java.util.ArrayList;
import java.util.List;

import de.yaacc.multiroom.control.SnapcastDisplayText;
import de.yaacc.multiroom.control.SnapcastGroup;
import de.yaacc.multiroom.control.SnapcastServerStatus;
import de.yaacc.multiroom.control.SnapcastStream;

/**
 * The streams of a server as the choices of a group's stream picker. Pure.
 *
 * <p>The label of a stream is its id cleaned as display text (the id of a stream is its name); the raw id is kept for
 * the call that sets it, and only for that. A stream that the server has switched off ({@code disabled}) can not be
 * chosen; the stream the group plays now needs no change; YAACC's own feed stream of this phone is marked (its name is
 * {@code YAACC <phone name> <marker>}), so that a user does not mistake it for a music source.
 */
public final class SnapcastStreamChoices {

    private static final String OWN_PREFIX = "YAACC ";
    private static final String STATUS_DISABLED = "disabled";
    private static final int MAX_LABEL = 64;

    /** Why a stream can not be chosen. */
    public enum Reason {
        NONE,
        /** The group plays it already. */
        CURRENT,
        /** The server has switched the stream off. */
        UNAVAILABLE
    }

    /** One stream of the picker. Immutable. */
    public static final class Choice {
        private final String streamId;
        private final String label;
        private final String status;
        private final boolean current;
        private final boolean ownFeed;
        private final Reason reason;

        Choice(String streamId, String label, String status, boolean current, boolean ownFeed, Reason reason) {
            this.streamId = streamId;
            this.label = label;
            this.status = status;
            this.current = current;
            this.ownFeed = ownFeed;
            this.reason = reason;
        }

        /** The server's id of the stream, for {@code Group.SetStream} only; never shown, never logged. */
        public String getStreamId() {
            return streamId;
        }

        /** The name to show: cleaned display text, different from the labels of the other choices. */
        public String getLabel() {
            return label;
        }

        /** {@code playing}, {@code idle}, {@code unknown} or {@code disabled}, as the server reports it (cleaned). */
        public String getStatus() {
            return status;
        }

        /** The group plays this stream now. */
        public boolean isCurrent() {
            return current;
        }

        /** This is the feed stream that YAACC made on this phone's behalf. */
        public boolean isOwnFeed() {
            return ownFeed;
        }

        public Reason getReason() {
            return reason;
        }

        /** True when choosing it makes sense. */
        public boolean isSelectable() {
            return reason == Reason.NONE;
        }

        @Override
        public String toString() {
            return "SnapcastStreamChoices.Choice{current=" + current + ", ownFeed=" + ownFeed + ", reason=" + reason + "}";
        }
    }

    private SnapcastStreamChoices() {
    }

    /**
     * @param status the server's status; null gives an empty list
     * @param marker the marker of this install in YAACC's stream names, or null when none was made yet
     * @return the choices in the server's order; empty when the group or the status is missing, or there is no stream
     */
    public static List<Choice> of(SnapcastServerStatus status, String groupId, String marker) {
        List<Choice> none = new ArrayList<>();
        SnapcastGroup group = status == null ? null : status.findGroup(groupId);
        if (group == null) {
            return none;
        }
        List<String> labels = new ArrayList<>();
        for (SnapcastStream stream : status.getStreams()) {
            labels.add(SnapcastDisplayText.of(stream.getId(), MAX_LABEL));
        }
        labels = SnapcastMemberRows.uniqueNames(labels);
        List<Choice> choices = new ArrayList<>(labels.size());
        int position = 0;
        for (SnapcastStream stream : status.getStreams()) {
            boolean current = stream.getId().equals(group.getStreamId());
            boolean disabled = STATUS_DISABLED.equals(stream.getStatus());
            Reason reason = current ? Reason.CURRENT : disabled ? Reason.UNAVAILABLE : Reason.NONE;
            choices.add(new Choice(stream.getId(), labels.get(position++), SnapcastDisplayText.of(stream.getStatus(), 16),
                    current, isOwnFeed(stream.getId(), marker), reason));
        }
        return choices;
    }

    /**
     * True when the stream name is {@code YAACC <phone name> <marker>} with this install's marker. A name that only ends
     * with the marker, or that has the prefix without the marker, is not ours.
     */
    public static boolean isOwnFeed(String streamId, String marker) {
        if (streamId == null || marker == null || marker.isEmpty()) {
            return false;
        }
        String tail = " " + marker;
        // the prefix, at least one character of the phone's name, a space and the marker
        return streamId.startsWith(OWN_PREFIX) && streamId.endsWith(tail)
                && streamId.length() >= OWN_PREFIX.length() + 1 + tail.length();
    }
}
