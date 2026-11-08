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
package de.yaacc.upnp.server;

/**
 * What to do when a browser screen comes back to the foreground after the device or the app was
 * suspended (screen off, Doze, a long time in the background). No Android classes: unit-testable
 * on the JVM.
 * <p>
 * Two decisions: whether the network listener (multicast receiver, unicast socket) is still usable
 * or has to be re-created, and whether the devices are searched again.
 */
public final class ResumeRefreshPolicy {

    /** A search that ran less than this ago is not repeated on resume. */
    public static final long SEARCH_MIN_INTERVAL_MILLIS = 30_000L;

    /** Value of "time of the last search" when there was none. */
    public static final long NEVER = -1L;

    private ResumeRefreshPolicy() {
    }

    /** The state of the network listener found on resume. */
    public enum ListenerCheck {
        /** Sockets run on the network that is active now. */
        HEALTHY(false),
        /** No usable network (airplane mode, cellular only): nothing to re-create, the network callback does it later. */
        NO_NETWORK(false),
        /** A network is there but the listener is disabled: an onAvailable was missed. */
        NOT_INITIALIZED(true),
        /** The active network is not the one the sockets were created for. */
        NETWORK_CHANGED(true),
        /** The multicast receive loop has ended (socket closed, loop died). */
        MULTICAST_DEAD(true),
        /** The unicast receive loop has ended; search answers would not arrive. */
        UNICAST_DEAD(true);

        private final boolean reenable;

        ListenerCheck(boolean reenable) {
            this.reenable = reenable;
        }

        /** @return true if the listener has to be disabled and enabled again */
        public boolean needsReenable() {
            return reenable;
        }
    }

    /**
     * @param networkUsable      a Wi-Fi, hotspot or other non-cellular network is there
     * @param initialized        the listener is enabled (handler and both sockets exist)
     * @param networkChanged     the active network differs from the one the listener was enabled on
     * @param multicastReceiving the multicast receive loop is running
     * @param unicastReceiving   the unicast receive loop is running
     */
    public static ListenerCheck checkListener(boolean networkUsable, boolean initialized, boolean networkChanged,
                                              boolean multicastReceiving, boolean unicastReceiving) {
        if (!networkUsable) {
            return ListenerCheck.NO_NETWORK;
        }
        if (!initialized) {
            return ListenerCheck.NOT_INITIALIZED;
        }
        if (networkChanged) {
            return ListenerCheck.NETWORK_CHANGED;
        }
        if (!multicastReceiving) {
            return ListenerCheck.MULTICAST_DEAD;
        }
        if (!unicastReceiving) {
            return ListenerCheck.UNICAST_DEAD;
        }
        return ListenerCheck.HEALTHY;
    }

    /**
     * @param listenerReenabled  the listener was re-created just now: its new sockets know no device
     * @param lastSearchAtMillis monotonic time of the last search, {@link #NEVER} if none
     * @param nowMillis          monotonic time now (same clock)
     * @return true if the devices are searched again now
     */
    public static boolean shouldSearch(boolean listenerReenabled, long lastSearchAtMillis, long nowMillis) {
        if (listenerReenabled || lastSearchAtMillis < 0) {
            return true;
        }
        long age = nowMillis - lastSearchAtMillis;
        // a negative age means the clock is not the one of the stamp: search to be safe
        return age < 0 || age >= SEARCH_MIN_INTERVAL_MILLIS;
    }
}
