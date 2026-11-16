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
package de.yaacc.multiroom.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import de.yaacc.R;
import de.yaacc.settings.SnapcastSettingsTarget;

/**
 * The words of the switch, the dialog and the checkbox for the state of the phone's own Snapcast client, and the one place that
 * says which part of the settings the gear opens.
 */
public class SnapcastClientPowerTextsTest {

    private static SnapcastClientPower.State state(SnapcastClientPower.Shown shown, String ending, boolean unseen) {
        return new SnapcastClientPower.State(shown, ending, unseen);
    }

    @Test
    public void everyShownStateHasItsText() {
        assertEquals(R.string.snapcast_client_state_off, SnapcastClientPowerTexts.stateTextRes(state(SnapcastClientPower.Shown.OFF, null, false)));
        assertEquals(R.string.snapcast_client_notification_connecting,
                SnapcastClientPowerTexts.stateTextRes(state(SnapcastClientPower.Shown.CONNECTING, null, false)));
        assertEquals(R.string.snapcast_client_notification_playing,
                SnapcastClientPowerTexts.stateTextRes(state(SnapcastClientPower.Shown.PLAYING, null, false)));
        assertEquals(R.string.snapcast_client_notification_reconnecting,
                SnapcastClientPowerTexts.stateTextRes(state(SnapcastClientPower.Shown.RECONNECTING, null, false)));
    }

    @Test
    public void aStoppedStateNamesTheLastEndingWhenThereIsOne() {
        SnapcastClientPower.State noisy = state(SnapcastClientPower.Shown.STOPPED, "NO_AUDIO", true);
        assertEquals(R.string.snapcast_client_notification_stopped, SnapcastClientPowerTexts.stateTextRes(noisy));
        assertEquals(SnapcastClientStatusText.endingText(SnapcastClientRuntime.Ending.NO_AUDIO), SnapcastClientPowerTexts.reasonOf(noisy));

        for (String none : new String[]{null, "", "NONE", "unknown ending"}) {
            SnapcastClientPower.State quiet = state(SnapcastClientPower.Shown.STOPPED, none, false);
            assertEquals(String.valueOf(none), R.string.snapcast_client_state_stopped, SnapcastClientPowerTexts.stateTextRes(quiet));
            assertEquals("", SnapcastClientPowerTexts.reasonOf(quiet));
        }
    }

    @Test
    public void everyRefusalHasItsOwnText() {
        Set<Integer> seen = new HashSet<>();
        for (SnapcastClientPower.Outcome outcome : SnapcastClientPower.Outcome.values()) {
            int res = SnapcastClientPowerTexts.refusalTextRes(outcome);
            if (outcome.isRefused()) {
                assertNotEquals(outcome.name(), 0, res);
                assertTrue("a text of its own for " + outcome, seen.add(res));
            } else {
                assertEquals(outcome.name() + " is no refusal", 0, res);
            }
        }
        assertEquals(R.string.snapcast_client_refused_option_off, SnapcastClientPowerTexts.refusalTextRes(SnapcastClientPower.Outcome.OPTION_OFF));
        assertEquals(R.string.snapcast_client_refused_no_server, SnapcastClientPowerTexts.refusalTextRes(SnapcastClientPower.Outcome.NO_SERVER));
        assertEquals(R.string.snapcast_client_refused_bad_server, SnapcastClientPowerTexts.refusalTextRes(SnapcastClientPower.Outcome.BAD_SERVER));
        assertEquals(R.string.snapcast_client_refused_not_allowed, SnapcastClientPowerTexts.refusalTextRes(SnapcastClientPower.Outcome.NOT_ALLOWED));
    }

    @Test
    public void onlyTheRefusalsAreRefusals() {
        assertFalse(SnapcastClientPower.Outcome.STARTED.isRefused());
        assertFalse(SnapcastClientPower.Outcome.ALREADY_ACTIVE.isRefused());
        assertFalse(SnapcastClientPower.Outcome.STOPPED.isRefused());
        assertTrue(SnapcastClientPower.Outcome.NOT_ALLOWED.isRefused());
    }

    @Test
    public void aStartTheSystemDidNotAllowHasWordsAndTheOldEndingsKeepTheirPlaceInTheTrace() {
        assertFalse(SnapcastClientStatusText.endingText(SnapcastClientRuntime.Ending.START_NOT_ALLOWED).isEmpty());
        // the trace stores the ordinal: the new ending comes after the old ones
        SnapcastClientRuntime.Ending[] all = SnapcastClientRuntime.Ending.values();
        assertEquals(SnapcastClientRuntime.Ending.START_NOT_ALLOWED, all[all.length - 1]);
        assertEquals(11, SnapcastClientRuntime.Ending.APP_EXIT.ordinal());
    }

    // ---- the gear: which part of the settings it opens ------------------------------------------------------------------

    @Test
    public void onlyTheSnapcastSectionIsAValidTargetOfTheSettingsScroll() {
        assertEquals(SnapcastClientSpike.KEY_SWITCHED_ON, SnapcastSettingsTarget.scrollKey(SnapcastClientSpike.KEY_SWITCHED_ON));
        assertEquals(SnapcastClientSpike.KEY_SWITCHED_ON, SnapcastSettingsTarget.SNAPCAST_CLIENT);
        for (String other : new String[]{null, "", " ", "settings_theme_key", "../x", SnapcastClientSpike.KEY_SWITCHED_ON + "x",
                new String(new char[5000]).replace('\0', 'a')}) {
            assertEquals(String.valueOf(other), null, SnapcastSettingsTarget.scrollKey(other));
        }
    }
}
