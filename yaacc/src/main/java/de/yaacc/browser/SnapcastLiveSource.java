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
package de.yaacc.browser;

import android.content.Context;
import android.os.Handler;

import java.util.concurrent.atomic.AtomicBoolean;

import de.yaacc.multiroom.SnapcastHub;
import de.yaacc.multiroom.SnapcastReceivers;

/**
 * The live updates of the Snapcast groups for a screen that shows receiver rows, the counterpart of
 * {@link YxcRowFeed} for MusicCast: instead of polling, the rows follow the control connections of the servers
 * (notifications of a second controller arrive within a moment). Changes arrive on a connection thread; they are
 * moved to the main thread and <b>coalesced</b>: a burst of notifications asks the screen for one refresh,
 * {@value #COALESCE_MILLIS} ms after the first one.
 *
 * <p><b>Lifetime.</b> A screen calls {@link #start()} when it comes to the front and {@link #stop()} when it goes (the
 * same lifetime as the other feeds of {@link ReceiverLiveUpdates}). The hub opens the connections (and the discovery)
 * only between the two, and closes them again after its grace period, so nothing runs while no receivers page is
 * visible. Main thread only (except for the change callback, which any thread may deliver). The hub, the clock and the
 * thread hop are behind interfaces, so the logic is tested on the JVM.
 */
final class SnapcastLiveSource {

    static final long COALESCE_MILLIS = 300L;

    /** What the source needs of the Snapcast part of the app; {@link #forApp} is the real one. */
    interface Hub {
        /** A receivers page is visible: discover and connect. */
        void pageStarted();

        /** A receivers page was hidden. */
        void pageStopped();

        void addChangeListener(SnapcastReceivers.ChangeListener listener);

        void removeChangeListener(SnapcastReceivers.ChangeListener listener);
    }

    private final Hub hub;
    private final Coalescer.Poster poster;
    private final Coalescer coalescer;
    private final AtomicBoolean hopQueued = new AtomicBoolean();
    private volatile boolean running;

    // a change on any thread: one hop to the main thread, which then triggers the coalescer
    private final Runnable hop = new Runnable() {
        @Override
        public void run() {
            hopQueued.set(false);
            if (running) {
                coalescer.trigger();
            }
        }
    };

    private final SnapcastReceivers.ChangeListener changeListener = new SnapcastReceivers.ChangeListener() {
        @Override
        public void onGroupsChanged() {
            if (running && hopQueued.compareAndSet(false, true)) {
                poster.postDelayed(hop, 0);
            }
        }
    };

    /**
     * @param poster    runs things on the main thread, later
     * @param onChanged told on the main thread, coalesced, while the source runs: the groups changed, rebuild the rows
     */
    SnapcastLiveSource(Hub hub, Coalescer.Poster poster, Runnable onChanged) {
        this.hub = hub;
        this.poster = poster;
        this.coalescer = new Coalescer(poster, () -> {
            if (running) {
                onChanged.run();
            }
        }, COALESCE_MILLIS);
    }

    boolean isRunning() {
        return running;
    }

    /** Starts following; does nothing when it runs already. */
    void start() {
        if (running) {
            return;
        }
        running = true;
        hub.addChangeListener(changeListener);
        hub.pageStarted();
    }

    /** Stops following and lets the hub close its connections after its grace period. */
    void stop() {
        if (!running) {
            return;
        }
        running = false;
        hub.removeChangeListener(changeListener);
        coalescer.cancel();
        poster.removeCallbacks(hop);
        hopQueued.set(false);
        hub.pageStopped();
    }

    /** The real hub of the app and the main thread's handler. */
    static SnapcastLiveSource forApp(Context context, Handler handler, Runnable onChanged) {
        final SnapcastHub snapcast = SnapcastHub.getShared(context);
        Hub hub = new Hub() {
            @Override
            public void pageStarted() {
                snapcast.pageStarted();
            }

            @Override
            public void pageStopped() {
                snapcast.pageStopped();
            }

            @Override
            public void addChangeListener(SnapcastReceivers.ChangeListener listener) {
                snapcast.addChangeListener(listener);
            }

            @Override
            public void removeChangeListener(SnapcastReceivers.ChangeListener listener) {
                snapcast.removeChangeListener(listener);
            }
        };
        return new SnapcastLiveSource(hub, Coalescer.forHandler(handler), onChanged);
    }
}
