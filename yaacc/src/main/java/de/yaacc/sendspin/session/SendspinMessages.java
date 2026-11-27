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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The messages of the protocol that the server role writes and reads (core and {@code player@v1}), as plain values. Builders return
 * the JSON text; parsers check every field against the range the specification gives and throw a {@link ProtocolException} for a
 * value outside it, which the receiver answers by closing the connection.
 */
public final class SendspinMessages {

    /** The binary message id of an audio chunk. */
    public static final int AUDIO_CHUNK_ID = 4;
    /** The binary message id of a JSON message. */
    public static final int JSON_ID = 0;
    /** The binary message id of a fragment. */
    public static final int FRAGMENT_ID = 1;

    private SendspinMessages() {
    }

    // ------------------------------------------------------------------ building

    static String message(String type, JSONObject payload) {
        try {
            return new JSONObject().put("type", type).put("payload", payload).toString();
        } catch (JSONException e) {
            throw new IllegalStateException("a message of plain values is always valid");
        }
    }

    public static String serverError(String reason) {
        return message("server/error", json("reason", reason));
    }

    public static String serverInit(String serverId) {
        return message("server/init", json("server_id", serverId, "version", 1));
    }

    public static String noiseHandshake(String data) {
        return message("noise/handshake", json("data", data));
    }

    public static String serverHello(String name) {
        return message("server/hello", json("name", name));
    }

    public static String serverActivate(List<String> activities, List<String> activeRoles) {
        return message("server/activate", json("activities", new JSONArray(activities), "active_roles", new JSONArray(activeRoles)));
    }

    /** {@code server/activate} that admits a pairing attempt with the Pairing PSK (no playback, no roles). */
    public static String serverActivatePairing() {
        return message("server/activate", json("activities", new JSONArray(Collections.singletonList("pairing")),
                "active_roles", new JSONArray(), "pairing", json("method", "pairing_psk")));
    }

    /** {@code server/pair-finalize}: the server has stored the pairing record. */
    public static String serverPairFinalize() {
        return message("server/pair-finalize", new JSONObject());
    }

    public static String serverTime(long clientTransmitted, long serverReceived, long serverTransmitted) {
        return message("server/time", json("client_transmitted", clientTransmitted, "server_received", serverReceived,
                "server_transmitted", serverTransmitted));
    }

    public static String groupUpdate(boolean playing, String groupId, String groupName) {
        return message("group/update", json("playback_state", playing ? "playing" : "stopped", "group_id", groupId, "group_name", groupName));
    }

    /** {@code stream/start} for the player role in PCM. */
    public static String streamStartPcm(long serverTransmitted, int sampleRate, int channels, int bitDepth) {
        return message("stream/start", json("server_transmitted", serverTransmitted,
                "player", json("codec", "pcm", "sample_rate", sampleRate, "channels", channels, "bit_depth", bitDepth)));
    }

    /** {@code stream/clear} for the player role; {@code fromTimestamp} null clears everything buffered. */
    public static String streamClear(long serverTransmitted, Long fromTimestamp) {
        JSONObject payload = json("server_transmitted", serverTransmitted, "roles", new JSONArray(Collections.singletonList("player")));
        if (fromTimestamp != null) {
            put(payload, "from_timestamp", fromTimestamp);
        }
        return message("stream/clear", payload);
    }

    public static String streamEnd() {
        return message("stream/end", json("roles", new JSONArray(Collections.singletonList("player"))));
    }

    public static String commandVolume(int volume) {
        return message("server/command", json("player", json("command", "volume", "volume", volume)));
    }

    public static String commandMute(boolean mute) {
        return message("server/command", json("player", json("command", "mute", "mute", mute)));
    }

    public static String commandOutputDelay(int milliseconds) {
        return message("server/command", json("player", json("command", "set_output_delay", "output_delay_ms", milliseconds)));
    }

    /**
     * The body of a binary audio chunk (message id {@link #AUDIO_CHUNK_ID}, which the channel adds): the timestamp (big-endian
     * int64, server clock in microseconds), {@code send_ahead} (big-endian uint32: microseconds from transmission to the
     * timestamp; 0 if transmitted at or after it, 4294967295 if the lead is too long to represent) and the encoded audio.
     */
    public static byte[] audioChunk(long timestampMicros, long sendAheadMicros, byte[] audio, int offset, int length) {
        long sendAhead = sendAheadMicros <= 0 ? 0 : Math.min(sendAheadMicros, 0xFFFFFFFFL);
        ByteBuffer buffer = ByteBuffer.allocate(12 + length);
        buffer.putLong(timestampMicros).putInt((int) sendAhead).put(audio, offset, length);
        return buffer.array();
    }

    /** The audio chunk of the older wire: the timestamp (int64, big endian) and the audio, without the send-ahead field. */
    public static byte[] audioChunkLegacy(long timestampMicros, byte[] audio, int offset, int length) {
        ByteBuffer buffer = ByteBuffer.allocate(8 + length);
        buffer.putLong(timestampMicros).put(audio, offset, length);
        return buffer.array();
    }

    /** {@code server/hello} of the older wire: it replaces {@code server/hello} and {@code server/activate} and names the active roles. */
    public static String legacyServerHello(String serverId, String name, List<String> activeRoles, String connectionReason) {
        return message("server/hello", json("server_id", serverId, "name", name, "version", 1, "connection_reason", connectionReason,
                "active_roles", new JSONArray(activeRoles)));
    }

    static JSONObject json(Object... pairs) {
        JSONObject object = new JSONObject();
        for (int i = 0; i < pairs.length; i += 2) {
            put(object, (String) pairs[i], pairs[i + 1]);
        }
        return object;
    }

    static void put(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (JSONException e) {
            throw new IllegalStateException("a value of plain types is always valid");
        }
    }

    // ------------------------------------------------------------------ reading

    /** One entry of {@code supported_formats}. */
    public static final class Format {
        public final String codec;
        public final int channels;
        public final int sampleRate;
        public final int bitDepth;

        Format(String codec, int channels, int sampleRate, int bitDepth) {
            this.codec = codec;
            this.channels = channels;
            this.sampleRate = sampleRate;
            this.bitDepth = bitDepth;
        }

        public boolean isPcm(int rate, int channelCount, int depth) {
            return "pcm".equals(codec) && sampleRate == rate && channels == channelCount && bitDepth == depth;
        }
    }

    /** {@code client/hello}: what the server uses of it. */
    public static final class ClientHello {
        public final String name;
        public final List<String> supportedRoles;
        public final List<Format> playerFormats;
        public final long playerBufferCapacity;
        public final boolean unpairedAccess;
        public final List<String> pairMethods;
        public final String productName;
        /** The {@code client_id} of a legacy hello (the encrypted protocol carries it in {@code client/init}), else null. */
        public final String clientId;
        /** The {@code supported_commands} of the player support object (the older wire puts them there), else empty. */
        public final List<String> playerCommands;

        /** The emission formats of {@code dynamic_pairing_code} that this server knows ({@code digits}, {@code qr_code}); empty if the method is not offered. */
        public final List<String> codeFormats;
        /** Whether the client offers {@code static_pairing_code}. */
        public final boolean staticCodeOffered;

        /** Whether the client offers any code-based pairing method. */
        public boolean offersCodePairing() {
            return !codeFormats.isEmpty() || staticCodeOffered;
        }

        ClientHello(String name, List<String> roles, List<Format> formats, long bufferCapacity, boolean unpaired, List<String> pairMethods,
                    String productName, String clientId, List<String> playerCommands, List<String> codeFormats, boolean staticCodeOffered) {
            this.codeFormats = Collections.unmodifiableList(codeFormats);
            this.staticCodeOffered = staticCodeOffered;
            this.clientId = clientId;
            this.playerCommands = Collections.unmodifiableList(playerCommands);
            this.name = name;
            this.supportedRoles = Collections.unmodifiableList(roles);
            this.playerFormats = Collections.unmodifiableList(formats);
            this.playerBufferCapacity = bufferCapacity;
            this.unpairedAccess = unpaired;
            this.pairMethods = Collections.unmodifiableList(pairMethods);
            this.productName = productName;
        }

        public boolean supportsPlayer() {
            return supportedRoles.contains("player@v1");
        }

        public boolean supportsController() {
            return supportedRoles.contains("controller@v1");
        }

        public boolean supportsMetadata() {
            return supportedRoles.contains("metadata@v1");
        }

        public boolean supportsArtwork() {
            return supportedRoles.contains("artwork@v1");
        }
    }

    private static final int MAX_ROLES = 64;
    private static final int MAX_FORMATS = 64;

    public static ClientHello parseClientHello(JSONObject p) throws ProtocolException {
        return parseClientHello(p, false);
    }

    /**
     * The hello of a client of the older, unencrypted wire: it also needs a {@code client_id} (plain text of at most 64 bytes) and a
     * session with it is always unpaired. The player support object lists the commands the player takes.
     */
    public static ClientHello parseLegacyClientHello(JSONObject p) throws ProtocolException {
        return parseClientHello(p, true);
    }

    private static ClientHello parseClientHello(JSONObject p, boolean legacy) throws ProtocolException {
        String clientId = text(p, "client_id", 64, legacy);
        if (legacy) {
            if (clientId.isEmpty()) {
                throw new ProtocolException("client_id");
            }
            for (int i = 0; i < clientId.length(); i++) {
                if (Character.isISOControl(clientId.charAt(i))) {
                    throw new ProtocolException("client_id");
                }
            }
        }
        String name = text(p, "name", 64, true);
        JSONArray rolesArray = p.optJSONArray("supported_roles");
        if (rolesArray == null || rolesArray.length() > MAX_ROLES) {
            throw new ProtocolException("supported_roles");
        }
        List<String> roles = new ArrayList<>();
        for (int i = 0; i < rolesArray.length(); i++) {
            Object role = rolesArray.opt(i);
            if (!(role instanceof String) || ((String) role).length() > 64) {
                throw new ProtocolException("supported_roles");
            }
            roles.add((String) role);
        }
        List<Format> formats = new ArrayList<>();
        List<String> playerCommands = new ArrayList<>();
        long bufferCapacity = 0;
        if (roles.contains("player@v1")) {
            JSONObject support = p.optJSONObject("player@v1_support");
            if (support == null) {
                throw new ProtocolException("player@v1_support is required");
            }
            JSONArray array = support.optJSONArray("supported_formats");
            if (array == null || array.length() == 0 || array.length() > MAX_FORMATS) {
                throw new ProtocolException("supported_formats");
            }
            for (int i = 0; i < array.length(); i++) {
                JSONObject f = array.optJSONObject(i);
                if (f == null) {
                    throw new ProtocolException("supported_formats");
                }
                String codec = text(f, "codec", 16, true);
                formats.add(new Format(codec, integer(f, "channels", 1, 64, true), integer(f, "sample_rate", 1, 1_000_000, true),
                        "opus".equals(codec) ? 0 : integer(f, "bit_depth", 1, 64, true)));
            }
            bufferCapacity = longInteger(support, "buffer_capacity", 0, Integer.MAX_VALUE);
            JSONArray commandArray = support.optJSONArray("supported_commands");
            if (commandArray != null && commandArray.length() <= 8) {
                for (int i = 0; i < commandArray.length(); i++) {
                    Object c = commandArray.opt(i);
                    if (c instanceof String && ((String) c).length() <= 32) {
                        playerCommands.add((String) c);
                    }
                }
            }
        }
        boolean unpaired = legacy;
        JSONObject access = p.optJSONObject("unpaired_access");
        if (access != null && !legacy) {
            unpaired = access.optBoolean("enabled", false);
        }
        List<String> methods = new ArrayList<>();
        JSONObject pair = p.optJSONObject("supported_pair_methods");
        if (pair != null) {
            for (java.util.Iterator<String> it = pair.keys(); it.hasNext(); ) {
                String key = it.next();
                if (methods.size() < 8 && key.length() <= 32) {
                    methods.add(key);
                }
            }
        }
        List<String> codeFormats = new ArrayList<>();
        boolean staticCode = false;
        if (pair != null) {
            JSONObject dynamic = pair.optJSONObject("dynamic_pairing_code");
            if (dynamic != null) {
                JSONArray offered = dynamic.optJSONArray("formats");
                JSONArray channels = dynamic.optJSONArray("out_channels");
                boolean channelKnown = false;
                for (int i = 0; channels != null && i < channels.length() && i < 8; i++) {
                    Object c = channels.opt(i);
                    channelKnown |= "display".equals(c) || "speaker".equals(c);
                }
                for (int i = 0; channelKnown && offered != null && i < offered.length() && i < 8; i++) {
                    Object f = offered.opt(i);
                    if (("digits".equals(f) || "qr_code".equals(f)) && !codeFormats.contains(f)) {
                        codeFormats.add((String) f);
                    }
                }
            }
            staticCode = pair.optJSONObject("static_pairing_code") != null;
        }
        JSONObject device = p.optJSONObject("device_info");
        String product = device == null ? null : text(device, "product_name", 64, false);
        return new ClientHello(name, roles, formats, bufferCapacity, unpaired, methods, product, clientId, playerCommands, codeFormats, staticCode);
    }

    /** The player object of {@code client/state}. */
    public static final class PlayerState {
        public final Integer volume;
        public final Boolean muted;
        public final int outputDelayMs;
        public final int requiredLeadTimeMs;
        public final int minBufferMs;
        public final List<String> supportedCommands;

        PlayerState(Integer volume, Boolean muted, int outputDelayMs, int requiredLeadTimeMs, int minBufferMs, List<String> commands) {
            this.volume = volume;
            this.muted = muted;
            this.outputDelayMs = outputDelayMs;
            this.requiredLeadTimeMs = requiredLeadTimeMs;
            this.minBufferMs = minBufferMs;
            this.supportedCommands = Collections.unmodifiableList(commands);
        }
    }

    /** {@code client/state}. */
    public static final class ClientState {
        public final boolean available;
        public final PlayerState player; // null if the message has no player object

        ClientState(boolean available, PlayerState player) {
            this.available = available;
            this.player = player;
        }
    }

    public static ClientState parseClientState(JSONObject p) throws ProtocolException {
        return parseClientState(p, null);
    }

    /**
     * {@code client/state} of the older wire: the delay may be called {@code static_delay_ms} and the commands are not repeated here
     * (they were in the hello).
     */
    public static ClientState parseLegacyClientState(JSONObject p, List<String> helloCommands) throws ProtocolException {
        return parseClientState(p, helloCommands);
    }

    private static ClientState parseClientState(JSONObject p, List<String> legacyCommands) throws ProtocolException {
        if (!(p.opt("available") instanceof Boolean)) {
            throw new ProtocolException("available");
        }
        PlayerState player = null;
        if (p.has("player")) {
            JSONObject o = p.optJSONObject("player");
            if (o == null) {
                throw new ProtocolException("player");
            }
            Integer volume = o.has("volume") ? Integer.valueOf(integer(o, "volume", 0, 100, true)) : null;
            Boolean muted = null;
            if (o.has("muted")) {
                if (!(o.opt("muted") instanceof Boolean)) {
                    throw new ProtocolException("muted");
                }
                muted = (Boolean) o.opt("muted");
            }
            List<String> commands = new ArrayList<>();
            JSONArray array = o.optJSONArray("supported_commands");
            if (array == null && legacyCommands != null) {
                commands.addAll(legacyCommands);
            } else {
                if (array == null || array.length() > 8) {
                    throw new ProtocolException("supported_commands");
                }
                for (int i = 0; i < array.length(); i++) {
                    Object c = array.opt(i);
                    if (!(c instanceof String) || ((String) c).length() > 32) {
                        throw new ProtocolException("supported_commands");
                    }
                    commands.add((String) c);
                }
            }
            String delayKey = legacyCommands != null && !o.has("output_delay_ms") ? "static_delay_ms" : "output_delay_ms";
            player = new PlayerState(volume, muted, integer(o, delayKey, 0, 5000, true),
                    integer(o, "required_lead_time_ms", 0, 600_000, true), integer(o, "min_buffer_ms", 0, 5000, true), commands);
        }
        return new ClientState((Boolean) p.opt("available"), player);
    }

    /** {@code client/time}: the client's timestamp in microseconds. */
    public static long parseClientTime(JSONObject p) throws ProtocolException {
        return longInteger(p, "client_transmitted", Long.MIN_VALUE, Long.MAX_VALUE);
    }

    /** {@code client/goodbye}: the reason, as the protocol names it. */
    public static String parseGoodbyeReason(JSONObject p) throws ProtocolException {
        return text(p, "reason", 32, true);
    }

    /** {@code client/pair-init}: the pairing index (the number of pairing activations the client has seen since the last handshake). */
    public static int parsePairingIndex(JSONObject p) throws ProtocolException {
        return integer(p, "pairing_index", 0, Integer.MAX_VALUE, true);
    }

    /** {@code client/pair-finalize} of the Pairing PSK flow: the 43 characters of the long-term PSK. */
    public static String parseLongTermPsk(JSONObject p) throws ProtocolException {
        return text(p, "long_term_psk", 64, true);
    }

    /** {@code pair/abort}: the reason. */
    public static String parsePairAbortReason(JSONObject p) throws ProtocolException {
        return text(p, "reason", 32, true);
    }

    /** The payload of {@code noise/handshake}: the bytes of the Noise message. */
    public static String parseNoiseData(JSONObject p) throws ProtocolException {
        return text(p, "data", 70000, true);
    }

    static String text(JSONObject o, String key, int maxBytes, boolean required) throws ProtocolException {
        Object value = o.opt(key);
        if (value == null || value == JSONObject.NULL) {
            if (required) {
                throw new ProtocolException(key);
            }
            return null;
        }
        if (!(value instanceof String) || ((String) value).getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new ProtocolException(key);
        }
        return (String) value;
    }

    static int integer(JSONObject o, String key, int min, int max, boolean required) throws ProtocolException {
        long value = longInteger(o, key, required ? min : Integer.MIN_VALUE, max);
        if (value < min) {
            throw new ProtocolException(key);
        }
        return (int) value;
    }

    static long longInteger(JSONObject o, String key, long min, long max) throws ProtocolException {
        Object value = o.opt(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            throw new ProtocolException(key);
        }
        long n = ((Number) value).longValue();
        if (n < min || n > max) {
            throw new ProtocolException(key);
        }
        return n;
    }
}
