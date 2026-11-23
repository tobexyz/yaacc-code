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
package de.yaacc.fcast.protocol.v4;

import java.util.ArrayList;
import java.util.List;

import de.yaacc.fcast.protocol.FCastProtocolException;

/**
 * Reads the body of a {@code Flatbuf} packet of a v4 receiver (a serialized {@code Packet}, see {@link V4Schema}). Hand-written
 * on top of the FlatBuffers runtime for the messages YAACC uses (decision D23); a message it does not use is {@link V4In.Kind#OTHER}.
 * Hostile input: the buffer is limited by the caller (the packet size), every access is bounds checked by the runtime, vectors are
 * limited here ({@value #MAX_TRACKS} tracks) and nothing is followed recursively; whatever goes wrong inside the buffer is one
 * {@link FCastProtocolException} of kind {@code BAD_BODY}, the connection goes on.
 */
public final class V4Reader {
    /** The most tracks read from one {@code TracksAvailable}. */
    public static final int MAX_TRACKS = 256;
    /** The longest text of an item (an address, a title) in bytes, and of a name or a format token. */
    static final int TEXT_ITEM = 16 * 1024;
    static final int TEXT_NAME = 1024;
    /** The most format names read from one list of the capabilities. */
    public static final int MAX_FORMATS = 128;

    private V4Reader() {
    }

    public static V4In read(byte[] body) throws FCastProtocolException {
        try {
            return readUnchecked(body);
        } catch (IndexOutOfBoundsException | IllegalArgumentException | java.nio.BufferUnderflowException | NegativeArraySizeException e) {
            throw new FCastProtocolException(FCastProtocolException.Kind.BAD_BODY, "a v4 message that is not a valid buffer", e);
        }
    }

    private static V4In readUnchecked(byte[] body) throws FCastProtocolException {
        if (body == null) {
            throw new FCastProtocolException(FCastProtocolException.Kind.BAD_BODY, "a v4 packet without a body");
        }
        FbTable packet = FbTable.root(body);
        Integer type = packet.u8(V4Schema.PACKET_PAYLOAD_TYPE);
        FbTable payload = packet.table(V4Schema.PACKET_PAYLOAD);
        if (type == null || type == 0 || payload == null) {
            return V4In.of(V4In.Kind.OTHER);
        }
        switch (type) {
            case V4Schema.MSG_LOAD:
                return readLoad(payload);
            case V4Schema.MSG_PROGRESS_CHANGED:
                return new V4In(V4In.Kind.PROGRESS, null, null, 0, seconds(payload.timeMicros(V4Schema.PROGRESS_POSITION)),
                        seconds(payload.timeMicros(V4Schema.PROGRESS_DURATION)), null, null, null, null, null, null);
            case V4Schema.MSG_VOLUME_CHANGED:
                return number(V4In.Kind.VOLUME, payload.f32(V4Schema.VOLUME_VOLUME));
            case V4Schema.MSG_SPEED_CHANGED:
                return number(V4In.Kind.SPEED, payload.f32(V4Schema.SPEED_SPEED));
            case V4Schema.MSG_PLAYBACK_STATE_CHANGED: {
                Integer state = payload.u8(V4Schema.STATE_STATE);
                return new V4In(V4In.Kind.STATE, null, null, 0, null, null, null, state == null ? V4Schema.STATE_IDLE : state, null,
                        null, null, null);
            }
            case V4Schema.MSG_STOP_PLAYBACK:
                return V4In.of(V4In.Kind.STOP);
            case V4Schema.MSG_RECEIVER_INTRODUCTION: {
                FbTable info = payload.table(V4Schema.RECEIVER_DEVICE_INFO);
                if (info == null) {
                    return V4In.of(V4In.Kind.RECEIVER_INTRODUCTION);
                }
                return new V4In(V4In.Kind.RECEIVER_INTRODUCTION, null, null, 0, null, null, null, null,
                        info.string(V4Schema.DEVICE_DISPLAY_NAME, TEXT_NAME), info.string(V4Schema.DEVICE_APP_NAME, TEXT_NAME),
                        info.string(V4Schema.DEVICE_APP_VERSION, TEXT_NAME), null, readCaps(payload.table(V4Schema.RECEIVER_CAPABILITIES)), null, null);
            }
            case V4Schema.MSG_CHANGE_TRACK: {
                Long id = payload.u32(V4Schema.CHANGE_TRACK_ID);
                Integer trackType = payload.u8(V4Schema.CHANGE_TRACK_TYPE);
                return new V4In(V4In.Kind.TRACK_SELECTED, null, null, 0, null, null, null, null, null, null, null, null, null, id,
                        trackType == null ? V4Schema.TRACK_VIDEO : trackType);
            }
            case V4Schema.MSG_ERROR: {
                Integer kind = payload.u8(V4Schema.ERROR_KIND);
                return new V4In(V4In.Kind.ERROR, null, null, 0, null, null, null, kind == null ? 0 : kind, null, null, null, null);
            }
            case V4Schema.MSG_TRACKS_AVAILABLE:
                return readTracks(payload);
            default:
                return V4In.of(V4In.Kind.OTHER);
        }
    }

    private static V4In.Caps readCaps(FbTable caps) {
        if (caps == null) {
            return null;
        }
        FbTable media = caps.table(V4Schema.CAPS_MEDIA);
        FbTable audio = caps.table(V4Schema.CAPS_AUDIO);
        FbTable display = caps.table(V4Schema.CAPS_DISPLAY);
        long[] size = display == null ? null : display.u32Pair(V4Schema.DISPLAY_RESOLUTION);
        Float step = audio == null ? null : audio.f32(V4Schema.AUDIO_VOLUME_STEP);
        if (media == null) {
            return new V4In.Caps(strings(null, 0), strings(null, 0), strings(null, 0), strings(null, 0), strings(null, 0), strings(null, 0),
                    strings(null, 0), false, false, finite(step), size == null ? 0 : size[0], size == null ? 0 : size[1]);
        }
        Boolean external = media.bool(V4Schema.MEDIA_EXTERNAL_SUBTITLES);
        Boolean mirroring = media.bool(V4Schema.MEDIA_MIRRORING);
        return new V4In.Caps(strings(media, V4Schema.MEDIA_PROTOCOLS), strings(media, V4Schema.MEDIA_CONTAINERS),
                strings(media, V4Schema.MEDIA_VIDEO_FORMATS), strings(media, V4Schema.MEDIA_AUDIO_FORMATS),
                strings(media, V4Schema.MEDIA_SUBTITLE_FORMATS), strings(media, V4Schema.MEDIA_HDR_FORMATS),
                strings(media, V4Schema.MEDIA_IMAGE_FORMATS), external != null && external, mirroring != null && mirroring, finite(step),
                size == null ? 0 : size[0], size == null ? 0 : size[1]);
    }

    private static Double finite(Float value) {
        return value == null || value.isNaN() || value.isInfinite() ? null : Double.valueOf(value);
    }

    /** The strings of a vector field, at most {@value #MAX_FORMATS} of them (a receiver lists a few dozen at most). */
    private static List<String> strings(FbTable table, int slot) {
        List<String> list = new ArrayList<>();
        if (table == null) {
            return list;
        }
        int count = Math.min(table.vectorLength(slot), MAX_FORMATS);
        for (int i = 0; i < count; i++) {
            String value = table.stringAt(slot, i, TEXT_NAME);
            if (value != null) {
                list.add(value);
            }
        }
        return list;
    }

    private static V4In number(V4In.Kind kind, Float value) {
        Double number = value == null ? Double.valueOf(0.0) : Double.valueOf(value);
        if (number.isNaN() || number.isInfinite()) {
            number = null;
        }
        return new V4In(kind, null, null, 0, null, null, number, null, null, null, null, null);
    }

    private static V4In readLoad(FbTable load) {
        Integer sourceType = load.u8(V4Schema.LOAD_SOURCE_TYPE);
        FbTable source = load.table(V4Schema.LOAD_SOURCE);
        if (sourceType == null || source == null) {
            return V4In.of(V4In.Kind.OTHER);
        }
        if (sourceType == V4Schema.SOURCE_SINGLE) {
            return new V4In(V4In.Kind.LOAD, readItem(source), null, 1, null, null, null, null, null, null, null, null);
        }
        if (sourceType == V4Schema.SOURCE_QUEUE) {
            int size = source.vectorLength(V4Schema.QUEUE_ITEMS);
            if (size == 0) {
                return V4In.of(V4In.Kind.OTHER);
            }
            Integer start = source.u8(V4Schema.QUEUE_START_INDEX);
            int index = start == null || start >= size ? 0 : start;
            FbTable queueItem = source.tableAt(V4Schema.QUEUE_ITEMS, index);
            FbTable media = queueItem.table(V4Schema.QUEUE_ITEM_MEDIA_ITEM);
            if (media == null) {
                return V4In.of(V4In.Kind.OTHER);
            }
            return new V4In(V4In.Kind.LOAD, readItem(media), index, size, null, null, null, null, null, null, null, null);
        }
        return V4In.of(V4In.Kind.OTHER);
    }

    private static V4In.Item readItem(FbTable item) {
        Float volume = item.f32(V4Schema.ITEM_VOLUME);
        Float speed = item.f32(V4Schema.ITEM_SPEED);
        return new V4In.Item(item.string(V4Schema.ITEM_CONTAINER, TEXT_ITEM), item.string(V4Schema.ITEM_SOURCE_URL, TEXT_ITEM), item.string(V4Schema.ITEM_TITLE, TEXT_ITEM),
                item.string(V4Schema.ITEM_THUMBNAIL_URL, TEXT_ITEM), seconds(item.timeMicros(V4Schema.ITEM_START_TIME)),
                volume == null || volume.isNaN() || volume.isInfinite() ? null : Double.valueOf(volume),
                speed == null || speed.isNaN() || speed.isInfinite() ? null : Double.valueOf(speed));
    }

    private static V4In readTracks(FbTable message) {
        int count = Math.min(message.vectorLength(V4Schema.TRACKS_TRACKS), MAX_TRACKS);
        List<V4In.Track> tracks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            FbTable track = message.tableAt(V4Schema.TRACKS_TRACKS, i);
            Long id = track.u32(V4Schema.TRACK_ID);
            Integer metaType = track.u8(V4Schema.TRACK_METADATA_TYPE);
            int type = -1;
            if (metaType != null) {
                if (metaType == V4Schema.TRACK_META_VIDEO) {
                    type = V4Schema.TRACK_VIDEO;
                } else if (metaType == V4Schema.TRACK_META_AUDIO) {
                    type = V4Schema.TRACK_AUDIO;
                } else if (metaType == V4Schema.TRACK_META_SUBTITLE) {
                    type = V4Schema.TRACK_SUBTITLE;
                }
            }
            tracks.add(new V4In.Track(id == null ? 0 : id, track.string(V4Schema.TRACK_ISO_639, TEXT_NAME), track.string(V4Schema.TRACK_TITLE, TEXT_NAME), type));
        }
        return new V4In(V4In.Kind.TRACKS, null, null, 0, null, null, null, null, null, null, null, tracks);
    }

    /** Microseconds to seconds; null for no value or one that does not fit a signed 64 bit number (an unsigned value of 2^63 or more). */
    private static Double seconds(Long micros) {
        return micros == null || micros < 0 ? null : micros / 1_000_000.0;
    }
}
