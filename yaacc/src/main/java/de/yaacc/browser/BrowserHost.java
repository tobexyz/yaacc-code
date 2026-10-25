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
 * Navigation that fragments and helpers may ask of the activity that hosts them. Implemented by
 * the browser activities; callers must tolerate "no host" (the context or activity is not a
 * {@code BrowserHost}) and may call from any thread, so implementations hop to the main thread
 * themselves.
 */
public interface BrowserHost {

    /** Shows the list of servers. */
    void showServers();

    /** Shows the content of the currently selected server. */
    void showContent();

    /** Shows the receivers. */
    void showReceivers();

    /** Shows the running players. */
    void showPlayers();

    /**
     * Whether the host shows the shutdown timer itself (in its toolbar), so the server list leaves
     * its own timer rows out and uses the top-level layout. False for the classic tabs.
     */
    default boolean providesShutdownTimer() {
        return false;
    }

    /**
     * Whether the host shows the receiver bar itself, at every level (new layout). A content
     * list then hides its own footer and opens the device sheet through {@link #showReceivers()}.
     * False for the classic tabs, where the content list shows the original static footer
     * (current provider, selected receivers) and there is no sheet.
     */
    default boolean providesReceiverBar() {
        return false;
    }

    /**
     * Whether the content list shows the back arrow at the content root too, and its tap calls
     * {@link #showServers()} there (new layout: the root of a server is one step below the server
     * list). False for the classic tabs, where the root has nothing to go back to.
     */
    default boolean showsBackAtContentRoot() {
        return false;
    }
}
