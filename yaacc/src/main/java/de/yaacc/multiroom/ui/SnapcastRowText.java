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
import java.util.Map;
import java.util.Objects;

import de.yaacc.multiroom.control.GroupVolume;
import de.yaacc.multiroom.control.SnapcastDisplayText;
import de.yaacc.multiroom.control.SnapcastGroup;
import de.yaacc.multiroom.control.SnapcastServerStatus;
import de.yaacc.multiroom.control.SnapcastStream;

/**
 * What the row of a Snapcast group says. Pure; the localized words come in through {@link Words}, so the composition is
 * tested without Android (the same idea as {@code YxcRowText} for MusicCast rows).
 *
 * <p>The text has two lines. The first one holds the facts of the group: the stream, how many clients are connected, the
 * group volume (the average of the connected members) and a "muted" marker. The second line names the <b>endpoint of the
 * server</b>: a hostile server can name its group like a legitimate one, and the endpoint is the one thing it cannot
 * copy (security review S20). Two markers explain what is wrong: "server unreachable" (the control connection is down;
 * the row shows the last known state and is not removed, so a short outage does not make the group flicker out of the
 * list and out of the stored selection) and "stream unavailable" (the group's stream is not in the server's list, or the
 * server has switched it off).
 *
 * <p>Everything that comes from the server (the stream name) goes through {@link SnapcastDisplayText} first: it is display
 * text, never an identity.
 */
public final class SnapcastRowText {

    /** The longest stream name shown in the row. */
    static final int MAX_STREAM_NAME = 48;
    /** The longest endpoint shown (a host name is at most 253 characters, a literal much less). */
    static final int MAX_ENDPOINT = 64;
    private static final String SEPARATOR = " · ";
    private static final String STATUS_DISABLED = "disabled";

    /** The localized words; the Android code backs them with string resources. */
    public interface Words {
        /** "Stream: Default". */
        String stream(String name);

        /** The group has no stream. */
        String noStream();

        /** "2 of 3 clients connected". */
        String clientsConnected(int connected, int total);

        /** The group has no members. */
        String noClients();

        /** "Volume 55%". */
        String volume(int percent);

        String muted();

        /** The marker for a server whose control connection is down. */
        String serverUnreachable();

        /** The marker for a stream that the server does not list or has disabled. */
        String streamUnavailable();

        /** "Server 192.168.1.5:1705". */
        String server(String endpoint);

        /** The group is no longer in the status of the server. */
        String groupGone();
    }

    /** The state of one row: the text and what the controls of the row show. Immutable. */
    public static final class Row {
        private final String text;
        private final int volume;
        private final boolean muted;
        private final boolean controllable;

        Row(String text, int volume, boolean muted, boolean controllable) {
            this.text = text;
            this.volume = volume;
            this.muted = muted;
            this.controllable = controllable;
        }

        /** The two lines of the row. */
        public String getText() {
            return text;
        }

        /** The group volume for the slider, or -1 when there is none (no members). The last known one while offline. */
        public int getVolume() {
            return volume;
        }

        /** The mute flag of the group. */
        public boolean isMuted() {
            return muted;
        }

        /**
         * True when the volume slider and the mute box make sense: the server answers and at least one member is
         * connected (a call for a server that does not answer would only fail).
         */
        public boolean isControllable() {
            return controllable;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Row)) {
                return false;
            }
            Row that = (Row) other;
            return volume == that.volume && muted == that.muted && controllable == that.controllable
                    && text.equals(that.text);
        }

        @Override
        public int hashCode() {
            return Objects.hash(text, volume, muted, controllable);
        }

        /** Numbers only: the text holds names of a server. */
        @Override
        public String toString() {
            return "SnapcastRowText.Row{volume=" + volume + ", muted=" + muted + ", controllable=" + controllable + "}";
        }
    }

    private SnapcastRowText() {
    }

    /**
     * @param group    the group, or null when the status of the server no longer has it
     * @param status   the last status read from the server (the group's stream is looked up in it)
     * @param online   whether the control connection is up
     * @param endpoint the server's endpoint as text ({@code host:port}); our own text, still cleaned before it is shown
     */
    public static Row of(SnapcastGroup group, SnapcastServerStatus status, boolean online, String endpoint, Words words) {
        String serverLine = words.server(SnapcastDisplayText.of(endpoint, MAX_ENDPOINT));
        if (group == null) {
            return new Row(words.groupGone() + "\n" + serverLine, -1, false, false);
        }
        Map<String, Integer> levels = GroupVolume.levels(group);
        int connected = levels.size();
        int total = group.getClients().size();
        int volume = connected == 0 ? -1 : GroupVolume.average(levels);

        List<String> facts = new ArrayList<>();
        if (!online) {
            facts.add(words.serverUnreachable());
        }
        facts.add(streamPart(group, status, words));
        if (!streamIsListed(group, status) && !group.getStreamId().isEmpty()) {
            facts.add(words.streamUnavailable());
        }
        if (online) {
            if (total == 0) {
                facts.add(words.noClients());
            } else {
                facts.add(words.clientsConnected(connected, total));
            }
            if (connected > 0) {
                facts.add(words.volume(volume));
            }
            if (group.isMuted()) {
                facts.add(words.muted());
            }
        }
        boolean controllable = online && connected > 0;
        return new Row(String.join(SEPARATOR, facts) + "\n" + serverLine, volume, group.isMuted(), controllable);
    }

    private static String streamPart(SnapcastGroup group, SnapcastServerStatus status, Words words) {
        if (group.getStreamId().isEmpty()) {
            return words.noStream();
        }
        return words.stream(SnapcastDisplayText.of(group.getStreamId(), MAX_STREAM_NAME));
    }

    /** The stream is in the server's list and not switched off. */
    private static boolean streamIsListed(SnapcastGroup group, SnapcastServerStatus status) {
        if (group.getStreamId().isEmpty()) {
            return true; // no stream is not "unavailable"
        }
        SnapcastStream stream = status == null ? null : status.findStream(group.getStreamId());
        return stream != null && !STATUS_DISABLED.equals(stream.getStatus());
    }
}
