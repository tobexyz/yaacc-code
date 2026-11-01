/*
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
package de.yaacc.browser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The decisions behind the live volume and mute of the receiver rows (device sheet and Receiver tab),
 * without any Android type so they are unit-tested on the JVM: which remote receiver is read in
 * which poll cycle, how long a receiver that fails is left alone, and when a value that was read must
 * not be shown because the user is touching the row.
 * <p>
 * <b>Refresh schedule.</b> The host ticks every 2 s with the receivers that are on screen. A receiver is
 * read at most every {@link #REFRESH_INTERVAL_MS} (3rd tick), never twice at the same time (a request
 * that is still running skips the receiver), and after failures the pause doubles up to
 * {@link #MAX_BACKOFF_MS}; the first success starts the normal interval again.
 * <p>
 * <b>User rule.</b> Anything the user does to a row (dragging the slider, moving it by key, tapping mute)
 * is the newer truth: a read is dropped while the user touches the row, for {@link #USER_GRACE_MS} after
 * the last action (the receiver may not have applied the new value yet), and when the user acted after
 * the read was started (the answer describes the time before).
 * <p>
 * Times are milliseconds of a monotonic clock, handed in by the caller. Main thread only.
 */
final class ReceiverStateRefreshPlan {
    /** A receiver is read at most this often (every 3rd tick of the 2 s poll). */
    static final long REFRESH_INTERVAL_MS = 6000;
    /** Upper bound of the pause after repeated failures. */
    static final long MAX_BACKOFF_MS = 60000;
    /** Reads are dropped this long after the user's last action on the row. */
    static final long USER_GRACE_MS = 3000;
    /** A touch whose end was never reported (row detached mid-drag) stops counting after this. */
    static final long TOUCH_TIMEOUT_MS = 30000;

    private static final int MAX_SHIFT = 6;

    private static final class Schedule {
        long nextDueAt;
        int failures;
        boolean inFlight;
    }

    private final Map<String, Schedule> schedules = new HashMap<>();
    private final Map<String, Long> touching = new HashMap<>();
    private final Map<String, Long> lastUserAction = new HashMap<>();

    /**
     * The receivers to read now, out of the ones on screen: not in flight, and due. Every returned id
     * counts as in flight until {@link #refreshSucceeded} or {@link #refreshFailed} is called for it.
     * A receiver not seen before is due at once.
     */
    List<String> dueForRefresh(Collection<String> idsOnScreen, long now) {
        List<String> due = new ArrayList<>();
        for (String id : idsOnScreen) {
            Schedule schedule = schedules.get(id);
            if (schedule == null) {
                schedule = new Schedule();
                schedules.put(id, schedule);
            }
            if (!schedule.inFlight && now >= schedule.nextDueAt) {
                schedule.inFlight = true;
                due.add(id);
            }
        }
        return due;
    }

    /** The request for the receiver answered: next read after the normal interval. */
    void refreshSucceeded(String id, long now) {
        Schedule schedule = schedules.get(id);
        if (schedule == null) {
            return;
        }
        schedule.inFlight = false;
        schedule.failures = 0;
        schedule.nextDueAt = now + REFRESH_INTERVAL_MS;
    }

    /** The request failed (error, timeout, no answer): next read after a doubled pause. */
    void refreshFailed(String id, long now) {
        Schedule schedule = schedules.get(id);
        if (schedule == null) {
            return;
        }
        schedule.inFlight = false;
        schedule.failures++;
        schedule.nextDueAt = now + backoffMs(schedule.failures);
    }

    /** Pause after the n-th failure in a row: 12 s, 24 s, 48 s, then {@link #MAX_BACKOFF_MS}. */
    static long backoffMs(int failures) {
        int shift = Math.max(0, Math.min(failures, MAX_SHIFT));
        return Math.min(REFRESH_INTERVAL_MS << shift, MAX_BACKOFF_MS);
    }

    /** Forgets the schedule (requests in flight, pauses) and the user's actions; for the host's start and stop. */
    void reset() {
        schedules.clear();
        touching.clear();
        lastUserAction.clear();
    }

    /** The user put a finger on the row's slider (or started a key action on it). */
    void userTouchStarted(String id, long now) {
        touching.put(id, now);
        lastUserAction.put(id, now);
    }

    void userTouchEnded(String id, long now) {
        touching.remove(id);
        lastUserAction.put(id, now);
    }

    /** The user changed the row's slider or mute box. */
    void userChanged(String id, long now) {
        lastUserAction.put(id, now);
    }

    /**
     * Whether a value that was read must be dropped instead of shown.
     *
     * @param readStartedAt when the read was started; for a read on the main thread, now
     */
    boolean shouldIgnoreRead(String id, long readStartedAt, long now) {
        Long touchStart = touching.get(id);
        if (touchStart != null && now - touchStart < TOUCH_TIMEOUT_MS) {
            return true;
        }
        Long last = lastUserAction.get(id);
        return last != null && (last >= readStartedAt || now - last < USER_GRACE_MS);
    }

    /** A value in the range of the slider: 0 to 100. */
    static int clampPercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }
}
