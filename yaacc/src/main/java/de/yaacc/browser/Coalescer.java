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

/**
 * Runs an action once, a little after the last of a burst of triggers: a flood of device announcements asks for a refresh of the
 * receiver rows many times, one rebuild after the burst is enough. Main thread only. The clock is behind {@link Poster}, so the logic
 * is tested on the JVM.
 */
final class Coalescer {

    /** A delayed runner; {@code android.os.Handler} is the real one (see {@link #forHandler}). */
    interface Poster {
        void postDelayed(Runnable runnable, long delayMillis);

        void removeCallbacks(Runnable runnable);
    }

    private final Poster poster;
    private final long delayMillis;
    private final Runnable run;
    private boolean pending;

    Coalescer(Poster poster, Runnable action, long delayMillis) {
        this.poster = poster;
        this.delayMillis = delayMillis;
        this.run = () -> {
            pending = false;
            action.run();
        };
    }

    static Poster forHandler(android.os.Handler handler) {
        return new Poster() {
            @Override
            public void postDelayed(Runnable runnable, long delayMillis) {
                handler.postDelayed(runnable, delayMillis);
            }

            @Override
            public void removeCallbacks(Runnable runnable) {
                handler.removeCallbacks(runnable);
            }
        };
    }

    /** Asks for a run; many calls before the run are one run. */
    void trigger() {
        if (pending) {
            return;
        }
        pending = true;
        poster.postDelayed(run, delayMillis);
    }

    /** Drops a run that is waiting. */
    void cancel() {
        if (pending) {
            pending = false;
            poster.removeCallbacks(run);
        }
    }
}
