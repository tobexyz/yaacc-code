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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import de.yaacc.browser.BrowserBackNavigation.Action;

/**
 * Unit tests for {@link BrowserBackNavigation}, the back-button decision of the new layout.
 * Plain JVM.
 */
public class BrowserBackNavigationTest {

    @Test
    public void inAFolderBelowTheRootPopsOneFolder() {
        assertEquals(Action.POP_FOLDER, BrowserBackNavigation.decide(false, false));
    }

    @Test
    public void atTheContentRootGoesBackToTheServerList() {
        assertEquals(Action.SHOW_SERVERS, BrowserBackNavigation.decide(false, true));
    }

    @Test
    public void onTheServerListTheAppGoesToTheBackgroundAndIsNotClosed() {
        assertEquals(Action.TO_BACKGROUND, BrowserBackNavigation.decide(true, false));
    }

    @Test
    public void onTheServerListTheContentStateIsIgnored() {
        assertEquals(Action.TO_BACKGROUND, BrowserBackNavigation.decide(true, true));
    }

    @Test
    public void thereIsNoActionThatQuitsTheApp() {
        // task 12.1: the last back is the normal "put the app in the background"; closing is the idle timer's and the exit button's job
        for (Action action : Action.values()) {
            assertNotEquals("EXIT", action.name());
        }
    }
}
