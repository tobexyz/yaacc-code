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
 * What the back button does in the new layout. Plain Java so the rule is testable without an
 * activity.
 */
public final class BrowserBackNavigation {

    /** The step to take. */
    public enum Action {
        /** Go up one folder inside the shown server. */
        POP_FOLDER,
        /** Leave the server and show the server list. */
        SHOW_SERVERS,
        /** Quit the app, as back on the classic Server tab does. */
        EXIT
    }

    private BrowserBackNavigation() {
    }

    /**
     * @param onServerList   the server list is the screen on top
     * @param contentAtRoot  a server's content is on top and shows its root folder; ignored when
     *                       {@code onServerList} is true
     */
    public static Action decide(boolean onServerList, boolean contentAtRoot) {
        if (onServerList) {
            return Action.EXIT;
        }
        return contentAtRoot ? Action.SHOW_SERVERS : Action.POP_FOLDER;
    }
}
