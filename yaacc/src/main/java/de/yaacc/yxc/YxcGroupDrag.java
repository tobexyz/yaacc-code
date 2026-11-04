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
package de.yaacc.yxc;

import java.util.Map;

/**
 * The state of one drag on the group volume slider. It lives outside the row's listener: the row is rebound while the
 * finger is down (a status poll, a change of the group), and a new listener must go on with the drag it did not see begin.
 * <p>
 * The members' levels at the start are read from the devices after the touch began; the movement until they are there
 * is only remembered and then applied once. A drag that ended or was replaced by a newer one does not apply late reads.
 * Thread-safe: the touch comes on the main thread, the levels on a worker.
 */
public final class YxcGroupDrag {
    private int id;
    private boolean active;
    private int startProgress;
    private int latestProgress;
    private Map<String, Integer> levels;

    /** A touch began at this position; returns the id of the drag (for {@link #levelsArrived}). */
    public synchronized int begin(int progress) {
        id++;
        active = true;
        startProgress = progress;
        latestProgress = progress;
        levels = null;
        return id;
    }

    /**
     * The slider moved. @return the level to send to each member, or {@code null} while the start levels are not there
     * yet or no drag is going on
     */
    public synchronized Map<String, Integer> moved(int progress) {
        if (!active) {
            return null;
        }
        latestProgress = progress;
        return targets();
    }

    /**
     * The start levels of drag {@code dragId} were read. @return the levels to send now (for the latest position), or
     * {@code null} if that drag is no longer the current one
     */
    public synchronized Map<String, Integer> levelsArrived(int dragId, Map<String, Integer> read) {
        if (dragId != id) {
            return null;
        }
        levels = read;
        return targets();
    }

    /** The finger was lifted; later reads of this drag are dropped. */
    public synchronized void end() {
        active = false;
        id++;
        levels = null;
    }

    public synchronized boolean isActive() {
        return active;
    }

    private Map<String, Integer> targets() {
        return levels == null ? null : YxcGroupVolume.apply(levels, latestProgress - startProgress);
    }
}
