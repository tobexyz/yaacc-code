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

import java.util.Collections;
import java.util.List;

/**
 * A message of a v4 receiver, read from a {@code Flatbuf} packet by {@link V4Reader}: what the message is and the fields YAACC uses.
 * Immutable. Texts are as the receiver sent them (the caller cleans and cuts them); numbers are plain (seconds, not microseconds).
 * Fields that do not belong to the kind are null.
 */
public final class V4In {
    /** The kinds YAACC acts on; everything else is {@link #OTHER} (and is skipped). */
    public enum Kind {
        LOAD, PROGRESS, VOLUME, STATE, SPEED, RECEIVER_INTRODUCTION, STOP, ERROR, TRACKS, OTHER
    }

    /** One item of a {@code Load}. */
    public static final class Item {
        public final String container;
        public final String url;
        public final String title;
        public final String thumbnailUrl;
        public final Double startSeconds;
        public final Double volume;
        public final Double speed;

        Item(String container, String url, String title, String thumbnailUrl, Double startSeconds, Double volume, Double speed) {
            this.container = container;
            this.url = url;
            this.title = title;
            this.thumbnailUrl = thumbnailUrl;
            this.startSeconds = startSeconds;
            this.volume = volume;
            this.speed = speed;
        }
    }

    public final Kind kind;
    /** {@code Load}: the item that plays now (the first, or the one the queue starts at). */
    public final Item item;
    /** {@code Load}: the index in the queue, null for a single item. */
    public final Integer queueIndex;
    /** {@code Load} of a queue: how many items there are. */
    public final int queueSize;
    /** {@code ProgressChanged}: seconds, null if the receiver did not say or the number is not usable. */
    public final Double positionSeconds;
    public final Double durationSeconds;
    /** {@code VolumeChanged} (0 to 1) and {@code SpeedChanged}. */
    public final Double number;
    /** {@code PlaybackStateChanged}: a {@link V4Schema} {@code STATE_} value; {@code Error}: the kind. */
    public final Integer code;
    /** {@code ReceiverIntroduction}: name, application, version of the receiver. */
    public final String displayName;
    public final String appName;
    public final String appVersion;
    /** {@code TracksAvailable}. */
    public final List<Track> tracks;

    /** A track of the receiver's current item. */
    public static final class Track {
        public final long id;
        public final String language;
        public final String title;
        /** {@link V4Schema#TRACK_VIDEO}, {@link V4Schema#TRACK_AUDIO}, {@link V4Schema#TRACK_SUBTITLE}, or -1 if the receiver did not say. */
        public final int type;

        Track(long id, String language, String title, int type) {
            this.id = id;
            this.language = language;
            this.title = title;
            this.type = type;
        }
    }

    V4In(Kind kind, Item item, Integer queueIndex, int queueSize, Double positionSeconds, Double durationSeconds, Double number,
         Integer code, String displayName, String appName, String appVersion, List<Track> tracks) {
        this.kind = kind;
        this.item = item;
        this.queueIndex = queueIndex;
        this.queueSize = queueSize;
        this.positionSeconds = positionSeconds;
        this.durationSeconds = durationSeconds;
        this.number = number;
        this.code = code;
        this.displayName = displayName;
        this.appName = appName;
        this.appVersion = appVersion;
        this.tracks = tracks == null ? Collections.<Track>emptyList() : tracks;
    }

    static V4In of(Kind kind) {
        return new V4In(kind, null, null, 0, null, null, null, null, null, null, null, null);
    }
}
