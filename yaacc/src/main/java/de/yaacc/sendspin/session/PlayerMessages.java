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
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The player role seen from the client (this app as a player that servers play to): what it says ({@code client/hello} with the
 * {@code player@v1} support object, {@code client/state} with the {@code player} object) and what it reads of a server
 * ({@code server/command} for the player, {@code stream/start}, {@code stream/clear}, the binary audio chunk). Everything read from the
 * network is bounded and checked. Pure.
 */
public final class PlayerMessages {

    private PlayerMessages() {
    }

    // ------------------------------------------------------------------ what the phone says

    /**
     * @param formats        PCM formats as {sample rate, channels, bit depth}, in the order of preference
     * @param bufferCapacity the bytes the player can hold ({@code buffer_capacity})
     * @param roles          the roles it offers, for example {@code player@v1}
     *                       <p>The commands the player takes are not in the hello: the current wire schema puts them in the
     *                       {@code client/state} player object (the reference library rejects other values at the hello level; found
     *                       against it, 2026-11-25).
     */
    public static String clientHello(String name, String productName, boolean unpairedAccess, List<int[]> formats, long bufferCapacity,
                                     List<String> roles) {
        JSONArray formatArray = new JSONArray();
        for (int[] f : formats) {
            formatArray.put(SendspinMessages.json("codec", "pcm", "channels", f[1], "sample_rate", f[0], "bit_depth", f[2]));
        }
        JSONObject payload = SendspinMessages.json("name", name, "supported_roles", new JSONArray(roles),
                "supported_pair_methods", SendspinMessages.json("pairing_psk", new JSONObject()),
                "unpaired_access", SendspinMessages.json("enabled", unpairedAccess));
        if (roles.contains("player@v1")) {
            SendspinMessages.put(payload, "player@v1_support", SendspinMessages.json("supported_formats", formatArray,
                    "buffer_capacity", bufferCapacity));
        }
        if (productName != null) {
            SendspinMessages.put(payload, "device_info", SendspinMessages.json("product_name", productName));
        }
        return SendspinMessages.message("client/hello", payload);
    }

    /**
     * {@code client/state} with the {@code player} object.
     *
     * @param requiredLeadTimeMs how far ahead of the play time a chunk has to arrive at least
     * @param minBufferMs        the audio the player wants to hold at least
     */
    public static String clientState(boolean available, int volume, boolean muted, int outputDelayMs, int requiredLeadTimeMs,
                                     int minBufferMs, List<String> commands) {
        JSONObject player = SendspinMessages.json("volume", volume, "muted", muted, "output_delay_ms", outputDelayMs,
                "required_lead_time_ms", requiredLeadTimeMs, "min_buffer_ms", minBufferMs, "supported_commands", new JSONArray(commands));
        return SendspinMessages.message("client/state", SendspinMessages.json("available", available, "player", player));
    }

    public static String clientStateUnavailable() {
        return SendspinMessages.message("client/state", SendspinMessages.json("available", false));
    }

    // ------------------------------------------------------------------ what a server says

    /** A command for the player of {@code server/command}. */
    public static final class Command {
        public final String command;
        public final Integer volume;
        public final Boolean mute;
        public final Integer outputDelayMs;

        Command(String command, Integer volume, Boolean mute, Integer outputDelayMs) {
            this.command = command;
            this.volume = volume;
            this.mute = mute;
            this.outputDelayMs = outputDelayMs;
        }
    }

    /** @return the command for the player, or null if the message is for no player role; a malformed one is a protocol error */
    public static Command parseCommand(JSONObject p) throws ProtocolException {
        if (!p.has("player")) {
            return null;
        }
        JSONObject o = p.optJSONObject("player");
        if (o == null) {
            throw new ProtocolException("player");
        }
        String command = SendspinMessages.text(o, "command", 32, true);
        switch (command) {
            case "volume":
                return new Command(command, SendspinMessages.integer(o, "volume", 0, 100, true), null, null);
            case "mute":
                if (!(o.opt("mute") instanceof Boolean)) {
                    throw new ProtocolException("mute");
                }
                return new Command(command, null, (Boolean) o.opt("mute"), null);
            case "set_output_delay":
                return new Command(command, null, null, SendspinMessages.integer(o, "output_delay_ms", 0, 5000, true));
            default:
                return new Command(command, null, null, null); // an unknown command: the caller ignores it
        }
    }

    /** The format of a stream to the player of {@code stream/start}. */
    public static final class StreamStart {
        public final String codec;
        public final int sampleRate;
        public final int channels;
        public final int bitDepth;

        StreamStart(String codec, int sampleRate, int channels, int bitDepth) {
            this.codec = codec;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.bitDepth = bitDepth;
        }
    }

    /** @return the format, or null if the start is for no player role */
    public static StreamStart parseStreamStart(JSONObject p) throws ProtocolException {
        if (!p.has("player")) {
            return null;
        }
        JSONObject o = p.optJSONObject("player");
        if (o == null) {
            throw new ProtocolException("player");
        }
        String codec = SendspinMessages.text(o, "codec", 16, true);
        int sampleRate = SendspinMessages.integer(o, "sample_rate", 1, 1_000_000, true);
        int channels = SendspinMessages.integer(o, "channels", 1, 8, true);
        int bitDepth = "opus".equals(codec) ? 0 : SendspinMessages.integer(o, "bit_depth", 1, 32, true);
        return new StreamStart(codec, sampleRate, channels, bitDepth);
    }

    /** True if a {@code stream/clear} or {@code stream/end} concerns the player: it names the player role or no role at all. */
    public static boolean clearsPlayer(JSONObject p) throws ProtocolException {
        List<String> roles = ControllerMessages.parseStreamEndRoles(p);
        return roles == null || roles.contains("player");
    }

    /** The {@code from_timestamp} of a {@code stream/clear} (everything from that time on), null for everything buffered. */
    public static Long clearFrom(JSONObject p) throws ProtocolException {
        return p.has("from_timestamp") ? Long.valueOf(SendspinMessages.longInteger(p, "from_timestamp", Long.MIN_VALUE, Long.MAX_VALUE)) : null;
    }

    /** The audio chunk (the body after the message id): timestamp, send-ahead and the audio. */
    public static final class AudioChunk {
        public final long timestampMicros;
        public final long sendAheadMicros;
        public final byte[] audio;

        AudioChunk(long timestampMicros, long sendAheadMicros, byte[] audio) {
            this.timestampMicros = timestampMicros;
            this.sendAheadMicros = sendAheadMicros;
            this.audio = audio;
        }
    }

    /** @return the chunk, or null if the body is too short to hold the two numbers */
    public static AudioChunk parseAudioChunk(byte[] body) {
        if (body == null || body.length < 12) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(body);
        long timestamp = buffer.getLong();
        long sendAhead = buffer.getInt() & 0xFFFFFFFFL;
        return new AudioChunk(timestamp, sendAhead, Arrays.copyOfRange(body, 12, body.length));
    }

}
