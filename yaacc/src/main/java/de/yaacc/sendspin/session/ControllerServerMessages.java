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

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The server's side of the controller and metadata roles (issue #325, K15): the {@code server/state} with its {@code controller} and
 * {@code metadata} objects, and the reading of a controller's {@code client/command}. The client is untrusted: every number is range
 * checked and a violation is a {@link ProtocolException}; what the server sends respects the limits of the protocol (texts are
 * clipped on a character boundary, an over long URL is left out). Pure.
 */
public final class ControllerServerMessages {

    private static final int MAX_TEXT_BYTES = 255;
    private static final int MAX_URL_BYTES = 2048;
    /** A seek by more than this many milliseconds (about 35 years) is nonsense, and the arithmetic stays far from overflow. */
    private static final long MAX_OFFSET_MS = 1L << 40;

    private ControllerServerMessages() {
    }

    /** The {@code controller} object of {@code server/state}. */
    public static final class ControllerView {
        final List<String> supportedCommands;
        final int volume;
        final boolean muted;
        final String repeat;
        final boolean shuffle;
        final Long seekMaxMs;

        public List<String> supportedCommands() { return supportedCommands; }
        public int volume() { return volume; }
        public boolean muted() { return muted; }
        public String repeat() { return repeat; }
        public boolean shuffle() { return shuffle; }
        public Long seekMaxMs() { return seekMaxMs; }

        /** @param seekMaxMs the end of what can be sought in the current item, null if it is not known (then 'seek' is not offered) */
        public ControllerView(List<String> supportedCommands, int volume, boolean muted, String repeat, boolean shuffle, Long seekMaxMs) {
            this.supportedCommands = supportedCommands;
            this.volume = volume;
            this.muted = muted;
            this.repeat = repeat;
            this.shuffle = shuffle;
            this.seekMaxMs = seekMaxMs;
        }
    }

    /** The progress of the {@code metadata} object. */
    public static final class Progress {
        final long trackProgressMs;
        final long trackDurationMs;
        final int playbackSpeed;

        /** @param playbackSpeed times 1000: 1000 plays at normal speed, 0 is paused */
        public Progress(long trackProgressMs, long trackDurationMs, int playbackSpeed) {
            this.trackProgressMs = trackProgressMs;
            this.trackDurationMs = trackDurationMs;
            this.playbackSpeed = playbackSpeed;
        }
    }

    /** The {@code metadata} object of {@code server/state}. Null texts are left out; a year or track below 1 is unknown. */
    public static final class MetadataView {
        public String title() { return title; }
        public String artist() { return artist; }
        public String album() { return album; }
        public String artworkUrl() { return artworkUrl; }

        final long timestampMicros;
        final String title;
        final String artist;
        final String albumArtist;
        final String album;
        final String artworkUrl;
        final int year;
        final int track;
        final Progress progress;

        public MetadataView(long timestampMicros, String title, String artist, String albumArtist, String album, String artworkUrl, int year,
                            int track, Progress progress) {
            this.timestampMicros = timestampMicros;
            this.title = title;
            this.artist = artist;
            this.albumArtist = albumArtist;
            this.album = album;
            this.artworkUrl = artworkUrl;
            this.year = year;
            this.track = track;
            this.progress = progress;
        }
    }

    /** {@code server/state} with the given objects (a null object is omitted, which leaves that state unchanged on the client). */
    public static String serverState(ControllerView controller, MetadataView metadata) {
        try {
            JSONObject payload = new JSONObject();
            if (controller != null) {
                JSONObject c = new JSONObject();
                c.put("supported_commands", new JSONArray(controller.supportedCommands));
                c.put("volume", Math.max(0, Math.min(100, controller.volume)));
                c.put("muted", controller.muted);
                c.put("repeat", controller.repeat);
                c.put("shuffle", controller.shuffle);
                if (controller.seekMaxMs != null) {
                    c.put("seek_max_ms", Math.max(0L, controller.seekMaxMs));
                }
                payload.put("controller", c);
            }
            if (metadata != null) {
                JSONObject m = new JSONObject();
                m.put("timestamp", metadata.timestampMicros);
                put(m, "title", clip(metadata.title, MAX_TEXT_BYTES));
                put(m, "artist", clip(metadata.artist, MAX_TEXT_BYTES));
                put(m, "album_artist", clip(metadata.albumArtist, MAX_TEXT_BYTES));
                put(m, "album", clip(metadata.album, MAX_TEXT_BYTES));
                if (metadata.artworkUrl != null && metadata.artworkUrl.getBytes(StandardCharsets.UTF_8).length <= MAX_URL_BYTES) {
                    m.put("artwork_url", metadata.artworkUrl);
                }
                if (metadata.year > 0) {
                    m.put("year", metadata.year);
                }
                if (metadata.track >= 1) {
                    m.put("track", metadata.track);
                }
                if (metadata.progress != null) {
                    m.put("progress", new JSONObject()
                            .put("track_progress", Math.max(0L, metadata.progress.trackProgressMs))
                            .put("track_duration", Math.max(0L, metadata.progress.trackDurationMs))
                            .put("playback_speed", Math.max(0, metadata.progress.playbackSpeed)));
                }
                payload.put("metadata", m);
            }
            return SendspinMessages.message("server/state", payload);
        } catch (JSONException e) {
            throw new IllegalStateException("a message of plain values is always valid");
        }
    }

    private static void put(JSONObject o, String key, String value) throws JSONException {
        if (value != null) {
            o.put(key, value);
        }
    }

    /** At most {@code maxBytes} of UTF-8, cut between characters (never inside a surrogate pair). */
    static String clip(String text, int maxBytes) {
        if (text == null) {
            return null;
        }
        if (text.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return text;
        }
        int bytes = 0;
        int end = 0;
        while (end < text.length()) {
            int codePoint = text.codePointAt(end);
            int size = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > maxBytes) {
                break;
            }
            bytes += size;
            end += Character.charCount(codePoint);
        }
        return text.substring(0, end);
    }

    /** A {@code client/command} of a controller: the command and its parameter, as far as it has one. */
    public static final class Command {
        public final String command;
        public final Integer volume;
        public final Boolean mute;
        public final Long positionMs;
        public final Long offsetMs;

        Command(String command, Integer volume, Boolean mute, Long positionMs, Long offsetMs) {
            this.command = command;
            this.volume = volume;
            this.mute = mute;
            this.positionMs = positionMs;
            this.offsetMs = offsetMs;
        }
    }

    /**
     * Reads the {@code controller} object of a {@code client/command}.
     *
     * @throws ProtocolException if the object, the command or the parameter that the command needs is missing or out of range
     */
    public static Command parseCommand(JSONObject payload) throws ProtocolException {
        JSONObject controller = payload.optJSONObject("controller");
        if (controller == null) {
            throw new ProtocolException("controller");
        }
        String command = SendspinMessages.text(controller, "command", 32, true);
        if (!ControllerMessages.COMMANDS.contains(command)) {
            throw new ProtocolException("command");
        }
        switch (command) {
            case "volume":
                return new Command(command, SendspinMessages.integer(controller, "volume", 0, 100, true), null, null, null);
            case "mute":
                if (!(controller.opt("mute") instanceof Boolean)) {
                    throw new ProtocolException("mute");
                }
                return new Command(command, null, (Boolean) controller.opt("mute"), null, null);
            case "seek":
                return new Command(command, null, null, SendspinMessages.longInteger(controller, "position_ms", 0, MAX_OFFSET_MS), null);
            case "seek_relative":
                return new Command(command, null, null, null, SendspinMessages.longInteger(controller, "offset_ms", -MAX_OFFSET_MS, MAX_OFFSET_MS));
            default:
                return new Command(command, null, null, null, null);
        }
    }
}
