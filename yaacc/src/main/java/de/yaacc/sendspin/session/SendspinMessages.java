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

        ClientHello(String name, List<String> roles, List<Format> formats, long bufferCapacity, boolean unpaired, List<String> pairMethods,
                    String productName) {
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
    }

    private static final int MAX_ROLES = 64;
    private static final int MAX_FORMATS = 64;

    public static ClientHello parseClientHello(JSONObject p) throws ProtocolException {
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
        }
        boolean unpaired = false;
        JSONObject access = p.optJSONObject("unpaired_access");
        if (access != null) {
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
        JSONObject device = p.optJSONObject("device_info");
        String product = device == null ? null : text(device, "product_name", 64, false);
        return new ClientHello(name, roles, formats, bufferCapacity, unpaired, methods, product);
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
            player = new PlayerState(volume, muted, integer(o, "output_delay_ms", 0, 5000, true),
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
