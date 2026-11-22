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
package de.yaacc.fcast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The decisions of the settings sheet of an FCast receiver (task 5.3): what is shown when, the speed choices, how a speed is written.
 * Pure. The protocol cannot read or change a receiver's own settings; the sheet shows what the protocol can control (volume, mute,
 * speed, stop), device information and the connection (design.md, "The settings sheet").
 */
public final class FCastSheetModel {

    /** The playback speeds that are offered ({@code SetSpeed}; the protocol accepts 0.25 to 4). */
    public static final List<Double> SPEEDS = Collections.unmodifiableList(
            java.util.Arrays.asList(0.5, 0.75, 1.0, 1.25, 1.5, 2.0));

    /** What the sheet needs to know to decide what to show. */
    public static final class Facts {
        boolean hasPlayer;
        boolean playerActive;
        boolean sessionActive;
        boolean manual;
        boolean connected;

        /** The receiver has a player (the player's session is the sheet's). */
        public Facts player(boolean active) {
            this.hasPlayer = true;
            this.playerActive = active;
            return this;
        }

        /** The receiver tells that it plays or has a paused item (through the session the sheet uses). */
        public Facts session(boolean active) {
            this.sessionActive = active;
            return this;
        }

        /** The receiver was typed in, pasted or scanned (it can be removed). */
        public Facts manual(boolean manual) {
            this.manual = manual;
            return this;
        }

        public Facts connected(boolean connected) {
            this.connected = connected;
            return this;
        }
    }

    /** The parts of the sheet. */
    public static final class Layout {
        private final boolean volume;
        private final boolean mute;
        private final boolean speed;
        private final boolean stop;
        private final boolean checkConnection;
        private final boolean reconnect;
        private final boolean remove;

        private Layout(boolean volume, boolean mute, boolean speed, boolean stop, boolean checkConnection, boolean reconnect, boolean remove) {
            this.volume = volume;
            this.mute = mute;
            this.speed = speed;
            this.stop = stop;
            this.checkConnection = checkConnection;
            this.reconnect = reconnect;
            this.remove = remove;
        }

        /** The volume works always (a connection is made for it on demand). */
        public boolean showsVolume() {
            return volume;
        }

        /** The mute is emulated by the player's memory of the old volume; without a player there is nothing to remember it. */
        public boolean showsMute() {
            return mute;
        }

        /** The speed is a property of what plays now and of the following items: only with a player. */
        public boolean showsSpeed() {
            return speed;
        }

        /** Stop: when something plays or is paused (through the player or the receiver's own report). */
        public boolean showsStop() {
            return stop;
        }

        public boolean showsCheckConnection() {
            return checkConnection;
        }

        /** A connection of the sheet can be made again; the connection of a player is the player's. */
        public boolean showsReconnect() {
            return reconnect;
        }

        /** Only a receiver that was added by hand; a found one is on the network, a remembered one goes with the selection. */
        public boolean showsRemove() {
            return remove;
        }
    }

    /** What the "protocol" row of the sheet says. */
    public enum ProtocolText {
        /** Not connected yet. */
        UNKNOWN,
        /** Version 3 (or older): unencrypted, and the receiver does not offer more. */
        PLAIN,
        /** Version 3 although the receiver offers 4: the key is not known (the receiver was added by its address). */
        PLAIN_V4_NEEDS_LINK,
        /** Version 3 although the receiver offers 4: this Android is older than 10. */
        PLAIN_V4_NEEDS_ANDROID,
        /** Version 4, the key came from the receiver's link. */
        SECURE_LINK,
        /** Version 4, the key came from the announcement of the receiver. */
        SECURE_DISCOVERY,
        /** Another version number. */
        OTHER
    }

    /**
     * @param version  the version the connection speaks (0: not connected)
     * @param peer     the version the receiver said it speaks (0: not said)
     * @param keySource where the key of a secured connection came from, null if not secured
     * @param sdk      the Android API level of this device
     */
    public static ProtocolText protocolText(int version, int peer, de.yaacc.fcast.protocol.FCastPin.Source keySource, int sdk) {
        if (version <= 0) {
            return ProtocolText.UNKNOWN;
        }
        if (version == 4) {
            return keySource == de.yaacc.fcast.protocol.FCastPin.Source.LINK ? ProtocolText.SECURE_LINK : ProtocolText.SECURE_DISCOVERY;
        }
        if (version == 3 || version < 3) {
            if (peer >= 4) {
                return sdk < de.yaacc.fcast.protocol.FCastConnection.MIN_SDK_FOR_V4 ? ProtocolText.PLAIN_V4_NEEDS_ANDROID
                        : ProtocolText.PLAIN_V4_NEEDS_LINK;
            }
            return ProtocolText.PLAIN;
        }
        return ProtocolText.OTHER;
    }

    /** Does the sheet show the tracks section: version 4 and something plays or is paused, and there are tracks or subtitle files can be added. */
    public static boolean showsTracks(int version, boolean itemActive, int audioChoices, int subtitleTracks, boolean externalSubtitles) {
        return version == 4 && itemActive && (audioChoices > 0 || subtitleTracks > 0 || externalSubtitles);
    }

    private FCastSheetModel() {
    }

    public static Layout layout(Facts facts) {
        boolean active = facts.hasPlayer ? facts.playerActive : facts.sessionActive;
        return new Layout(true, facts.hasPlayer, facts.hasPlayer, active, true, !facts.hasPlayer && facts.connected, facts.manual);
    }

    /** "1.25x" style text, without trailing zeros ("1x", "0.5x", "2x"). */
    public static String speedLabel(double speed) {
        String text = String.format(Locale.ROOT, "%.2f", speed);
        text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
        return text + "\u00d7";
    }

    /** The index in {@link #SPEEDS} that is nearest to the speed, -1 for a speed that is not a number. */
    public static int nearestSpeed(double speed) {
        if (Double.isNaN(speed)) {
            return -1;
        }
        int best = 0;
        for (int i = 1; i < SPEEDS.size(); i++) {
            if (Math.abs(SPEEDS.get(i) - speed) < Math.abs(SPEEDS.get(best) - speed)) {
                best = i;
            }
        }
        return best;
    }

    /** The labels of all speeds, in order. */
    public static List<String> speedLabels() {
        List<String> labels = new ArrayList<>();
        for (double speed : SPEEDS) {
            labels.add(speedLabel(speed));
        }
        return labels;
    }
}
