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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The rule "may the app close itself now" of the background idle timer: nothing that serves or plays may be cut off. A
 * Snapcast client session and a Snapcast feed count like a UPnP service (issue 319, task 11.5).
 */
public class AutoCloseRuleTest {

    @Test
    public void nothingActiveMayClose() {
        assertTrue(AutoCloseRule.mayCloseNow(new AutoCloseRule.Facts()));
        assertEquals(AutoCloseRule.Keep.NONE, AutoCloseRule.reasonToStay(new AutoCloseRule.Facts()));
    }

    @Test
    public void aPlayerKeepsTheApp() {
        assertFalse(AutoCloseRule.mayCloseNow(new AutoCloseRule.Facts().playersActive(true)));
        assertEquals(AutoCloseRule.Keep.PLAYER, AutoCloseRule.reasonToStay(new AutoCloseRule.Facts().playersActive(true)));
    }

    @Test
    public void everyUpnpServiceKeepsTheApp() {
        assertFalse(AutoCloseRule.mayCloseNow(new AutoCloseRule.Facts().serverEnabled(true)));
        assertFalse(AutoCloseRule.mayCloseNow(new AutoCloseRule.Facts().rendererEnabled(true)));
        assertFalse(AutoCloseRule.mayCloseNow(new AutoCloseRule.Facts().proxyEnabled(true)));
    }

    @Test
    public void aSnapcastClientSessionKeepsTheApp() {
        AutoCloseRule.Facts facts = new AutoCloseRule.Facts().snapcastClientSessionActive(true);
        assertFalse(AutoCloseRule.mayCloseNow(facts));
        assertEquals(AutoCloseRule.Keep.SNAPCAST_CLIENT, AutoCloseRule.reasonToStay(facts));
    }

    @Test
    public void aSnapcastFeedKeepsTheAppEvenIfNoPlayerIsCounted() {
        AutoCloseRule.Facts facts = new AutoCloseRule.Facts().snapcastFeedActive(true);
        assertFalse(AutoCloseRule.mayCloseNow(facts));
        assertEquals(AutoCloseRule.Keep.SNAPCAST_FEED, AutoCloseRule.reasonToStay(facts));
    }

    @Test
    public void everySingleFactBlocksOnItsOwn() {
        for (AutoCloseRule.Keep keep : AutoCloseRule.Keep.values()) {
            if (keep == AutoCloseRule.Keep.NONE) {
                continue;
            }
            AutoCloseRule.Facts facts = new AutoCloseRule.Facts();
            switch (keep) {
                case PLAYER: facts.playersActive(true); break;
                case LOCAL_SERVER: facts.serverEnabled(true); break;
                case RENDERER: facts.rendererEnabled(true); break;
                case PROXY: facts.proxyEnabled(true); break;
                case SNAPCAST_CLIENT: facts.snapcastClientSessionActive(true); break;
                case SNAPCAST_FEED: facts.snapcastFeedActive(true); break;
                default: throw new AssertionError(keep);
            }
            assertFalse(keep.name(), AutoCloseRule.mayCloseNow(facts));
            assertEquals(keep, AutoCloseRule.reasonToStay(facts));
        }
    }

    @Test
    public void aFinishedSnapcastSessionDoesNotKeepTheApp() {
        // the facts are read again when the timer fires: a session that ended meanwhile is false by then
        assertTrue(AutoCloseRule.mayCloseNow(new AutoCloseRule.Facts().snapcastClientSessionActive(false).snapcastFeedActive(false)));
    }

    @Test
    public void nullFactsAreRefusedAsStay() {
        // unknown state must never close the app
        assertFalse(AutoCloseRule.mayCloseNow(null));
    }
}
