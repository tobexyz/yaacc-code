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

import de.yaacc.browser.BrowserLayoutChoice.Layout;

/**
 * The resume-time decision shared by both browser activities: relaunch into the other layout when
 * the effective layout differs from the one this activity renders. The caller combines it with
 * {@link RelaunchGuard#tryRelaunch()} so a wrong decision cannot loop. Plain Java.
 */
public final class BrowserLayoutSwitch {

    private BrowserLayoutSwitch() {
    }

    /**
     * @param own       the layout the calling activity renders
     * @param effective the layout {@code BrowserLauncher#effectiveLayout} reports now
     * @return true if the calling activity should start {@code BrowserLauncher#targetClass} and finish
     */
    public static boolean shouldRelaunch(Layout own, Layout effective) {
        return effective != null && effective != own;
    }
}
