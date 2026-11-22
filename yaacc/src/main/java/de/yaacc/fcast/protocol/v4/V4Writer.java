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

import com.google.flatbuffers.FlatBufferBuilder;

/**
 * Writes the {@code Flatbuf} bodies (a serialized {@code Packet}) that a v4 sender sends. Hand-written on top of the FlatBuffers runtime
 * (decision D23); the buffers are checked against the reference implementation's reader (docs/tech.md). A string is written as it is;
 * the caller limits the sizes (the packet limit of v4 is 512 KiB). Pure.
 */
public final class V4Writer {
    private V4Writer() {
    }

    /** {@code SenderIntroduction}: sent once, right after the TLS handshake. */
    public static byte[] senderIntroduction(String displayName, String appName, String appVersion) {
        FlatBufferBuilder b = new FlatBufferBuilder(128);
        int name = displayName == null ? 0 : b.createString(displayName);
        int app = appName == null ? 0 : b.createString(appName);
        int version = appVersion == null ? 0 : b.createString(appVersion);
        b.startTable(3);
        if (name != 0) {
            b.addOffset(0, name, 0);
        }
        if (app != 0) {
            b.addOffset(1, app, 0);
        }
        if (version != 0) {
            b.addOffset(2, version, 0);
        }
        int info = b.endTable();
        b.startTable(1);
        b.addOffset(0, info, 0);
        int payload = b.endTable();
        b.required(payload, V4Schema.SENDER_DEVICE_INFO);
        return packet(b, V4Schema.MSG_SENDER_INTRODUCTION, payload);
    }

    /**
     * {@code Load} of one item. Optional fields are left out when null.
     *
     * @param startSeconds where to start (not negative), volume 0 to 1, speed
     */
    public static byte[] load(String container, String url, Double startSeconds, Double volume, Double speed, String title, String thumbnailUrl) {
        FlatBufferBuilder b = new FlatBufferBuilder(512);
        int containerOffset = b.createString(container);
        int urlOffset = b.createString(url);
        int titleOffset = title == null || title.isEmpty() ? 0 : b.createString(title);
        int thumbnailOffset = thumbnailUrl == null || thumbnailUrl.isEmpty() ? 0 : b.createString(thumbnailUrl);
        b.startTable(11);
        b.addOffset(0, containerOffset, 0);
        b.addOffset(1, urlOffset, 0);
        if (startSeconds != null) {
            time(b, 2, startSeconds);
        }
        if (volume != null) {
            b.addFloat(volume.floatValue());
            b.slot(3);
        }
        if (speed != null) {
            b.addFloat(speed.floatValue());
            b.slot(4);
        }
        if (titleOffset != 0) {
            b.addOffset(6, titleOffset, 0);
        }
        if (thumbnailOffset != 0) {
            b.addOffset(7, thumbnailOffset, 0);
        }
        int item = b.endTable();
        b.required(item, V4Schema.ITEM_CONTAINER);
        b.required(item, V4Schema.ITEM_SOURCE_URL);
        b.startTable(2);
        b.addByte(0, (byte) V4Schema.SOURCE_SINGLE, 0);
        b.addOffset(1, item, 0);
        int load = b.endTable();
        b.required(load, V4Schema.LOAD_SOURCE);
        return packet(b, V4Schema.MSG_LOAD, load);
    }

    /** {@code ProgressChanged}: a sender seeks with it (the duration is ignored by the receiver). */
    public static byte[] progress(double positionSeconds, double durationSeconds) {
        FlatBufferBuilder b = new FlatBufferBuilder(64);
        b.startTable(2);
        time(b, 0, positionSeconds);
        time(b, 1, durationSeconds);
        return packet(b, V4Schema.MSG_PROGRESS_CHANGED, b.endTable());
    }

    public static byte[] volume(double volume) {
        FlatBufferBuilder b = new FlatBufferBuilder(64);
        b.startTable(1);
        b.addFloat(0, (float) volume, 0.0);
        return packet(b, V4Schema.MSG_VOLUME_CHANGED, b.endTable());
    }

    public static byte[] speed(double speed) {
        FlatBufferBuilder b = new FlatBufferBuilder(64);
        b.startTable(1);
        b.addFloat(0, (float) speed, 0.0);
        return packet(b, V4Schema.MSG_SPEED_CHANGED, b.endTable());
    }

    /** {@code PlaybackStateChanged}: Paused pauses, Playing resumes. */
    public static byte[] state(int state) {
        FlatBufferBuilder b = new FlatBufferBuilder(64);
        b.startTable(1);
        b.addByte(0, (byte) state, 0);
        return packet(b, V4Schema.MSG_PLAYBACK_STATE_CHANGED, b.endTable());
    }

    public static byte[] stop() {
        FlatBufferBuilder b = new FlatBufferBuilder(64);
        b.startTable(0);
        return packet(b, V4Schema.MSG_STOP_PLAYBACK, b.endTable());
    }

    /** {@code ChangeTrack}: select a track of a type; {@code id} null switches that type off (subtitles). */
    public static byte[] changeTrack(Long id, int trackType) {
        FlatBufferBuilder b = new FlatBufferBuilder(64);
        b.startTable(2);
        if (id != null) {
            b.addInt(id.intValue());
            b.slot(0);
        }
        b.addByte(1, (byte) trackType, 0);
        return packet(b, V4Schema.MSG_CHANGE_TRACK, b.endTable());
    }

    /** {@code AddSubtitleSource}: a subtitle file the receiver fetches. */
    public static byte[] addSubtitleSource(String url, boolean select, String name) {
        FlatBufferBuilder b = new FlatBufferBuilder(256);
        int urlOffset = b.createString(url);
        int nameOffset = name == null || name.isEmpty() ? 0 : b.createString(name);
        b.startTable(4);
        b.addOffset(0, urlOffset, 0);
        b.addBoolean(1, select, false);
        if (nameOffset != 0) {
            b.addOffset(2, nameOffset, 0);
        }
        int payload = b.endTable();
        b.required(payload, V4Schema.SUBTITLE_URL);
        return packet(b, V4Schema.MSG_ADD_SUBTITLE_SOURCE, payload);
    }

    /** {@code SetProgressUpdateInterval}. */
    public static byte[] progressInterval(double seconds) {
        FlatBufferBuilder b = new FlatBufferBuilder(64);
        b.startTable(1);
        time(b, 0, seconds);
        return packet(b, V4Schema.MSG_SET_PROGRESS_UPDATE_INTERVAL, b.endTable());
    }

    /** The {@code Time} struct (microseconds as an unsigned 64 bit number) as a field of the table that is being built. */
    private static void time(FlatBufferBuilder b, int slot, double seconds) {
        long micros = Double.isNaN(seconds) || seconds <= 0 ? 0 : seconds >= Long.MAX_VALUE / 1e6 ? Long.MAX_VALUE : Math.round(seconds * 1e6);
        b.prep(8, 8);
        b.putLong(micros);
        b.addStruct(slot, b.offset(), 0);
    }

    private static byte[] packet(FlatBufferBuilder b, int type, int payload) {
        b.startTable(2);
        b.addByte(0, (byte) type, 0);
        b.addOffset(1, payload, 0);
        b.finish(b.endTable());
        return b.sizedByteArray();
    }
}
