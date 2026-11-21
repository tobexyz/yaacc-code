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
package de.yaacc.player;

import java.util.Locale;

import de.yaacc.fcast.protocol.FCastMessages;
import de.yaacc.fcast.protocol.FCastReceiverState;
import de.yaacc.fcast.protocol.FCastText;
import de.yaacc.multiroom.control.MonotonicClock;

/**
 * The decisions of the FCast player that need no Android (task 4.2, design.md section 5). Pure; the player feeds it what the
 * session reports and what the user commands, and does what the answer says.
 *
 * <p><b>The end of an item</b> (once per item, never after a pause or stop of ours, never for an item another sender started):
 * <ol>
 *   <li>the {@code MediaItemEnd} event (its URL is the one the player started, or absent). The desktop receiver sends nothing
 *   else at the end;</li>
 *   <li>a {@code Paused} update within one second of the duration (the Android receiver), unless the pause is ours;</li>
 *   <li>the safety net ({@link #onTick}): the receiver was {@code Playing} within one second of the duration and
 *   {@value #SILENCE_MILLIS} ms passed without an update (a receiver that sends neither).</li>
 * </ol>
 * An {@code Idle} after the item played, without a stop of ours, means the receiver was stopped (by its user or another sender):
 * the player stops too, it does not advance. An {@code Idle} before the item played is the receiver clearing its old item.
 *
 * <p>Not thread safe: the player calls it from its own lock or one thread.
 */
final class FCastPlayerLogic {

    /** What the player has to do about a report. */
    enum Action {
        NONE,
        /** The item played to its end: go on with the next one. */
        ITEM_ENDED,
        /** The receiver stopped by itself or by another sender: stop the player, do not advance. */
        STOPPED_BY_RECEIVER,
        /** Another sender (or the receiver's user) paused the item: show it as paused. */
        EXTERNAL_PAUSE,
        /** Another sender resumed the item that we had paused: show it as playing. */
        EXTERNAL_RESUME
    }

    /** Silence after a {@code Playing} update near the end that counts as the end of the item. */
    static final long SILENCE_MILLIS = 2_000;

    private final MonotonicClock clock;
    private final TrackFailurePolicy failures = new TrackFailurePolicy();

    private boolean active;
    private boolean ended;
    private boolean sawPlaying;
    private boolean pausedByUs;
    private String playedUrl;

    private boolean muted;
    private Double volumeBeforeMute;

    FCastPlayerLogic(MonotonicClock clock) {
        this.clock = clock;
    }

    // ------------------------------------------------------------------ our commands

    /** A new item is started by us. */
    void onPlayCommand(String url) {
        active = true;
        ended = false;
        sawPlaying = false;
        pausedByUs = false;
        playedUrl = url;
    }

    void onPauseCommand() {
        pausedByUs = true;
    }

    void onResumeCommand() {
        pausedByUs = false;
    }

    /** We stop the receiver: whatever it reports now is our doing. */
    void onStopCommand() {
        active = false;
        pausedByUs = false;
        failures.reset();
    }

    /** A new run of the player (a new queue). */
    void reset() {
        onStopCommand();
        playedUrl = null;
    }

    // ------------------------------------------------------------------ what the receiver reports

    Action onState(FCastReceiverState state) {
        if (!active || state == null || state.isItemFromElsewhere()) {
            return Action.NONE;
        }
        FCastReceiverState.Pending pending = state.getPending();
        switch (state.getPlayback()) {
            case PLAYING:
                if (!sawPlaying) {
                    sawPlaying = true;
                    failures.onTrackStarted();
                }
                if (pausedByUs && pending != FCastReceiverState.Pending.PAUSE && pending != FCastReceiverState.Pending.STOP) {
                    // we paused, the receiver plays: somebody resumed it
                    pausedByUs = false;
                    return Action.EXTERNAL_RESUME;
                }
                return Action.NONE;
            case PAUSED:
                if (pausedByUs || pending == FCastReceiverState.Pending.PAUSE || pending == FCastReceiverState.Pending.STOP) {
                    return Action.NONE;
                }
                if (state.hasEnded()) {
                    return end();
                }
                if (sawPlaying && pending == FCastReceiverState.Pending.NONE) {
                    pausedByUs = true; // from now on the pause is the item's state, the end rules treat it like ours
                    return Action.EXTERNAL_PAUSE;
                }
                return Action.NONE;
            case IDLE:
                if (sawPlaying && pending != FCastReceiverState.Pending.STOP && pending != FCastReceiverState.Pending.PLAY) {
                    active = false;
                    return Action.STOPPED_BY_RECEIVER;
                }
                return Action.NONE;
            default:
                return Action.NONE;
        }
    }

    /**
     * @param type    the event type of the receiver
     * @param itemUrl the URL the receiver names, null if none
     */
    Action onEvent(int type, String itemUrl) {
        if (!active || ended || type != FCastMessages.EVENT_MEDIA_ITEM_END) {
            return Action.NONE;
        }
        if (itemUrl != null && playedUrl != null && !itemUrl.equals(FCastText.clean(playedUrl, FCastText.MAX_URL))) {
            return Action.NONE; // the end of some other item
        }
        return end();
    }

    /** The safety net; the player calls it every second or so while an item plays. */
    Action onTick(FCastReceiverState state) {
        if (!active || ended || pausedByUs || state == null || state.isItemFromElsewhere()
                || state.getPlayback() != FCastReceiverState.Playback.PLAYING
                || state.getPending() != FCastReceiverState.Pending.NONE || !state.isNearEnd()) {
            return Action.NONE;
        }
        long silent = clock.nowMillis() - state.getPlaybackUpdatedAt();
        return silent >= SILENCE_MILLIS ? end() : Action.NONE;
    }

    private Action end() {
        if (ended) {
            return Action.NONE;
        }
        ended = true;
        return Action.ITEM_ENDED;
    }

    /**
     * The receiver or the file failed for this item.
     *
     * @param hasNext true if an item follows
     */
    TrackFailurePolicy.Decision onError(boolean hasNext) {
        active = false;
        return failures.onFailure(hasNext);
    }

    // ------------------------------------------------------------------ volume and mute

    /** 0 to 100 (the app) to 0.0 to 1.0 (the protocol). */
    static double toProtocolVolume(int percent) {
        return FCastText.clamp(percent / 100.0, 0.0, 1.0);
    }

    /** 0.0 to 1.0 (the protocol) to 0 to 100; null or NaN is 0. */
    static int toPercent(Double volume) {
        if (volume == null || volume.isNaN()) {
            return 0;
        }
        return (int) Math.round(FCastText.clamp(volume, 0.0, 1.0) * 100);
    }

    /**
     * The protocol has no mute (D10): mute sets the volume to 0 and remembers the old one, unmute sets it back.
     *
     * @param currentVolume the volume the receiver reported, null if unknown
     * @return the volume to send, NaN if nothing is to be sent
     */
    double onMuteRequested(boolean mute, Double currentVolume) {
        if (mute) {
            muted = true;
            volumeBeforeMute = currentVolume != null && currentVolume > 0 ? currentVolume : null;
            return 0.0;
        }
        muted = false;
        Double restore = volumeBeforeMute;
        volumeBeforeMute = null;
        return restore == null ? Double.NaN : restore;
    }

    /** A volume the receiver reported: a value above 0 that we did not set ends the mute. */
    void onVolumeUpdate(double volume) {
        if (volume > 0) {
            muted = false;
            volumeBeforeMute = null;
        }
    }

    boolean isMuted(Double currentVolume) {
        return muted && (currentVolume == null || currentVolume == 0.0);
    }

    // ------------------------------------------------------------------ position

    /**
     * The position for the progress bar: the reported one, moved on by the time since the update while the item plays at the
     * reported speed; never past the duration.
     *
     * @param pausedLocally true after our own pause command, before the receiver confirmed it
     */
    static long positionMillis(FCastReceiverState state, long nowMillis, boolean pausedLocally) {
        Double position = state.getPosition();
        if (position == null || position.isNaN() || position < 0) {
            return 0;
        }
        double millis = position * 1000.0;
        long updatedAt = state.getPlaybackUpdatedAt();
        if (state.getPlayback() == FCastReceiverState.Playback.PLAYING && !pausedLocally && updatedAt > 0 && nowMillis > updatedAt) {
            Double speed = state.getSpeed();
            double factor = speed == null || speed.isNaN() || speed <= 0 ? 1.0 : speed;
            millis += (nowMillis - updatedAt) * factor;
        }
        long duration = durationMillis(state);
        if (duration > 0 && millis > duration) {
            millis = duration;
        }
        return Math.round(millis);
    }

    static long durationMillis(FCastReceiverState state) {
        Double duration = state.getDuration();
        return duration == null || duration.isNaN() || duration <= 0 ? 0 : Math.round(duration * 1000.0);
    }

    /** A seek target in seconds: not negative, not past a known duration. */
    static double seekSeconds(long millis, Double duration) {
        double seconds = Math.max(0, millis) / 1000.0;
        if (duration != null && !duration.isNaN() && duration > 0 && seconds > duration) {
            return duration;
        }
        return seconds;
    }

    /** A picture advances by the slide time, not by the receiver. */
    static boolean isImage(String mimeType) {
        String type = ContentTypes.normalize(mimeType);
        return type != null && type.startsWith("image/");
    }
}
