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

/**
 * The power of a renderer card (task 12.4) as a value: whether the card has a power button at all (the capability), what state it
 * shows (on, standby, unknown) and what a tap would do. Pure; the kinds of renderers (MusicCast, OpenHome, later the phone's own
 * Snapcast client) only make these values.
 */
public class RendererPowerTest {

    @Test
    public void aDeviceThatDoesNotSupportPowerHasNoButton() {
        RendererPower power = RendererPower.unsupported();

        assertFalse(power.isSupported());
        assertEquals(RendererPower.State.UNKNOWN, power.state());
        assertEquals(RendererPower.Next.NOTHING, power.next());
    }

    @Test
    public void aDeviceThatIsOnIsSwitchedOffByATap() {
        RendererPower power = RendererPower.of(RendererPower.State.ON);

        assertTrue(power.isSupported());
        assertEquals(RendererPower.State.ON, power.state());
        assertEquals(RendererPower.Next.TURN_OFF, power.next());
    }

    @Test
    public void aDeviceInStandbyIsSwitchedOnByATap() {
        assertEquals(RendererPower.Next.TURN_ON, RendererPower.of(RendererPower.State.STANDBY).next());
    }

    @Test
    public void aDeviceWhoseStateIsNotKnownIsReadFirstAndNotGuessed() {
        RendererPower power = RendererPower.of(RendererPower.State.UNKNOWN);

        assertTrue("the button is there: the device supports it", power.isSupported());
        assertEquals(RendererPower.Next.READ_FIRST, power.next());
    }

    @Test
    public void aMissingStateIsUnknown() {
        assertEquals(RendererPower.State.UNKNOWN, RendererPower.of(null).state());
    }

    @Test
    public void valuesWithTheSameCapabilityAndStateAreEqual() {
        assertEquals(RendererPower.of(RendererPower.State.ON), RendererPower.of(RendererPower.State.ON));
        assertEquals(RendererPower.unsupported(), RendererPower.unsupported());
        assertNotEquals(RendererPower.of(RendererPower.State.ON), RendererPower.of(RendererPower.State.STANDBY));
        assertNotEquals("supported with an unknown state is not unsupported", RendererPower.unsupported(),
                RendererPower.of(RendererPower.State.UNKNOWN));
        assertEquals(RendererPower.of(RendererPower.State.ON).hashCode(), RendererPower.of(RendererPower.State.ON).hashCode());
    }
}
