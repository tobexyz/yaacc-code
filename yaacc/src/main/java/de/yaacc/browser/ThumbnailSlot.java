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
 * What one recycled row of the content list currently shows or loads as its thumbnail, so a bind does not start
 * a load for something that is already there. No Android classes.
 * <p>
 * The key identifies a request: the uri and the requested size. A bind with the key the slot already loads or
 * shows keeps everything as it is (no new task, no reset of the type icon); a different key replaces it.
 */
final class ThumbnailSlot {

    enum Decision {
        /** The row has no thumbnail: show the type icon. */
        NONE,
        /** The same request is already loading or shown: touch nothing. */
        KEEP,
        /** The decoded thumbnail is in the memory cache: show it at once. */
        SHOW_CACHED,
        /** Show the type icon and load the thumbnail. */
        LOAD
    }

    private enum State {IDLE, LOADING, SHOWN}

    private String key;
    private State state = State.IDLE;

    /** The request key of a thumbnail, the same for the same uri and size. */
    static String key(String uri, int widthPx, int heightPx) {
        return uri + "@" + widthPx + "x" + heightPx;
    }

    /**
     * @param newKey the request of the row being bound, null if it has no thumbnail
     * @param cached the decoded thumbnail for that key is in memory
     */
    Decision onBind(String newKey, boolean cached) {
        if (newKey == null) {
            key = null;
            state = State.IDLE;
            return Decision.NONE;
        }
        if (newKey.equals(key) && state != State.IDLE) {
            return Decision.KEEP;
        }
        key = newKey;
        state = cached ? State.SHOWN : State.LOADING;
        return cached ? Decision.SHOW_CACHED : Decision.LOAD;
    }

    /**
     * A load ended. An answer for a key that is no longer the slot's is ignored. A failed or cancelled load
     * leaves the slot idle, so the next bind of that key loads again.
     */
    void onFinished(String finishedKey, boolean shown) {
        if (finishedKey != null && finishedKey.equals(key) && state == State.LOADING) {
            state = shown ? State.SHOWN : State.IDLE;
        }
    }

    /** The row was recycled. */
    void release() {
        key = null;
        state = State.IDLE;
    }

    String key() {
        return key;
    }

    boolean isLoading() {
        return state == State.LOADING;
    }
}
