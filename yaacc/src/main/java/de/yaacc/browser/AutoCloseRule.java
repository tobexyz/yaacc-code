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
 * The rule "may the app close itself now" of the background idle timer ({@link BackgroundIdleTimer}): nothing that serves,
 * plays or listens may be cut off by it. The UPnP services (the media server, the renderer, the media proxy), every player
 * and, since task 11.5 of issue 319, a running Snapcast client session (the listen test, later the renderer client) and a
 * Snapcast feed count as "something is running". Pure: the timer reads the facts when it fires and only asks this class.
 *
 * <p>This is the rule of the <b>automatic</b> close only. The shutdown (sleep) timer that the user sets and the exit action
 * are the user's own decision and end the app whatever runs (as for the UPnP services); a Snapcast client session that ends
 * that way is recorded first, see {@code Yaacc.exit()}.
 */
public final class AutoCloseRule {

    /** What keeps the app open; {@link #NONE} when nothing does. */
    public enum Keep {
        NONE, PLAYER, LOCAL_SERVER, RENDERER, PROXY, SNAPCAST_CLIENT, SNAPCAST_FEED
    }

    /** What is running, read when the timer fires. Mutable builder for the timer, read once by the rule. */
    public static final class Facts {
        private boolean playersActive;
        private boolean serverEnabled;
        private boolean rendererEnabled;
        private boolean proxyEnabled;
        private boolean snapcastClientSessionActive;
        private boolean snapcastFeedActive;

        /** At least one player exists (of any kind). */
        public Facts playersActive(boolean value) {
            this.playersActive = value;
            return this;
        }

        /** The UPnP media server is switched on. */
        public Facts serverEnabled(boolean value) {
            this.serverEnabled = value;
            return this;
        }

        /** The UPnP renderer (receiver) is switched on. */
        public Facts rendererEnabled(boolean value) {
            this.rendererEnabled = value;
            return this;
        }

        /** The media proxy is switched on. */
        public Facts proxyEnabled(boolean value) {
            this.proxyEnabled = value;
            return this;
        }

        /** A Snapcast client session is starting or running (not an ended one). */
        public Facts snapcastClientSessionActive(boolean value) {
            this.snapcastClientSessionActive = value;
            return this;
        }

        /** A Snapcast feed (a player that plays into a Snapcast group) exists. */
        public Facts snapcastFeedActive(boolean value) {
            this.snapcastFeedActive = value;
            return this;
        }
    }

    private AutoCloseRule() {
    }

    /**
     * @return true when nothing runs and the app may close itself; false when something runs, and also for no facts (an
     * unknown state never closes the app)
     */
    public static boolean mayCloseNow(Facts facts) {
        return reasonToStay(facts) == Keep.NONE;
    }

    /** @return the first thing that keeps the app open, {@link Keep#NONE} when nothing does; {@link Keep#PLAYER} for no facts */
    public static Keep reasonToStay(Facts facts) {
        if (facts == null) {
            return Keep.PLAYER;
        }
        if (facts.playersActive) {
            return Keep.PLAYER;
        }
        if (facts.serverEnabled) {
            return Keep.LOCAL_SERVER;
        }
        if (facts.rendererEnabled) {
            return Keep.RENDERER;
        }
        if (facts.proxyEnabled) {
            return Keep.PROXY;
        }
        if (facts.snapcastClientSessionActive) {
            return Keep.SNAPCAST_CLIENT;
        }
        if (facts.snapcastFeedActive) {
            return Keep.SNAPCAST_FEED;
        }
        return Keep.NONE;
    }
}
