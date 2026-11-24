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

import de.yaacc.sendspin.discovery.SendspinDirectory;
import de.yaacc.sendspin.SendspinHub;

/**
 * The live updates of the Sendspin players for a screen that shows receiver rows (the counterpart of {@link FCastLiveSource}): the receivers list follows what the directory learns (a receiver announced, gone, added by hand).
 * Changes arrive on a discovery thread, under a lock of the directory: the callback only hops to the main thread and returns, and the
 * hops are <b>coalesced</b> ({@value #COALESCE_MILLIS} ms after the first change).
 *
 * <p><b>Lifetime.</b> {@link #start()} when the screen comes to the front, {@link #stop()} when it goes. The hub browses only between
 * the two (and for its grace period after), so nothing runs while no receivers page is visible. Main thread only, except the change
 * callback. The hub and the clock are behind interfaces, so the logic is tested on the JVM.
 */
final class SendspinLiveSource {

    static final long COALESCE_MILLIS = 300L;

    /** What the source needs of the Sendspin part of the app; {@link #forApp} is the real one. */
    interface Hub {
        /** A receivers page is visible: discover. */
        void pageStarted();

        /** A receivers page was hidden. */
        void pageStopped();

        void addChangeListener(SendspinDirectory.Listener listener);

        void removeChangeListener(SendspinDirectory.Listener listener);
    }

    private final Hub hub;
    private final Coalescer.Poster poster;
    private final Coalescer coalescer;
    private final AtomicBoolean hopQueued = new AtomicBoolean();
    private volatile boolean running;

    private final Runnable hop = new Runnable() {
        @Override
        public void run() {
            hopQueued.set(false);
            if (running) {
                coalescer.trigger();
            }
        }
    };

    private final SendspinDirectory.Listener changeListener = new SendspinDirectory.Listener() {
        @Override
        public void changed() {
            if (running && hopQueued.compareAndSet(false, true)) {
                poster.postDelayed(hop, 0);
            }
        }
    };

    /**
     * @param poster    runs things on the main thread, later
     * @param onChanged told on the main thread, coalesced, while the source runs: the receivers changed, rebuild the rows
     */
    SendspinLiveSource(Hub hub, Coalescer.Poster poster, Runnable onChanged) {
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

    /** Stops following and lets the hub stop browsing after its grace period. */
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
    static SendspinLiveSource forApp(Context context, Handler handler, Runnable onChanged) {
        final SendspinHub sendspin = SendspinHub.getShared(context);
        Hub hub = new Hub() {
            @Override
            public void pageStarted() {
                sendspin.pageStarted();
            }

            @Override
            public void pageStopped() {
                sendspin.pageStopped();
            }

            @Override
            public void addChangeListener(SendspinDirectory.Listener listener) {
                sendspin.addChangeListener(listener);
            }

            @Override
            public void removeChangeListener(SendspinDirectory.Listener listener) {
                sendspin.removeChangeListener(listener);
            }
        };
        return new SendspinLiveSource(hub, Coalescer.forHandler(handler), onChanged);
    }
}
