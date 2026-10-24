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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import de.yaacc.browser.BrowserLayoutChoice.Layout;

/**
 * Unit tests for {@link BrowserLayoutSwitch}, the resume-time decision of both browser
 * activities. Plain JVM.
 */
public class BrowserLayoutSwitchTest {

    @Test
    public void sameLayoutDoesNotRelaunch() {
        assertFalse(BrowserLayoutSwitch.shouldRelaunch(Layout.CLASSIC, Layout.CLASSIC));
        assertFalse(BrowserLayoutSwitch.shouldRelaunch(Layout.NEW, Layout.NEW));
    }

    @Test
    public void differentLayoutRelaunches() {
        assertTrue(BrowserLayoutSwitch.shouldRelaunch(Layout.CLASSIC, Layout.NEW));
        assertTrue(BrowserLayoutSwitch.shouldRelaunch(Layout.NEW, Layout.CLASSIC));
    }

    @Test
    public void nullEffectiveLayoutNeverRelaunches() {
        assertFalse(BrowserLayoutSwitch.shouldRelaunch(Layout.CLASSIC, null));
        assertFalse(BrowserLayoutSwitch.shouldRelaunch(Layout.NEW, null));
    }
}
