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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.yaacc.fcast.protocol.v4.V4In;
import de.yaacc.fcast.protocol.v4.V4Reader;
import de.yaacc.fcast.protocol.v4.V4Schema;
import de.yaacc.fcast.protocol.v4.V4Writer;

/**
 * Puts version 4 below the session (decision D23): what a v4 receiver sends is turned into the same {@link FCastMessages.Incoming}
 * objects that the session reads from a v3 receiver, and the commands of the session (which are built as v3 bodies) are turned into v4
 * messages. The player, the card and the sheet do not know which version runs.
 *
 * <p><b>Incoming.</b> v4 reports the state, the position, the speed and the volume in separate messages; v3 in one update. This class
 * keeps what it knows (state, position, duration, speed, the URL of the item) and emits a complete {@code PlaybackUpdate} after each
 * change, but only once the receiver has said what the state is (a position alone would have no state to go with). {@code Ended} is
 * what the Android receiver of v3 does: an update {@code Paused} at the duration, then the end event of the item. A {@code Load} (ours
 * relayed by the receiver, another sender's, or the receiver's own) is a {@code PlayUpdate}. Text from the receiver is cleaned and cut
 * as in v3. One instance per connection, used by the reader thread only.
 */
final class FCastV4Translator {
    private Integer state;          // FCastMessages.STATE_*, null until the receiver says
    private Double position;
    private Double duration;
    private Double speed;
    private String itemUrl;
    private boolean started;        // the receiver said Playing or Paused for this item (Buffering is on the way, not started)

    /** @return the messages for the session (may be empty) */
    List<FCastMessages.Incoming> incoming(byte[] body) throws FCastProtocolException {
        V4In in = V4Reader.read(body);
        List<FCastMessages.Incoming> out = new ArrayList<>(2);
        switch (in.kind) {
            case RECEIVER_INTRODUCTION:
                out.add(new FCastMessages.Initial(FCastText.clean(in.displayName, FCastText.MAX_NAME), FCastText.clean(in.appName, FCastText.MAX_NAME),
                        FCastText.clean(in.appVersion, FCastText.MAX_NAME), null));
                if (in.caps != null) {
                    out.add(new FCastMessages.Capabilities(capabilities(in.caps)));
                }
                break;
            case TRACKS:
                out.add(new FCastMessages.Tracks(tracks(in.tracks)));
                break;
            case TRACK_SELECTED:
                out.add(new FCastMessages.TrackSelected(trackType(in.trackType), in.trackId));
                break;
            case LOAD: {
                V4In.Item item = in.item;
                itemUrl = FCastText.clean(item.url, FCastText.MAX_URL);
                state = null;
                started = false;
                position = null;
                duration = null;
                out.add(new FCastMessages.Tracks(Collections.<FCastTrack>emptyList())); // the tracks of the old item are gone
                speed = item.speed == null ? null : FCastText.clamp(item.speed, FCastMessages.MIN_REPORTED_SPEED, FCastMessages.MAX_REPORTED_SPEED);
                out.add(new FCastMessages.PlayUpdate(0L, new FCastMessages.PlayData(FCastText.clean(item.container, FCastText.MAX_URL), itemUrl,
                        FCastText.clean(item.title, FCastMessages.MAX_TITLE), FCastText.clean(item.thumbnailUrl, FCastText.MAX_URL),
                        item.startSeconds, item.volume == null ? null : FCastText.clamp(item.volume, 0.0, 1.0), speed)));
                break;
            }
            case STATE:
                applyState(in.code == null ? V4Schema.STATE_IDLE : in.code, out);
                break;
            case PROGRESS:
                if (in.positionSeconds != null) {
                    position = in.positionSeconds;
                }
                if (in.durationSeconds != null && in.durationSeconds > 0) {
                    duration = in.durationSeconds;
                }
                emitUpdate(out);
                break;
            case SPEED:
                if (in.number != null) {
                    speed = FCastText.clamp(in.number, FCastMessages.MIN_REPORTED_SPEED, FCastMessages.MAX_REPORTED_SPEED);
                    emitUpdate(out);
                }
                break;
            case VOLUME:
                if (in.number != null) {
                    out.add(new FCastMessages.VolumeUpdate(0L, FCastText.clamp(in.number, 0.0, 1.0)));
                }
                break;
            case STOP:
                applyState(V4Schema.STATE_IDLE, out);
                break;
            case ERROR: {
                int kind = in.code == null ? 0 : in.code;
                String name = kind >= 0 && kind < V4Schema.ERROR_KINDS.length ? V4Schema.ERROR_KINDS[kind] : "error " + kind;
                // only an error that comes before the item has started playing fails the item (the receiver could not load it); a seek or a
                // volume that was clamped, a subtitle file that was refused and the like come while it plays: shown, no reason to skip it
                boolean itemFatal = kind == 1 || kind == 5 || kind == 6 || kind == 12;
                boolean beforePlaying = !started;
                String text = "the receiver reported: " + name;
                out.add(itemFatal && beforePlaying ? new FCastMessages.PlaybackError(text) : new FCastMessages.Notice(text));
                break;
            }
            default:
                break; // tracks (task 7.4) and the messages that only a sender sends
        }
        return out;
    }

    private static final int MAX_FORMAT = 32;

    private static FCastCapabilities capabilities(V4In.Caps caps) {
        return new FCastCapabilities(formats(caps.protocols), formats(caps.containers), formats(caps.videoFormats), formats(caps.audioFormats),
                formats(caps.subtitleFormats), formats(caps.hdrFormats), formats(caps.imageFormats), caps.externalSubtitles, caps.mirroring,
                caps.volumeStep == null ? null : FCastText.clamp(caps.volumeStep, 0.0, 1.0),
                (int) Math.min(caps.displayWidth, 100_000), (int) Math.min(caps.displayHeight, 100_000));
    }

    private static List<String> formats(List<String> raw) {
        List<String> clean = new ArrayList<>(raw.size());
        for (String item : raw) {
            String text = FCastText.clean(item, MAX_FORMAT);
            if (text != null && !text.isEmpty()) {
                clean.add(text);
            }
        }
        return clean;
    }

    private static List<FCastTrack> tracks(List<V4In.Track> raw) {
        List<FCastTrack> list = new ArrayList<>(raw.size());
        for (V4In.Track track : raw) {
            list.add(new FCastTrack(track.id, trackType(track.type), FCastText.clean(track.language, 16), FCastText.clean(track.title, FCastText.MAX_NAME)));
        }
        return list;
    }

    private static FCastTrack.Type trackType(Integer v4Type) {
        if (v4Type == null) {
            return FCastTrack.Type.UNKNOWN;
        }
        switch (v4Type) {
            case V4Schema.TRACK_VIDEO:
                return FCastTrack.Type.VIDEO;
            case V4Schema.TRACK_AUDIO:
                return FCastTrack.Type.AUDIO;
            case V4Schema.TRACK_SUBTITLE:
                return FCastTrack.Type.SUBTITLE;
            default:
                return FCastTrack.Type.UNKNOWN;
        }
    }

    private void applyState(int v4State, List<FCastMessages.Incoming> out) {
        switch (v4State) {
            case V4Schema.STATE_PLAYING:
            case V4Schema.STATE_BUFFERING: // v3 has no buffering: the receiver is on its way to playing
                state = FCastMessages.STATE_PLAYING;
                started |= v4State == V4Schema.STATE_PLAYING;
                emitUpdate(out);
                break;
            case V4Schema.STATE_PAUSED:
                state = FCastMessages.STATE_PAUSED;
                started = true;
                emitUpdate(out);
                break;
            case V4Schema.STATE_ENDED:
                // what the Android receiver of v3 reports at the end of an item: Paused at the duration, then the end event
                state = FCastMessages.STATE_PAUSED;
                if (duration != null) {
                    position = duration;
                }
                emitUpdate(out);
                out.add(new FCastMessages.Event(0L, FCastMessages.EVENT_MEDIA_ITEM_END, itemUrl));
                break;
            default:
                state = FCastMessages.STATE_IDLE;
                started = false;
                position = null;
                duration = null;
                out.add(new FCastMessages.PlaybackUpdate(0L, FCastMessages.STATE_IDLE, null, null, null, null));
                out.add(new FCastMessages.Tracks(Collections.<FCastTrack>emptyList()));
        }
    }

    private void emitUpdate(List<FCastMessages.Incoming> out) {
        if (state == null || state == FCastMessages.STATE_IDLE) {
            return;
        }
        out.add(new FCastMessages.PlaybackUpdate(0L, state, position, duration, speed, null));
    }

    // ------------------------------------------------------------------ outgoing

    /**
     * Turns a command of the session (an opcode of v3 and its JSON body) into the body of a {@code Flatbuf} packet.
     *
     * @return the bytes, or null if v4 has nothing to send for it (the event subscriptions: v4 receivers report the end by themselves)
     * @throws FCastProtocolException {@code BAD_MESSAGE} for a body that is not what the builders of {@link FCastMessages} make,
     *                                {@code UNSUPPORTED} for an opcode that has no v4 counterpart
     */
    static byte[] outgoing(FCastOpcode opcode, String jsonBody) throws FCastProtocolException {
        try {
            switch (opcode) {
                case PLAY: {
                    JSONObject json = new JSONObject(jsonBody);
                    JSONObject metadata = json.optJSONObject("metadata");
                    return V4Writer.load(json.getString("container"), json.getString("url"), optDouble(json, "time"),
                            optDouble(json, "volume"), optDouble(json, "speed"), metadata == null ? null : metadata.optString("title", null),
                            metadata == null ? null : metadata.optString("thumbnailUrl", null));
                }
                case PAUSE:
                    return V4Writer.state(V4Schema.STATE_PAUSED);
                case RESUME:
                    return V4Writer.state(V4Schema.STATE_PLAYING);
                case STOP:
                    return V4Writer.stop();
                case SEEK:
                    return V4Writer.progress(new JSONObject(jsonBody).getDouble("time"), 0);
                case SET_VOLUME:
                    return V4Writer.volume(new JSONObject(jsonBody).getDouble("volume"));
                case SET_SPEED:
                    return V4Writer.speed(new JSONObject(jsonBody).getDouble("speed"));
                case SUBSCRIBE_EVENT:
                case UNSUBSCRIBE_EVENT:
                    return null;
                default:
                    throw new FCastProtocolException(FCastProtocolException.Kind.UNSUPPORTED, opcode + " has no message in version 4");
            }
        } catch (JSONException | NullPointerException e) {
            throw new FCastProtocolException(FCastProtocolException.Kind.BAD_MESSAGE, "the command can not be sent in version 4", e);
        }
    }

    private static Double optDouble(JSONObject json, String key) throws JSONException {
        return json.has(key) && !json.isNull(key) ? Double.valueOf(json.getDouble(key)) : null;
    }

    /** {@code ChangeTrack}: selects the track {@code id} of the type, or none ({@code id} null; subtitles). */
    static byte[] changeTrack(FCastTrack.Type type, Long id) {
        return V4Writer.changeTrack(id, type == FCastTrack.Type.AUDIO ? V4Schema.TRACK_AUDIO : type == FCastTrack.Type.VIDEO ? V4Schema.TRACK_VIDEO
                : V4Schema.TRACK_SUBTITLE);
    }

    /** {@code AddSubtitleSource}. */
    static byte[] addSubtitle(String url, boolean select, String name) {
        return V4Writer.addSubtitleSource(url, select, FCastText.clean(name, FCastText.MAX_NAME));
    }

    /** The sender's introduction (name, application, version), sent once after the TLS handshake. */
    static byte[] senderIntroduction(String displayName, String appName, String appVersion) {
        return V4Writer.senderIntroduction(FCastText.clean(displayName, FCastText.MAX_NAME), FCastText.clean(appName, FCastText.MAX_NAME),
                FCastText.clean(appVersion, FCastText.MAX_NAME));
    }

    static List<FCastMessages.Incoming> none() {
        return Collections.emptyList();
    }
}
