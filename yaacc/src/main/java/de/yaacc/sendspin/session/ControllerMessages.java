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
package de.yaacc.sendspin.session;

import de.yaacc.sendspin.secure.Base64Url;
import de.yaacc.sendspin.secure.SendspinPsk;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The messages of the client side of the protocol for a client with the roles {@code controller@v1} and {@code metadata@v1}: what it
 * sends ({@code client/init}, {@code client/hello}, {@code client/state}, {@code client/time}, {@code client/command},
 * {@code client/goodbye}, {@code pair/abort}) and what it reads of the server ({@code server/hello}, {@code server/activate},
 * {@code group/update}, {@code server/state} with the controller and metadata objects, {@code server/time}). The server is
 * untrusted: every number is range checked, every text has its byte limit, a violation is a {@link ProtocolException}. Pure.
 */
public final class ControllerMessages {

    /** The roles this client asks for. */
    public static final List<String> ROLES = Collections.unmodifiableList(Arrays.asList("controller@v1", "metadata@v1"));

    /** The controller commands of the protocol; a command that is not in this list is not offered to the user. */
    public static final List<String> COMMANDS = Collections.unmodifiableList(Arrays.asList("play", "pause", "stop", "next", "previous",
            "volume", "mute", "repeat_off", "repeat_one", "repeat_all", "shuffle", "unshuffle", "switch", "seek", "seek_relative"));

    private static final int MAX_NAME_BYTES = 64;
    private static final int MAX_METADATA_BYTES = 255;
    private static final int MAX_URL_BYTES = 2048;
    private static final int MAX_LIST = 64;

    private ControllerMessages() {
    }

    // ------------------------------------------------------------------ what the client sends

    public static String clientInit(String clientId, String suiteName) {
        return SendspinMessages.message("client/init", SendspinMessages.json("client_id", clientId, "version", 1, "suite", suiteName));
    }

    /**
     * @param productName     null to leave it out; at most 64 bytes
     * @param softwareVersion null to leave it out
     * @param unpairedAccess  whether this client admits a server it is not paired with (spec: unpaired access)
     */
    public static String clientHello(String name, String productName, String softwareVersion, boolean unpairedAccess) {
        JSONObject payload = SendspinMessages.json("name", name, "supported_roles", new JSONArray(ROLES),
                "supported_pair_methods", SendspinMessages.json("pairing_psk", new JSONObject()),
                "unpaired_access", SendspinMessages.json("enabled", unpairedAccess));
        if (productName != null || softwareVersion != null) {
            JSONObject info = new JSONObject();
            if (productName != null) {
                SendspinMessages.put(info, "product_name", productName);
            }
            if (softwareVersion != null) {
                SendspinMessages.put(info, "software_version", softwareVersion);
            }
            SendspinMessages.put(payload, "device_info", info);
        }
        return SendspinMessages.message("client/hello", payload);
    }

    public static String clientState(boolean available) {
        return SendspinMessages.message("client/state", SendspinMessages.json("available", available));
    }

    public static String clientTime(long clientTransmittedMicros) {
        return SendspinMessages.message("client/time", SendspinMessages.json("client_transmitted", clientTransmittedMicros));
    }

    private static String command(JSONObject controller) {
        return SendspinMessages.message("client/command", SendspinMessages.json("controller", controller));
    }

    /** A command without a parameter: play, pause, stop, next, previous, repeat_*, shuffle, unshuffle, switch. */
    public static String commandPlain(String command) {
        return command(SendspinMessages.json("command", command));
    }

    public static String commandVolume(int volume) {
        return command(SendspinMessages.json("command", "volume", "volume", volume));
    }

    public static String commandMute(boolean mute) {
        return command(SendspinMessages.json("command", "mute", "mute", mute));
    }

    public static String commandSeek(long positionMs) {
        return command(SendspinMessages.json("command", "seek", "position_ms", positionMs));
    }

    public static String commandSeekRelative(long offsetMs) {
        return command(SendspinMessages.json("command", "seek_relative", "offset_ms", offsetMs));
    }

    public static String clientGoodbye(String reason) {
        return SendspinMessages.message("client/goodbye", SendspinMessages.json("reason", reason));
    }

    public static String pairAbort(String reason) {
        return SendspinMessages.message("pair/abort", SendspinMessages.json("reason", reason));
    }

    // ------------------------------------------------------------------ what the client reads

    /** {@code server/hello}: the name of the server. */
    public static String parseServerHello(JSONObject p) throws ProtocolException {
        return SendspinMessages.text(p, "name", MAX_NAME_BYTES, true);
    }

    /** {@code server/activate}. */
    public static final class Activate {
        public final Set<String> activities;
        /** Null if the message has none (the roles of the earlier activation persist). */
        public final List<String> activeRoles;
        public final String pairingMethod;

        Activate(Set<String> activities, List<String> activeRoles, String pairingMethod) {
            this.activities = Collections.unmodifiableSet(activities);
            this.activeRoles = activeRoles == null ? null : Collections.unmodifiableList(activeRoles);
            this.pairingMethod = pairingMethod;
        }
    }

    public static Activate parseActivate(JSONObject p) throws ProtocolException {
        JSONArray activitiesArray = p.optJSONArray("activities");
        if (activitiesArray == null || activitiesArray.length() > MAX_LIST) {
            throw new ProtocolException("activities");
        }
        Set<String> activities = new LinkedHashSet<>();
        for (int i = 0; i < activitiesArray.length(); i++) {
            Object a = activitiesArray.opt(i);
            if (!(a instanceof String) || ((String) a).length() > 32 || !activities.add((String) a)) {
                throw new ProtocolException("activities");
            }
        }
        List<String> roles = null;
        if (p.has("active_roles")) {
            JSONArray rolesArray = p.optJSONArray("active_roles");
            if (rolesArray == null || rolesArray.length() > MAX_LIST) {
                throw new ProtocolException("active_roles");
            }
            roles = new ArrayList<>();
            for (int i = 0; i < rolesArray.length(); i++) {
                Object r = rolesArray.opt(i);
                if (!(r instanceof String) || ((String) r).length() > 64) {
                    throw new ProtocolException("active_roles");
                }
                roles.add((String) r);
            }
        }
        String method = null;
        JSONObject pairing = p.optJSONObject("pairing");
        if (pairing != null) {
            method = SendspinMessages.text(pairing, "method", 32, false);
        }
        return new Activate(activities, roles, method);
    }

    /** What the client does with an activation (spec, {@code server/activate}: the first rule that applies). */
    public enum Admission {
        OK,
        /** An unpaired session while this client does not admit unpaired access: {@code client/goodbye} {@code pairing_required}. */
        PAIRING_REQUIRED,
        /** An activity set or role the matched PSK does not allow: {@code client/goodbye} {@code unauthorized}. */
        UNAUTHORIZED,
        /** A pairing method this client does not offer: {@code pair/abort} {@code method_not_supported}, the connection stays open. */
        METHOD_NOT_SUPPORTED
    }

    /**
     * @param psk            the category of the PSK of the session
     * @param unpairedAccess whether this client admits unpaired access now
     * @param activities     the declared activities
     * @param roles          the roles that are active after this message (the persisted ones if the message has none)
     * @param methodOffered  whether the pairing method of the activation, if there is one, is one this client offers and that fits
     *                       the PSK
     */
    public static Admission admit(SendspinPsk.Category psk, boolean unpairedAccess, Set<String> activities, List<String> roles,
                                  boolean methodOffered) {
        boolean unpairedSession = psk != SendspinPsk.Category.LONG_TERM;
        boolean now = admissible(psk, unpairedAccess, activities, roles);
        if (!now) {
            if (unpairedSession && !unpairedAccess && admissible(psk, true, activities, roles)) {
                return Admission.PAIRING_REQUIRED;
            }
            return Admission.UNAUTHORIZED;
        }
        if (activities.contains("pairing") && !methodOffered) {
            return Admission.METHOD_NOT_SUPPORTED;
        }
        return Admission.OK;
    }

    private static boolean allowedSet(SendspinPsk.Category psk, boolean unpairedAccess, Set<String> activities) {
        for (String a : activities) {
            if (!"playback".equals(a) && !"pairing".equals(a)) {
                return false;
            }
        }
        if (psk == SendspinPsk.Category.LONG_TERM) {
            return !activities.contains("pairing");
        }
        return !activities.contains("playback") || unpairedAccess;
    }

    private static boolean admissible(SendspinPsk.Category psk, boolean unpairedAccess, Set<String> activities, List<String> roles) {
        if (!allowedSet(psk, unpairedAccess, activities)) {
            return false;
        }
        if (roles == null || roles.isEmpty()) {
            return true;
        }
        Set<String> withPlayback = new HashSet<>(activities);
        withPlayback.add("playback");
        return allowedSet(psk, unpairedAccess, withPlayback); // playback-capable: the roles may be active even without the activity
    }

    /** {@code group/update}. */
    public static final class Group {
        public final boolean playing;
        public final String groupId;
        public final String groupName;

        Group(boolean playing, String groupId, String groupName) {
            this.playing = playing;
            this.groupId = groupId;
            this.groupName = groupName;
        }
    }

    public static Group parseGroupUpdate(JSONObject p) throws ProtocolException {
        String state = SendspinMessages.text(p, "playback_state", 16, true);
        if (!"playing".equals(state) && !"stopped".equals(state)) {
            throw new ProtocolException("playback_state");
        }
        String id = SendspinMessages.text(p, "group_id", 64, true);
        if (Base64Url.decode32(id) == null) {
            throw new ProtocolException("group_id"); // the spec: the client MUST close the connection
        }
        return new Group("playing".equals(state), id, SendspinMessages.text(p, "group_name", MAX_NAME_BYTES, true));
    }

    /** The {@code controller} object of {@code server/state}: what the group can do and its volume, mute, repeat and shuffle. */
    public static final class ControllerState {
        public final Set<String> supportedCommands;
        public final int volume;
        public final boolean muted;
        public final String repeat;
        public final boolean shuffle;
        public final Long seekMaxMs;

        ControllerState(Set<String> commands, int volume, boolean muted, String repeat, boolean shuffle, Long seekMaxMs) {
            this.supportedCommands = Collections.unmodifiableSet(commands);
            this.volume = volume;
            this.muted = muted;
            this.repeat = repeat;
            this.shuffle = shuffle;
            this.seekMaxMs = seekMaxMs;
        }

        public boolean supports(String command) {
            return supportedCommands.contains(command);
        }
    }

    /** Playback progress of the {@code metadata} object. */
    public static final class Progress {
        public final long trackProgressMs;
        /** 0 for unlimited or unknown. */
        public final long trackDurationMs;
        /** Times 1000: 1000 is normal speed, 0 is paused. */
        public final int playbackSpeed;

        Progress(long trackProgressMs, long trackDurationMs, int playbackSpeed) {
            this.trackProgressMs = trackProgressMs;
            this.trackDurationMs = trackDurationMs;
            this.playbackSpeed = playbackSpeed;
        }
    }

    /** The {@code metadata} object of {@code server/state}. The texts are not cleaned here: the display does it. */
    public static final class Metadata {
        /** Server clock, microseconds: when this takes effect and where the progress is measured from. */
        public final long timestamp;
        public final String title;
        public final String artist;
        public final String albumArtist;
        public final String album;
        public final String artworkUrl;
        public final Progress progress;

        Metadata(long timestamp, String title, String artist, String albumArtist, String album, String artworkUrl, Progress progress) {
            this.timestamp = timestamp;
            this.title = title;
            this.artist = artist;
            this.albumArtist = albumArtist;
            this.album = album;
            this.artworkUrl = artworkUrl;
            this.progress = progress;
        }
    }

    /** {@code server/state}: each object is null if the message has none (an omitted object leaves its state unchanged). */
    public static final class ServerState {
        public final ControllerState controller;
        public final Metadata metadata;

        ServerState(ControllerState controller, Metadata metadata) {
            this.controller = controller;
            this.metadata = metadata;
        }
    }

    public static ServerState parseServerState(JSONObject p) throws ProtocolException {
        ControllerState controller = null;
        Metadata metadata = null;
        if (p.has("controller")) {
            JSONObject o = p.optJSONObject("controller");
            if (o == null) {
                throw new ProtocolException("controller");
            }
            controller = parseController(o);
        }
        if (p.has("metadata")) {
            JSONObject o = p.optJSONObject("metadata");
            if (o == null) {
                throw new ProtocolException("metadata");
            }
            metadata = parseMetadata(o);
        }
        return new ServerState(controller, metadata);
    }

    private static ControllerState parseController(JSONObject o) throws ProtocolException {
        JSONArray array = o.optJSONArray("supported_commands");
        if (array == null || array.length() > MAX_LIST) {
            throw new ProtocolException("supported_commands");
        }
        Set<String> commands = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            Object c = array.opt(i);
            if (!(c instanceof String)) {
                throw new ProtocolException("supported_commands");
            }
            if (COMMANDS.contains(c)) {
                commands.add((String) c); // a command of a newer protocol revision is not offered
            }
        }
        int volume = SendspinMessages.integer(o, "volume", 0, 100, true);
        if (!(o.opt("muted") instanceof Boolean) || !(o.opt("shuffle") instanceof Boolean)) {
            throw new ProtocolException("muted or shuffle");
        }
        String repeat = SendspinMessages.text(o, "repeat", 8, true);
        if (!"off".equals(repeat) && !"one".equals(repeat) && !"all".equals(repeat)) {
            throw new ProtocolException("repeat");
        }
        Long seekMax = null;
        if (o.has("seek_max_ms")) {
            seekMax = SendspinMessages.longInteger(o, "seek_max_ms", 0, Long.MAX_VALUE / 1000);
        }
        return new ControllerState(commands, volume, (Boolean) o.opt("muted"), repeat, (Boolean) o.opt("shuffle"), seekMax);
    }

    private static Metadata parseMetadata(JSONObject o) throws ProtocolException {
        long timestamp = SendspinMessages.longInteger(o, "timestamp", 0, Long.MAX_VALUE / 4);
        if (o.has("year")) {
            SendspinMessages.integer(o, "year", 0, 10000, true);
        }
        if (o.has("track")) {
            SendspinMessages.integer(o, "track", 1, Integer.MAX_VALUE, true);
        }
        Progress progress = null;
        if (o.has("progress")) {
            JSONObject pr = o.optJSONObject("progress");
            if (pr == null) {
                throw new ProtocolException("progress");
            }
            progress = new Progress(SendspinMessages.longInteger(pr, "track_progress", 0, Long.MAX_VALUE / 1000),
                    SendspinMessages.longInteger(pr, "track_duration", 0, Long.MAX_VALUE / 1000),
                    SendspinMessages.integer(pr, "playback_speed", 0, 100_000, true));
        }
        return new Metadata(timestamp, SendspinMessages.text(o, "title", MAX_METADATA_BYTES, false),
                SendspinMessages.text(o, "artist", MAX_METADATA_BYTES, false),
                SendspinMessages.text(o, "album_artist", MAX_METADATA_BYTES, false),
                SendspinMessages.text(o, "album", MAX_METADATA_BYTES, false),
                SendspinMessages.text(o, "artwork_url", MAX_URL_BYTES, false), progress);
    }

    /** {@code server/time}: {@code client_transmitted}, {@code server_received}, {@code server_transmitted}. */
    public static long[] parseServerTime(JSONObject p) throws ProtocolException {
        return new long[]{SendspinMessages.longInteger(p, "client_transmitted", Long.MIN_VALUE, Long.MAX_VALUE),
                SendspinMessages.longInteger(p, "server_received", Long.MIN_VALUE, Long.MAX_VALUE),
                SendspinMessages.longInteger(p, "server_transmitted", Long.MIN_VALUE, Long.MAX_VALUE)};
    }
}
