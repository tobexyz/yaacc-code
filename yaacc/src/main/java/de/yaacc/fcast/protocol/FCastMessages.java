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
package de.yaacc.fcast.protocol;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The messages of FCast protocol v3 that YAACC sends and reads (JSON bodies, see {@code docs/tech.md}, "FCast"): builders for
 * the sender's messages and parsers for the receiver's. Pure (the platform's {@code org.json}).
 *
 * <p><b>Parsing is lenient about what it does not use</b> (unknown fields are ignored, receivers differ) <b>and strict about what
 * it uses</b>: a field of the wrong type, a number that is not finite, a body that is not an object or is nested deeper than
 * {@link FCastText#MAX_DEPTH} is a {@link FCastProtocolException} (kind {@code BAD_BODY} or {@code BAD_MESSAGE}) for that
 * message only. Text from the receiver is cleaned and cut, numbers are clamped to what is meaningful, so a hostile receiver
 * can not hand the app a negative time, an infinite speed or a name of a megabyte.
 *
 * <p><b>Outgoing URLs are sent exactly as given</b> (owner's decision D9); the only refusal is a message that does not fit into a
 * packet ({@link FCastPacketCodec}).
 */
public final class FCastMessages {
    /** The playback states of a {@code PlaybackUpdate}. */
    public static final int STATE_IDLE = 0;
    public static final int STATE_PLAYING = 1;
    public static final int STATE_PAUSED = 2;

    /** The event types of {@code SubscribeEvent} / {@code Event}. */
    public static final int EVENT_MEDIA_ITEM_START = 0;
    public static final int EVENT_MEDIA_ITEM_END = 1;
    public static final int EVENT_MEDIA_ITEM_CHANGE = 2;

    /** The speeds YAACC asks for; a receiver clamps to 1/16 to 16 itself. */
    public static final double MIN_SPEED = 0.25;
    public static final double MAX_SPEED = 4.0;
    /** The speeds accepted from a receiver. */
    public static final double MIN_REPORTED_SPEED = 1.0 / 16.0;
    public static final double MAX_REPORTED_SPEED = 16.0;
    /** Text YAACC sends (a title). */
    public static final int MAX_TITLE = 200;
    /** The highest version of the plain JSON protocol this code speaks (v1 to v3); v4 is {@link #V4}. */
    public static final int MAX_VERSION = 3;
    /** Version 4 (TLS 1.3 and FlatBuffers); only a connection with a pinned key speaks it (D24). */
    public static final int V4 = 4;

    private FCastMessages() {
    }

    // ------------------------------------------------------------------ received

    /** A message parsed from a receiver. */
    public interface Incoming {
    }

    /** {@code Version}: the protocol version the receiver speaks (1 or more). */
    public static final class Version implements Incoming {
        private final int version;

        Version(int version) {
            this.version = version;
        }

        public int getVersion() {
            return version;
        }
    }

    /** What a receiver says is playing (from {@code Initial} or {@code PlayUpdate}); all fields may be null. */
    public static final class PlayData {
        private final String container;
        private final String url;
        private final String title;
        private final String thumbnailUrl;
        private final Double time;
        private final Double volume;
        private final Double speed;

        PlayData(String container, String url, String title, String thumbnailUrl, Double time, Double volume, Double speed) {
            this.container = container;
            this.url = url;
            this.title = title;
            this.thumbnailUrl = thumbnailUrl;
            this.time = time;
            this.volume = volume;
            this.speed = speed;
        }

        public String getContainer() {
            return container;
        }

        public String getUrl() {
            return url;
        }

        public String getTitle() {
            return title;
        }

        public String getThumbnailUrl() {
            return thumbnailUrl;
        }

        public Double getTime() {
            return time;
        }

        public Double getVolume() {
            return volume;
        }

        public Double getSpeed() {
            return speed;
        }
    }

    /** {@code Initial} of a receiver (protocol 3). */
    public static final class Initial implements Incoming {
        private final String displayName;
        private final String appName;
        private final String appVersion;
        private final PlayData playData;

        Initial(String displayName, String appName, String appVersion, PlayData playData) {
            this.displayName = displayName;
            this.appName = appName;
            this.appVersion = appVersion;
            this.playData = playData;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getAppName() {
            return appName;
        }

        public String getAppVersion() {
            return appVersion;
        }

        /** What is playing now, or null. */
        public PlayData getPlayData() {
            return playData;
        }
    }

    /** {@code PlaybackUpdate}: the state and, if the receiver says so, the position. */
    public static final class PlaybackUpdate implements Incoming {
        private final long generationTime;
        private final int state;
        private final Double time;
        private final Double duration;
        private final Double speed;
        private final Integer itemIndex;

        PlaybackUpdate(long generationTime, int state, Double time, Double duration, Double speed, Integer itemIndex) {
            this.generationTime = generationTime;
            this.state = state;
            this.time = time;
            this.duration = duration;
            this.speed = speed;
            this.itemIndex = itemIndex;
        }

        /** The receiver's clock in milliseconds since 1970 when it made the message (0 if absent); not used for anything but logging. */
        public long getGenerationTime() {
            return generationTime;
        }

        /** {@link #STATE_IDLE}, {@link #STATE_PLAYING} or {@link #STATE_PAUSED}. */
        public int getState() {
            return state;
        }

        /** The position in seconds (not negative), or null. */
        public Double getTime() {
            return time;
        }

        /** The length in seconds (not negative), or null. */
        public Double getDuration() {
            return duration;
        }

        public Double getSpeed() {
            return speed;
        }

        public Integer getItemIndex() {
            return itemIndex;
        }
    }

    /** {@code VolumeUpdate}: the volume 0 to 1. */
    public static final class VolumeUpdate implements Incoming {
        private final long generationTime;
        private final double volume;

        VolumeUpdate(long generationTime, double volume) {
            this.generationTime = generationTime;
            this.volume = volume;
        }

        public long getGenerationTime() {
            return generationTime;
        }

        public double getVolume() {
            return volume;
        }
    }

    /** {@code PlaybackError}: a text of the receiver (cleaned, at most {@link FCastText#MAX_ERROR} characters). */
    public static final class PlaybackError implements Incoming {
        private final String message;

        PlaybackError(String message) {
            this.message = message;
        }

        public String getMessage() {
            return message;
        }
    }

    /** {@code PlayUpdate}: another sender (or the receiver itself) started something. */
    public static final class PlayUpdate implements Incoming {
        private final long generationTime;
        private final PlayData playData;

        PlayUpdate(long generationTime, PlayData playData) {
            this.generationTime = generationTime;
            this.playData = playData;
        }

        public long getGenerationTime() {
            return generationTime;
        }

        /** What was started, or null. */
        public PlayData getPlayData() {
            return playData;
        }
    }

    /** {@code Event} of a subscribed event type (media item start, end or change). */
    public static final class Event implements Incoming {
        private final long generationTime;
        private final int type;
        private final String itemUrl;

        Event(long generationTime, int type, String itemUrl) {
            this.generationTime = generationTime;
            this.type = type;
            this.itemUrl = itemUrl;
        }

        public long getGenerationTime() {
            return generationTime;
        }

        /** One of the {@code EVENT_} constants, or any other number the receiver sent. */
        public int getType() {
            return type;
        }

        /** The URL of the item the event is about, if the receiver says. */
        public String getItemUrl() {
            return itemUrl;
        }
    }

    /** A message of a v4 receiver that is no failure of the item (a seek or volume that was clamped, a refused subtitle file): shown, nothing more. */
    public static final class Notice implements Incoming {
        private final String message;

        Notice(String message) {
            this.message = message;
        }

        public String getMessage() {
            return message;
        }
    }

    /** {@code TracksAvailable} of a v4 receiver: the tracks of the item it plays now (empty when there is none). */
    public static final class Tracks implements Incoming {
        private final java.util.List<FCastTrack> tracks;

        Tracks(java.util.List<FCastTrack> tracks) {
            this.tracks = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(tracks));
        }

        public java.util.List<FCastTrack> getTracks() {
            return tracks;
        }
    }

    /** {@code ChangeTrack} relayed by a v4 receiver: the track of a type that is selected now ({@code null}: none). */
    public static final class TrackSelected implements Incoming {
        private final FCastTrack.Type type;
        private final Long trackId;

        TrackSelected(FCastTrack.Type type, Long trackId) {
            this.type = type;
            this.trackId = trackId;
        }

        public FCastTrack.Type getType() {
            return type;
        }

        public Long getTrackId() {
            return trackId;
        }
    }

    /** What a v4 receiver can play, from its {@code ReceiverIntroduction}. */
    public static final class Capabilities implements Incoming {
        private final FCastCapabilities capabilities;

        Capabilities(FCastCapabilities capabilities) {
            this.capabilities = capabilities;
        }

        public FCastCapabilities getCapabilities() {
            return capabilities;
        }
    }

    /**
     * Parses the packet of a receiver.
     *
     * @return the message, or null for a packet that has no model here: {@code Ping} and {@code Pong} (the connection answers
     * them), the opcodes only a sender sends, and numbers that are no opcode
     * @throws FCastProtocolException {@code BAD_BODY} or {@code BAD_MESSAGE}; the connection goes on after it
     */
    public static Incoming parse(FCastPacket packet) throws FCastProtocolException {
        FCastOpcode opcode = packet.getOpcode();
        if (opcode == null) {
            return null;
        }
        switch (opcode) {
            case VERSION:
                return parseVersion(object(packet));
            case INITIAL:
                return parseInitial(object(packet));
            case PLAYBACK_UPDATE:
                return parsePlaybackUpdate(object(packet));
            case VOLUME_UPDATE:
                return parseVolumeUpdate(object(packet));
            case PLAYBACK_ERROR:
                return new PlaybackError(optText(object(packet), "message", FCastText.MAX_ERROR, ""));
            case PLAY_UPDATE:
                JSONObject update = object(packet);
                return new PlayUpdate(optLong(update, "generationTime"), optPlayData(update, "playData"));
            case EVENT:
                return parseEvent(object(packet));
            default:
                return null;
        }
    }

    private static JSONObject object(FCastPacket packet) throws FCastProtocolException {
        String body = packet.getBody();
        if (body == null) {
            throw new FCastProtocolException(FCastProtocolException.Kind.BAD_BODY, packet.getOpcode() + " without a body");
        }
        if (!FCastText.isAcceptableJson(body)) {
            throw new FCastProtocolException(FCastProtocolException.Kind.BAD_BODY,
                    packet.getOpcode() + " is nested too deep or uses a form that JSON does not have");
        }
        try {
            return new JSONObject(body);
        } catch (JSONException e) {
            throw new FCastProtocolException(FCastProtocolException.Kind.BAD_BODY, packet.getOpcode() + " is not a JSON object", e);
        }
    }

    private static Version parseVersion(JSONObject json) throws FCastProtocolException {
        Object value = json.opt("version");
        if (!(value instanceof Number)) {
            throw bad("version is not a number");
        }
        double number = ((Number) value).doubleValue();
        if (Double.isNaN(number) || Double.isInfinite(number) || number != Math.floor(number) || number < 1 || number > 1000) {
            throw bad("version " + number + " is not a protocol version");
        }
        return new Version((int) number);
    }

    private static Initial parseInitial(JSONObject json) throws FCastProtocolException {
        return new Initial(optText(json, "displayName", FCastText.MAX_NAME, null), optText(json, "appName", FCastText.MAX_NAME, null),
                optText(json, "appVersion", FCastText.MAX_NAME, null), optPlayData(json, "playData"));
    }

    private static PlaybackUpdate parsePlaybackUpdate(JSONObject json) throws FCastProtocolException {
        Object state = json.opt("state");
        if (!(state instanceof Number)) {
            throw bad("state is missing or not a number");
        }
        double s = ((Number) state).doubleValue();
        if (s != STATE_IDLE && s != STATE_PLAYING && s != STATE_PAUSED) {
            throw bad("state " + s + " is not 0, 1 or 2");
        }
        Double time = optDouble(json, "time");
        Double duration = optDouble(json, "duration");
        Double speed = optDouble(json, "speed");
        Double index = optDouble(json, "itemIndex");
        return new PlaybackUpdate(optLong(json, "generationTime"), (int) s,
                time == null ? null : Math.max(0.0, time), duration == null ? null : Math.max(0.0, duration),
                speed == null ? null : FCastText.clamp(speed, MIN_REPORTED_SPEED, MAX_REPORTED_SPEED),
                index == null || index < 0 || index > Integer.MAX_VALUE ? null : (int) Math.floor(index));
    }

    private static VolumeUpdate parseVolumeUpdate(JSONObject json) throws FCastProtocolException {
        Double volume = optDouble(json, "volume");
        if (volume == null) {
            throw bad("volume is missing");
        }
        return new VolumeUpdate(optLong(json, "generationTime"), FCastText.clamp(volume, 0.0, 1.0));
    }

    private static Event parseEvent(JSONObject json) throws FCastProtocolException {
        Object event = json.opt("event");
        if (!(event instanceof JSONObject)) {
            throw bad("event is missing or not an object");
        }
        JSONObject inner = (JSONObject) event;
        Double type = optDouble(inner, "type");
        if (type == null) {
            throw bad("event type is missing");
        }
        String url = null;
        Object item = inner.opt("item");
        if (item instanceof JSONObject) {
            url = optText((JSONObject) item, "url", FCastText.MAX_URL, null);
        }
        return new Event(optLong(json, "generationTime"), (int) Math.floor(type), url);
    }

    private static PlayData optPlayData(JSONObject json, String key) throws FCastProtocolException {
        Object value = json.opt(key);
        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        if (!(value instanceof JSONObject)) {
            throw bad(key + " is not an object");
        }
        JSONObject data = (JSONObject) value;
        String title = null;
        String thumbnail = null;
        Object metadata = data.opt("metadata");
        if (metadata instanceof JSONObject) {
            title = optText((JSONObject) metadata, "title", MAX_TITLE, null);
            thumbnail = optText((JSONObject) metadata, "thumbnailUrl", FCastText.MAX_URL, null);
        }
        Double volume = optDouble(data, "volume");
        Double time = optDouble(data, "time");
        Double speed = optDouble(data, "speed");
        return new PlayData(optText(data, "container", FCastText.MAX_URL, null), optText(data, "url", FCastText.MAX_URL, null),
                title, thumbnail, time == null ? null : Math.max(0.0, time),
                volume == null ? null : FCastText.clamp(volume, 0.0, 1.0),
                speed == null ? null : FCastText.clamp(speed, MIN_REPORTED_SPEED, MAX_REPORTED_SPEED));
    }

    private static String optText(JSONObject json, String key, int max, String fallback) throws FCastProtocolException {
        Object value = json.opt(key);
        if (value == null || value == JSONObject.NULL) {
            return fallback;
        }
        if (!(value instanceof String)) {
            throw bad(key + " is not a text");
        }
        return FCastText.clean((String) value, max);
    }

    private static Double optDouble(JSONObject json, String key) throws FCastProtocolException {
        Object value = json.opt(key);
        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        if (!(value instanceof Number)) {
            throw bad(key + " is not a number");
        }
        double number = ((Number) value).doubleValue();
        if (Double.isNaN(number) || Double.isInfinite(number)) {
            throw bad(key + " is not a finite number");
        }
        return number;
    }

    private static long optLong(JSONObject json, String key) throws FCastProtocolException {
        Double value = optDouble(json, key);
        return value == null || value < 0 || value > Long.MAX_VALUE ? 0L : value.longValue();
    }

    private static FCastProtocolException bad(String message) {
        return new FCastProtocolException(FCastProtocolException.Kind.BAD_MESSAGE, message);
    }

    // ------------------------------------------------------------------ sent

    /** {@code Version} body. */
    public static String version(int version) {
        return "{\"version\":" + version + "}";
    }

    /** {@code Initial} body of a sender; a null or empty part is left out. */
    public static String initialSender(String displayName, String appName, String appVersion) {
        JSONObject json = new JSONObject();
        try {
            putText(json, "displayName", displayName);
            putText(json, "appName", appName);
            putText(json, "appVersion", appVersion);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return json.toString();
    }

    /** What to play. The URL is sent as it is (D9). */
    public static final class PlayRequest {
        private final String container;
        private final String url;
        private Double time;
        private Double volume;
        private Double speed;
        private String title;
        private String thumbnailUrl;

        /**
         * @param container the MIME type of the item (never empty: the caller takes it from the item or by its extension)
         * @param url       the URL the receiver fetches
         */
        public PlayRequest(String container, String url) {
            this.container = container;
            this.url = url;
        }

        /** The position in seconds to start at. */
        public PlayRequest time(double seconds) {
            this.time = seconds;
            return this;
        }

        /** The volume 0 to 1 to play at. */
        public PlayRequest volume(double volume) {
            this.volume = volume;
            return this;
        }

        public PlayRequest speed(double speed) {
            this.speed = speed;
            return this;
        }

        public PlayRequest title(String title) {
            this.title = title;
            return this;
        }

        public PlayRequest thumbnailUrl(String thumbnailUrl) {
            this.thumbnailUrl = thumbnailUrl;
            return this;
        }

        public String getContainer() {
            return container;
        }

        public String getUrl() {
            return url;
        }

        /**
         * @return the {@code Play} body
         * @throws FCastProtocolException {@code BAD_MESSAGE} if the container or the URL is empty or a number is not finite
         */
        public String toJson() throws FCastProtocolException {
            if (container == null || container.trim().isEmpty()) {
                throw bad("a Play needs a container (MIME type)");
            }
            if (url == null || url.isEmpty()) {
                throw bad("a Play needs a URL");
            }
            JSONObject json = new JSONObject();
            try {
                json.put("container", container);
                json.put("url", url);
                if (time != null) {
                    json.put("time", finite(time, "time", 0.0, Double.MAX_VALUE));
                }
                if (volume != null) {
                    json.put("volume", finite(volume, "volume", 0.0, 1.0));
                }
                if (speed != null) {
                    json.put("speed", finite(speed, "speed", MIN_SPEED, MAX_SPEED));
                }
                String cleanTitle = FCastText.clean(title, MAX_TITLE);
                boolean hasTitle = cleanTitle != null && !cleanTitle.isEmpty();
                boolean hasThumbnail = thumbnailUrl != null && !thumbnailUrl.isEmpty();
                if (hasTitle || hasThumbnail) {
                    JSONObject metadata = new JSONObject();
                    metadata.put("type", 0);
                    if (hasTitle) {
                        metadata.put("title", cleanTitle);
                    }
                    if (hasThumbnail) {
                        metadata.put("thumbnailUrl", thumbnailUrl);
                    }
                    json.put("metadata", metadata);
                }
            } catch (JSONException e) {
                throw new FCastProtocolException(FCastProtocolException.Kind.BAD_MESSAGE, "the Play message can not be built", e);
            }
            return json.toString();
        }
    }

    /** {@code Seek} body: seconds, not negative. */
    public static String seek(double seconds) throws FCastProtocolException {
        return "{\"time\":" + number(finite(seconds, "time", 0.0, Double.MAX_VALUE)) + "}";
    }

    /** {@code SetVolume} body: 0 to 1 (a value outside is clamped, not finite is refused). */
    public static String setVolume(double volume) throws FCastProtocolException {
        return "{\"volume\":" + number(finite(volume, "volume", 0.0, 1.0)) + "}";
    }

    /** {@code SetSpeed} body: {@link #MIN_SPEED} to {@link #MAX_SPEED}. */
    public static String setSpeed(double speed) throws FCastProtocolException {
        return "{\"speed\":" + number(finite(speed, "speed", MIN_SPEED, MAX_SPEED)) + "}";
    }

    /** {@code SubscribeEvent} body for an event type, e.g. {@link #EVENT_MEDIA_ITEM_END}. */
    public static String subscribeEvent(int type) {
        return "{\"event\":{\"type\":" + type + "}}";
    }

    /** {@code UnsubscribeEvent} body. */
    public static String unsubscribeEvent(int type) {
        return subscribeEvent(type);
    }

    private static double finite(double value, String name, double min, double max) throws FCastProtocolException {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw bad(name + " is not a finite number");
        }
        return Math.max(min, Math.min(max, value));
    }

    /** A number as JSON text without exponent surprises (a double prints as e.g. 1.0E10 otherwise, which JSON allows but some parsers dislike). */
    private static String number(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return new java.math.BigDecimal(value).round(new java.math.MathContext(12)).stripTrailingZeros().toPlainString();
    }

    private static void putText(JSONObject json, String key, String value) throws JSONException {
        String clean = FCastText.clean(value, FCastText.MAX_NAME);
        if (clean != null && !clean.isEmpty()) {
            json.put(key, clean);
        }
    }
}
