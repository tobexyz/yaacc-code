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
package de.yaacc.upnp.server.renderingcontrol;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

import de.yaacc.multiroom.SnapcastRendererOutput;
import de.yaacc.multiroom.SnapcastRendererOutput.Mode;
import de.yaacc.multiroom.SnapcastRendererOutput.Problem;
import de.yaacc.multiroom.SnapcastRendererOutput.Setting;
import de.yaacc.multiroom.control.MonotonicClock;

/**
 * The volume of the YAACC renderer follows its output (task 9.1), decided at <b>every call</b> from the setting:
 * <ul>
 *   <li><b>This device</b> (the default, also a damaged setting): the system volume, as before. Nothing of the group is
 *   asked, woken or touched.</li>
 *   <li><b>Snapcast group:</b> the volume and the mute flag of the chosen group. <i>Reading</i> is the model in memory (no
 *   network; the last known value while the model has none, then 0 and not muted). <i>Setting</i> returns at once and runs on
 *   the sender thread: it waits (bounded, as a play does) for a connection that is coming up, then asks the group side to
 *   send; when the group can not be reached or is gone, the problem is reported and nothing is sent. A quick series
 *   sends only the latest value. The phone's own volume is never read or changed in this mode.</li>
 * </ul>
 *
 * <p><b>No feedback.</b> The path is one way. A set goes to the server; a change that the server reports updates the model,
 * and whoever reports the state to the controllers (the RenderingControl event check, the OpenHome poll) only <i>reads</i>
 * here. Nothing in the read path sets anything, so a volume change by the server can not cause another set.
 *
 * <p>The controller's own reads in group mode ({@link #getPercent}, {@link #isMuted}: Get calls, the OpenHome renderer's
 * poll) wake the connection to the server of the group, at most once in {@link #WAKE_INTERVAL_MILLIS} (the connection closes
 * by itself after a grace period when nobody asks), and so does {@link #wakeNow} for a new subscriber: a controller that asks
 * for the volume sees the group's value without a play. The reads of the change check ({@link #peekPercent},
 * {@link #peekMuted}) never wake: the idle close of the link is itself a change event, and a wake from its check would open
 * the link again every grace period for as long as a controller is subscribed. The last known level is kept for the group it
 * belongs to and forgotten when another group is chosen. Pure Java; the group side, the sender, the clock and the problem
 * report are the app's.
 */
public final class OutputRoutedVolume implements RendererVolume {

    /** How often a read may wake the connection to the server. */
    public static final long WAKE_INTERVAL_MILLIS = 20_000L;

    /** The longest a set waits for a connection that is coming up. */
    static final long MAX_WAIT_MILLIS = SnapcastRendererOutput.DEFAULT_MAX_WAIT_MILLIS;

    /** The volume and the mute flag of the group as of the model. Immutable. */
    public static final class Level {
        private final int percent;
        private final boolean muted;

        public Level(int percent, boolean muted) {
            this.percent = Math.max(0, Math.min(100, percent));
            this.muted = muted;
        }

        public int percent() {
            return percent;
        }

        public boolean muted() {
            return muted;
        }
    }

    /** The group of a server: its facts (and wake), its level from the model, and the sends. */
    public interface GroupSide extends SnapcastRendererOutput.Probe {
        /** The level of the group from the model in memory; null when the model has none. No network. */
        Level level(String receiverId);

        /** Sets the volume of the group (0 to 100) on the server. Returns at once or soon; called on the sender thread. */
        void setVolume(String receiverId, int percent);

        /** Mutes or unmutes the group on the server; as {@link #setVolume}. */
        void setMuted(String receiverId, boolean muted);
    }

    private final Supplier<Setting> settings;
    private final RendererVolume local;
    private final GroupSide group;
    private final Executor sender;
    private final SnapcastRendererOutput.Waiter waiter;
    private final MonotonicClock clock;
    private final Consumer<Problem> problems;

    private final Object lock = new Object();
    // guarded by lock
    private Level lastKnown;
    private String lastKnownFor;
    private long lastWakeMillis;
    private boolean wokeOnce;
    private Integer wantedVolume;
    private Boolean wantedMute;
    private boolean queued;

    /**
     * @param settings the setting as it is now (read at every call)
     * @param local    the system volume
     * @param sender   runs the sets, one at a time and in order (one daemon thread)
     * @param problems told (on the sender thread) why a set was not sent; the app shows a toast
     */
    public OutputRoutedVolume(Supplier<Setting> settings, RendererVolume local, GroupSide group, Executor sender,
                              SnapcastRendererOutput.Waiter waiter, MonotonicClock clock, Consumer<Problem> problems) {
        this.settings = settings;
        this.local = local;
        this.group = group;
        this.sender = sender;
        this.waiter = waiter;
        this.clock = clock;
        this.problems = problems;
    }

    private Setting current() {
        return settings.get();
    }

    @Override
    public int getPercent() {
        return percent(true);
    }

    @Override
    public boolean isMuted() {
        return muted(true);
    }

    @Override
    public int peekPercent() {
        return percent(false);
    }

    @Override
    public boolean peekMuted() {
        return muted(false);
    }

    private int percent(boolean mayWake) {
        Setting setting = current();
        if (setting.mode() != Mode.GROUP) {
            return local.getPercent();
        }
        Level level = read(setting, mayWake);
        return level == null ? 0 : level.percent();
    }

    private boolean muted(boolean mayWake) {
        Setting setting = current();
        if (setting.mode() != Mode.GROUP) {
            return local.isMuted();
        }
        Level level = read(setting, mayWake);
        return level != null && level.muted();
    }

    /**
     * The level of the group now, else the last one known (of this group only), else null. Never sets anything; wakes the
     * connection (at most once in the interval) only when {@code mayWake}.
     */
    private Level read(Setting setting, boolean mayWake) {
        String id = setting.receiverId();
        if (id == null) {
            return null;
        }
        if (mayWake) {
            wake(id, false);
        }
        Level level = group.level(id);
        synchronized (lock) {
            if (!id.equals(lastKnownFor)) {
                lastKnown = null; // another group: its level is not the old one's
                lastKnownFor = id;
            }
            if (level != null) {
                lastKnown = level;
            }
            return lastKnown;
        }
    }

    private void wake(String id, boolean force) {
        long now = clock.nowMillis();
        synchronized (lock) {
            if (!force && wokeOnce && now - lastWakeMillis < WAKE_INTERVAL_MILLIS) {
                return;
            }
            wokeOnce = true;
            lastWakeMillis = now;
        }
        group.wake(id);
    }

    /**
     * A control point subscribed to the renderer's events (or the output was switched to a group while one is subscribed):
     * wake the connection to the server of the group once, now, so that the state it is told is the group's. Only this and the
     * controller's own reads ({@link #getPercent}, {@link #isMuted}) wake; the check that a change event triggers
     * ({@link #peekPercent}) never does, or the idle close of the link would bring it back every 30 seconds.
     */
    @Override
    public void wakeNow() {
        Setting setting = current();
        if (setting.mode() == Mode.GROUP && setting.receiverId() != null) {
            wake(setting.receiverId(), true);
        }
    }

    @Override
    public void setPercent(int desired) {
        Setting setting = current();
        if (setting.mode() != Mode.GROUP) {
            local.setPercent(desired);
            return;
        }
        synchronized (lock) {
            wantedVolume = Math.max(0, Math.min(100, desired));
        }
        schedule();
    }

    @Override
    public void setMuted(boolean mute) {
        Setting setting = current();
        if (setting.mode() != Mode.GROUP) {
            local.setMuted(mute);
            return;
        }
        synchronized (lock) {
            wantedMute = mute;
        }
        schedule();
    }

    private void schedule() {
        synchronized (lock) {
            if (queued) {
                return;
            }
            queued = true;
        }
        try {
            sender.execute(this::send);
        } catch (RejectedExecutionException ended) {
            synchronized (lock) {
                queued = false;
            }
        }
    }

    /** On the sender thread: the latest wanted values, once the group is there. */
    private void send() {
        Integer volume;
        Boolean mute;
        synchronized (lock) {
            volume = wantedVolume;
            mute = wantedMute;
            wantedVolume = null;
            wantedMute = null;
            queued = false;
        }
        if (volume == null && mute == null) {
            return;
        }
        // the setting is read again: it may have been switched while the call waited in the queue
        Setting setting = current();
        SnapcastRendererOutput.Decision decision = SnapcastRendererOutput.resolve(setting, false, group, waiter, MAX_WAIT_MILLIS);
        if (decision.output() != SnapcastRendererOutput.Output.GROUP) {
            if (setting.mode() == Mode.GROUP) {
                problems.accept(decision.problem());
            }
            return; // the output is this device again: a value meant for the group is not applied to the phone
        }
        String id = decision.receiverId();
        if (volume != null) {
            group.setVolume(id, volume);
        }
        if (mute != null) {
            group.setMuted(id, mute);
        }
    }
}
