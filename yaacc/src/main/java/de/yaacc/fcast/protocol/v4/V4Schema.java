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

/**
 * The numbers of the FCast v4 schema ({@code fcast.fbs}, namespace {@code FCast.V4}) that this code uses: the members of the
 * {@code Message} union, the enums and the vtable offsets of the fields (4 for the first field of a table, 6 for the second; a union
 * takes two: the type, then the value). Taken from the schema and cross-checked against the {@code VT_*} constants of the reference
 * implementation's generated code (docs/tech.md, "FCast v4").
 */
public final class V4Schema {
    private V4Schema() {
    }

    // the members of the Message union (0 is NONE)
    public static final int MSG_LOAD = 1;
    public static final int MSG_PROGRESS_CHANGED = 2;
    public static final int MSG_VOLUME_CHANGED = 3;
    public static final int MSG_PLAYBACK_STATE_CHANGED = 4;
    public static final int MSG_SPEED_CHANGED = 5;
    public static final int MSG_SENDER_INTRODUCTION = 6;
    public static final int MSG_RECEIVER_INTRODUCTION = 7;
    public static final int MSG_STOP_PLAYBACK = 8;
    public static final int MSG_TRACKS_AVAILABLE = 13;
    public static final int MSG_CHANGE_TRACK = 14;
    public static final int MSG_ADD_SUBTITLE_SOURCE = 16;
    public static final int MSG_SET_PROGRESS_UPDATE_INTERVAL = 17;
    public static final int MSG_ERROR = 23;

    // PlaybackState
    public static final int STATE_IDLE = 0;
    public static final int STATE_BUFFERING = 1;
    public static final int STATE_PLAYING = 2;
    public static final int STATE_PAUSED = 3;
    public static final int STATE_ENDED = 4;

    // MediaTrackType
    public static final int TRACK_VIDEO = 0;
    public static final int TRACK_AUDIO = 1;
    public static final int TRACK_SUBTITLE = 2;

    // MediaSource union
    public static final int SOURCE_SINGLE = 1;
    public static final int SOURCE_QUEUE = 2;

    // MediaTrackMetadata union
    public static final int TRACK_META_VIDEO = 1;
    public static final int TRACK_META_AUDIO = 2;
    public static final int TRACK_META_SUBTITLE = 3;

    // ErrorKind (names for the user: index = value)
    public static final String[] ERROR_KINDS = {"InvalidOpcode", "ResourceNotFound", "SeekOutOfRange", "VolumeOutOfRange", "RateOutOfRange",
            "UnsupportedFormat", "MalformedBody", "InvalidState", "QueuePositionOutOfRange", "QueueRemovePlayingItem", "QueueFull",
            "InvalidPayloadType", "Internal"};

    // Packet
    public static final int PACKET_PAYLOAD_TYPE = 4;
    public static final int PACKET_PAYLOAD = 6;

    // Load
    public static final int LOAD_SOURCE_TYPE = 4;
    public static final int LOAD_SOURCE = 6;

    // MediaItem
    public static final int ITEM_CONTAINER = 4;
    public static final int ITEM_SOURCE_URL = 6;
    public static final int ITEM_START_TIME = 8;
    public static final int ITEM_VOLUME = 10;
    public static final int ITEM_SPEED = 12;
    public static final int ITEM_HEADERS = 14;
    public static final int ITEM_TITLE = 16;
    public static final int ITEM_THUMBNAIL_URL = 18;

    // Queue and QueueItem
    public static final int QUEUE_ITEMS = 4;
    public static final int QUEUE_START_INDEX = 6;
    public static final int QUEUE_ITEM_MEDIA_ITEM = 4;

    // ProgressChanged
    public static final int PROGRESS_POSITION = 4;
    public static final int PROGRESS_DURATION = 6;

    public static final int VOLUME_VOLUME = 4;
    public static final int STATE_STATE = 4;
    public static final int SPEED_SPEED = 4;

    // SenderIntroduction, ReceiverIntroduction, DeviceInfo
    public static final int SENDER_DEVICE_INFO = 4;
    public static final int RECEIVER_DEVICE_INFO = 4;
    public static final int RECEIVER_CAPABILITIES = 6;
    public static final int DEVICE_DISPLAY_NAME = 4;
    public static final int DEVICE_APP_NAME = 6;
    public static final int DEVICE_APP_VERSION = 8;

    // Error
    public static final int ERROR_KIND = 4;
    public static final int ERROR_PACKET_NUM = 6;

    // TracksAvailable, MediaTrack, ChangeTrack, AddSubtitleSource, SetProgressUpdateInterval (task 7.4)
    public static final int TRACKS_TRACKS = 4;
    public static final int TRACK_ID = 4;
    public static final int TRACK_ISO_639 = 6;
    public static final int TRACK_TITLE = 8;
    public static final int TRACK_METADATA_TYPE = 10;
    public static final int CHANGE_TRACK_ID = 4;
    public static final int CHANGE_TRACK_TYPE = 6;
    public static final int SUBTITLE_URL = 4;
    public static final int SUBTITLE_SELECT = 6;
    public static final int SUBTITLE_NAME = 8;
    public static final int INTERVAL_INTERVAL = 4;
}
