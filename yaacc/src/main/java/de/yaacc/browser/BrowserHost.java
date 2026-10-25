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

import java.util.Collection;

import de.yaacc.share.PendingPick;

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

    /**
     * Opens the receiver picker ("Play on...", the device sheet in pick mode) and reports the answer
     * through {@code callbacks}: exactly one of {@link PendingPick.Callbacks#onConfirmed} (with the
     * chosen receiver ids) or {@link PendingPick.Callbacks#onCancelled} runs, on the main thread, and
     * only if this returns true. Call it on the main thread.
     * <p>
     * The new layout uses it for play, play-all, add-to-playlist and a pending share; classic uses it
     * only for a pending shared file (normal classic browsing never shows the sheet). Nothing is
     * stored by the picker: the host's callbacks decide what a confirm sets and clears.
     *
     * @param callbacks  what to do with the answer
     * @param shareName  the cleaned name of the pending share, or null if the pick is not for a share
     *                   (an empty name is replaced by a fixed text on the sheet)
     * @param initialIds receivers that start ticked, may be null (classic: its tab-based selection)
     * @return false (callbacks are never called) if the host has no picker, a pick is already open,
     * or the screen cannot show it now; a new request never replaces the open one
     */
    default boolean pickReceivers(PendingPick.Callbacks callbacks, String shareName,
                                  Collection<String> initialIds) {
        return false;
    }

    /** Whether the receiver picker with this id is open (a restored sheet asks, then closes itself if not). */
    default boolean isPickOpen(String pickId) {
        return false;
    }

    /**
     * A shared file is waiting for the user to pick the receivers (see {@code ShareIntentHandler}).
     * Called on the main thread. The host shows its receiver picker with {@code displayName} (already
     * cleaned, safe for {@code setText}) and later calls {@code ShareIntentHandler#confirm} or
     * {@code ShareIntentHandler#cancel} with the {@code token}. The default does nothing: the pending
     * share then waits for its timeout (two minutes) and is dropped.
     *
     * @param token       binds the pending share to its pick; a result with another token changes nothing
     * @param displayName the cleaned name of the file
     */
    default void onPendingShare(String token, String displayName) {
    }
}
