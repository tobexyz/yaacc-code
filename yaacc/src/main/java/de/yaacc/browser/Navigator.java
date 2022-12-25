/*
* Copyright (C) 2013 www.yaacc.de
*
* This program is free software; you can redistribute it and/or
* modify it under the terms of the GNU General Public License
* as published by the Free Software Foundation; either version 3
* of the License, or (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program; if not, write to the Free Software
* Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
*/
package de.yaacc.browser;

import android.util.Log;

import java.io.Serializable;
import java.util.LinkedList;

/**
 * Manages navigation path inside device and folder hierarchy.
 *
 * @author Christoph Hähnel (eyeless)
 */
public class Navigator implements Serializable {

    public final static String ITEM_ROOT_OBJECT_ID = "0";

    public Navigator() {
        navigationPath = new LinkedList<Position>();
    }

    private LinkedList<Position> navigationPath;

    /**
     * Provides information about the current position.
     *
     * @return current position or null
     */
    public Position getCurrentPosition() {
        if (navigationPath.isEmpty()) {
            return null;
        }
        return navigationPath.peekLast();
    }

    public void pushPosition(Position pos) {
        Log.d(getClass().getName(), "pushNavigation: " + pos.getObjectId());
        navigationPath.add(pos);
    }

    /**
     * Provides information about the current position and removes it from the navigation path.
     *
     * @return current position or null
     */
    public Position popPosition() {
        Position result = null;
        if (!navigationPath.isEmpty()) {
            result = navigationPath.removeLast();
        }
        Log.d(getClass().getName(), "popNavigation: " + result.getObjectId());
        return result;
    }
} 