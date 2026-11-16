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
package de.yaacc.multiroom.ui;

import de.yaacc.multiroom.SnapcastGroupDevice;
import de.yaacc.multiroom.control.MonotonicClock;
import de.yaacc.multiroom.discovery.SnapcastServerKey;

/**
 * The hardware volume keys on the focused Snapcast group (11.4). Pure Java: which group is the target is decided by
 * {@link SnapcastKeyFocus}, the Android code only passes the key and shows the answer.
 *
 * <ul>
 *   <li><b>One step</b> is {@value #STEP_PERCENT} points of the <i>group</i> volume (the average of the connected members, set
 *   through {@link SnapcastControls#setGroupVolume}: every member moves by the same number of points), clamped to 0..100.</li>
 *   <li><b>Mute.</b> "Up" on a muted group unmutes it and raises the volume (as the system does for its streams); "down" on a
 *   muted group changes nothing but is still taken, so the phone's own volume does not move instead. At the ends of the range
 *   the key is taken and nothing is sent.</li>
 *   <li><b>Not controllable</b> (the server does not answer, no member is connected, the group is not known): the key is not
 *   taken and the system keeps it, as before 11.4.</li>
 *   <li><b>A held key</b> repeats faster than the server's answer comes back and the model catches up, so a press within
 *   {@value #PENDING_MILLIS} ms of the last one goes on from the value the last press asked for.</li>
 * </ul>
 * Every change goes through the one {@link Sink} (the app's {@link SnapcastControls}): off the main thread, coalesced, failures
 * as a toast.
 */
public final class SnapcastVolumeKeys {

    /** Points of the group volume for one key press. */
    public static final int STEP_PERCENT = 5;
    /** A press this soon after the last one continues from the value the last press asked for. */
    public static final long PENDING_MILLIS = 2_000L;

    /** The two keys. */
    public enum Direction {
        UP,
        DOWN
    }

    /** What the row of a group shows now (volume, mute, controllable); null when the group is not known. */
    public interface Model {
        SnapcastRowText.Row rowOf(String receiverId);
    }

    /** Where the changes go. */
    public interface Sink {
        void setGroupVolume(SnapcastServerKey key, String groupId, int percent);

        void setGroupMuted(SnapcastServerKey key, String groupId, boolean muted);
    }

    /** What one key press does to a group. Immutable. */
    public static final class Decision {
        private static final Decision NOT_HANDLED = new Decision(false, -1, false);

        private final boolean handled;
        private final int volume;
        private final boolean unmute;

        Decision(boolean handled, int volume, boolean unmute) {
            this.handled = handled;
            this.volume = volume;
            this.unmute = unmute;
        }

        /** True when the key is taken (the system must not change the phone's volume). */
        public boolean isHandled() {
            return handled;
        }

        /** The group volume to ask for, or -1 when it stays as it is. */
        public int getVolume() {
            return volume;
        }

        /** True when the group is to be unmuted. */
        public boolean isUnmute() {
            return unmute;
        }
    }

    /** What the Android code shows after a press. Immutable. */
    public static final class Result {
        private static final Result NOT_HANDLED = new Result(false, -1, false);

        private final boolean handled;
        private final int volume;
        private final boolean unmuted;

        Result(boolean handled, int volume, boolean unmuted) {
            this.handled = handled;
            this.volume = volume;
            this.unmuted = unmuted;
        }

        public boolean isHandled() {
            return handled;
        }

        /** The volume the card shows now, or -1 when it is unchanged. */
        public int getVolume() {
            return volume;
        }

        /** True when the mute of the card is to be cleared. */
        public boolean isUnmuted() {
            return unmuted;
        }
    }

    private final Model model;
    private final Sink sink;
    private final MonotonicClock clock;
    private final Object lock = new Object();
    // what the last press asked for (guarded by lock)
    private String pendingId;
    private int pendingVolume;
    private boolean pendingMuted;
    private long pendingAt;

    public SnapcastVolumeKeys(Model model, Sink sink, MonotonicClock clock) {
        this.model = model;
        this.sink = sink;
        this.clock = clock;
    }

    /**
     * The decision for one press.
     *
     * @param percent      the group volume now, -1 when there is none
     * @param controllable whether a change makes sense (the server answers and a member is connected)
     */
    public static Decision decide(Direction direction, int percent, boolean muted, boolean controllable) {
        if (!controllable || percent < 0) {
            return Decision.NOT_HANDLED;
        }
        int current = Math.min(100, percent);
        if (direction == Direction.UP) {
            int raised = Math.min(100, current + STEP_PERCENT);
            return new Decision(true, raised == current ? -1 : raised, muted);
        }
        if (muted) {
            return new Decision(true, -1, false);
        }
        int lowered = Math.max(0, current - STEP_PERCENT);
        return new Decision(true, lowered == current ? -1 : lowered, false);
    }

    /**
     * The user set the group volume with the slider (card or sheet): the next key starts from that value, not from the value
     * of an earlier key press or from a model that has not caught up. The mute state is the group's as the model shows it.
     */
    public void volumeChanged(String receiverId, int percent) {
        SnapcastRowText.Row row = rowFor(receiverId);
        if (row == null) {
            return;
        }
        synchronized (lock) {
            pendingId = receiverId;
            pendingVolume = Math.max(0, Math.min(100, percent));
            pendingMuted = row.isMuted();
            pendingAt = clock.nowMillis();
        }
    }

    /**
     * The user muted or unmuted the group with the symbol: the next key starts from that state; the volume is the one a key
     * press asked for a moment ago, else the model's.
     */
    public void muteChanged(String receiverId, boolean muted) {
        SnapcastRowText.Row row = rowFor(receiverId);
        if (row == null) {
            return;
        }
        synchronized (lock) {
            long now = clock.nowMillis();
            boolean pending = receiverId.equals(pendingId) && now - pendingAt <= PENDING_MILLIS && now >= pendingAt;
            pendingVolume = pending ? pendingVolume : row.getVolume();
            pendingId = receiverId;
            pendingMuted = muted;
            pendingAt = now;
        }
    }

    private SnapcastRowText.Row rowFor(String receiverId) {
        return receiverId == null || SnapcastGroupDevice.parseId(receiverId) == null ? null : model.rowOf(receiverId);
    }

    /**
     * A key press on the group of {@code receiverId} (the id of its receiver row).
     *
     * @return what happened; not handled when the group can not be controlled
     */
    public Result press(String receiverId, Direction direction) {
        SnapcastGroupDevice.Id id = receiverId == null ? null : SnapcastGroupDevice.parseId(receiverId);
        SnapcastRowText.Row row = id == null ? null : model.rowOf(receiverId);
        if (row == null) {
            return Result.NOT_HANDLED;
        }
        long now = clock.nowMillis();
        int percent = row.getVolume();
        boolean muted = row.isMuted();
        synchronized (lock) {
            if (receiverId.equals(pendingId) && now - pendingAt <= PENDING_MILLIS && now >= pendingAt) {
                percent = pendingVolume; // the model has not caught up with the last press
                muted = pendingMuted;    // a press that unmuted is not undone by an old model
            }
            Decision decision = decide(direction, percent, muted, row.isControllable());
            if (!decision.isHandled()) {
                return Result.NOT_HANDLED;
            }
            int shown = decision.getVolume() >= 0 ? decision.getVolume() : percent;
            pendingId = receiverId;
            pendingVolume = shown;
            pendingMuted = muted && !decision.isUnmute();
            pendingAt = now;
            if (decision.isUnmute()) {
                sink.setGroupMuted(id.getServerKey(), id.getGroupId(), false);
            }
            if (decision.getVolume() >= 0) {
                sink.setGroupVolume(id.getServerKey(), id.getGroupId(), decision.getVolume());
            }
            return new Result(true, decision.getVolume(), decision.isUnmute());
        }
    }
}
