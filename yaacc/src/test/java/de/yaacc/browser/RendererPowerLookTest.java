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
package de.yaacc.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import de.yaacc.R;

/** How the power button of a card looks and what it says (task 12.4): shown only when supported, state text, busy, error texts. Pure. */
public class RendererPowerLookTest {

    @Test
    public void aCardWithoutPowerHasNoButton() {
        RendererPowerLook look = RendererPowerLook.of(RendererPower.unsupported(), false);

        assertFalse(look.visible());
        assertFalse(look.enabled());
    }

    @Test
    public void theStateIsToldInWordsForEveryState() {
        assertEquals(R.string.power_state_on, RendererPowerLook.of(RendererPower.of(RendererPower.State.ON), false).stateText());
        assertEquals(R.string.power_state_standby, RendererPowerLook.of(RendererPower.of(RendererPower.State.STANDBY), false).stateText());
        assertEquals(R.string.power_state_unknown, RendererPowerLook.of(RendererPower.of(RendererPower.State.UNKNOWN), false).stateText());
    }

    @Test
    public void aButtonOfADeviceThatIsOnIsFullAndOtherStatesAreDimmedLikeTheOtherToggleButtons() {
        RendererPowerLook on = RendererPowerLook.of(RendererPower.of(RendererPower.State.ON), false);
        RendererPowerLook standby = RendererPowerLook.of(RendererPower.of(RendererPower.State.STANDBY), false);
        RendererPowerLook unknown = RendererPowerLook.of(RendererPower.of(RendererPower.State.UNKNOWN), false);

        assertTrue(on.visible() && on.enabled());
        assertEquals(1f, on.alpha(), 0f);
        assertEquals("the repeat and shuffle buttons use 1 and 0.5", 0.5f, standby.alpha(), 0f);
        assertTrue("a device in standby can be switched on", standby.enabled());
        assertTrue("an unknown state is read first on a tap", unknown.enabled());
        assertEquals(0.5f, unknown.alpha(), 0f);
    }

    @Test
    public void whileAChangeRunsTheButtonIsDisabledAndDimmed() {
        RendererPowerLook busy = RendererPowerLook.of(RendererPower.of(RendererPower.State.ON), true);

        assertTrue(busy.visible());
        assertFalse(busy.enabled());
        assertEquals("the other disabled buttons use 0.4", 0.4f, busy.alpha(), 0f);
    }

    @Test
    public void everyErrorHasATextOfItsOwnAndNoErrorHasNone() {
        Set<Integer> seen = new HashSet<>();
        for (RendererPowerToggle.Error error : RendererPowerToggle.Error.values()) {
            int text = RendererPowerLook.errorText(error);
            if (error == RendererPowerToggle.Error.NONE) {
                assertEquals(0, text);
            } else {
                assertNotEquals(error.name(), 0, text);
                assertTrue(seen.add(text));
            }
        }
        assertEquals(R.string.power_error_unreachable, RendererPowerLook.errorText(RendererPowerToggle.Error.UNREACHABLE));
        assertEquals(R.string.power_error_unknown_state, RendererPowerLook.errorText(RendererPowerToggle.Error.UNKNOWN_STATE));
        assertEquals(R.string.power_error_refused, RendererPowerLook.errorText(RendererPowerToggle.Error.REFUSED));
    }
}
