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
package de.yaacc.fcast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import de.yaacc.fcast.FCastRowText.Facts;
import de.yaacc.fcast.FCastRowText.State;

/** Task 5.2: what the card of an FCast receiver says. */
public class FCastRowTextTest {

    private static final FCastRowText.Words WORDS = new FCastRowText.Words() {
        @Override
        public String type() {
            return "FCast";
        }

        @Override
        public String notReachable() {
            return "Not reachable";
        }

        @Override
        public String idle() {
            return "Idle";
        }

        @Override
        public String playing() {
            return "Playing";
        }

        @Override
        public String paused() {
            return "Paused";
        }

        @Override
        public String stateWithTitle(String state, String title) {
            return title.isEmpty() ? state : state + ": " + title;
        }
    };

    private static FCastRowText.Row row(Facts facts) {
        return FCastRowText.of(facts, WORDS);
    }

    @Test
    public void anIdleCardWithNoPlayerAndNoSessionShowsOnlyTheTypeAndTheAddress() {
        FCastRowText.Row row = row(new Facts().endpoint("192.168.0.239:46899"));

        assertEquals("FCast \u00b7 192.168.0.239:46899", row.getText());
        assertEquals(State.UNKNOWN, row.getState());
        assertEquals(-1, row.getVolume());
        assertFalse(row.isMuted());
    }

    @Test
    public void aPlayerGivesPlayingPausedOrIdleWithTheTitle() {
        assertEquals("FCast \u00b7 a:1\nPlaying: Song", row(new Facts().endpoint("a:1").player(true, false, "Song")).getText());
        assertEquals("FCast \u00b7 a:1\nPaused: Song", row(new Facts().endpoint("a:1").player(false, true, "Song")).getText());
        assertEquals("FCast \u00b7 a:1\nIdle", row(new Facts().endpoint("a:1").player(false, false, "Song")).getText());
        assertEquals(State.PLAYING, row(new Facts().player(true, false, "")).getState());
        assertEquals("FCast\nPlaying", row(new Facts().player(true, false, "")).getText());
    }

    @Test
    public void anOpenSessionGivesTheStateOnceTheReceiverHasToldIt() {
        assertEquals(State.UNKNOWN, row(new Facts().session(false, false, false, false, "")).getState());
        assertEquals(State.IDLE, row(new Facts().session(false, true, false, false, "")).getState());
        assertEquals(State.PLAYING, row(new Facts().session(false, true, true, false, "Movie")).getState());
        assertEquals(State.PAUSED, row(new Facts().session(false, true, false, true, "Movie")).getState());
    }

    @Test
    public void aFailedConnectionIsNotReachableWhateverElseIsKnown() {
        FCastRowText.Row row = row(new Facts().endpoint("a:1").session(true, true, true, false, "Movie"));

        assertEquals(State.NOT_REACHABLE, row.getState());
        assertEquals("FCast \u00b7 a:1\nNot reachable", row.getText());
    }

    @Test
    public void aPlayerBeatsASessionForTheState() {
        // the player is the receiver's one connection: a card with a player never shows "not reachable" from another source
        assertEquals(State.PLAYING, row(new Facts().player(true, false, "x")).getState());
    }

    @Test
    public void theTitleFromTheReceiverIsCleanedAndCut() {
        StringBuilder title = new StringBuilder("A\u0007\u202eB");
        while (title.length() < 500) {
            title.append('x');
        }

        String text = row(new Facts().player(true, false, title.toString())).getText();

        assertFalse(text.contains("\u0007"));
        assertFalse(text.contains("\u202e"));
        assertTrue(text.startsWith("FCast\nPlaying: AB"));
        assertTrue("cut", text.length() < 130);
    }

    @Test
    public void theVolumeIsPassedOnAndLimited() {
        assertEquals(40, row(new Facts().volume(40, false)).getVolume());
        assertEquals(100, row(new Facts().volume(400, true)).getVolume());
        assertTrue(row(new Facts().volume(400, true)).isMuted());
        assertEquals(-1, row(new Facts().volume(-7, false)).getVolume());
    }

    @Test
    public void aLongAddressIsCut() {
        StringBuilder address = new StringBuilder();
        while (address.length() < 300) {
            address.append('a');
        }

        assertTrue(row(new Facts().endpoint(address.toString())).getText().length() < 90);
    }
}
