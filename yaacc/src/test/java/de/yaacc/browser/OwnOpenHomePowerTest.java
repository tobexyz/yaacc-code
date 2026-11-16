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

import java.util.ArrayList;
import java.util.List;

/**
 * Task 12.13: the power button on the card of the app's own OpenHome renderer, through the one power model of the cards. It shows
 * the state of the {@code Standby} variable of the renderer's Product service, the same one the power switch of the OpenHome settings
 * sheet writes (switch on = not in standby), and a tap writes it the way that switch does. Plain JVM.
 */
public class OwnOpenHomePowerTest {

    private static final class FakeOwn implements OwnOpenHomePower.Switch {
        Boolean standby;
        boolean accepts = true;
        final List<Boolean> calls = new ArrayList<>();

        FakeOwn(Boolean standby) {
            this.standby = standby;
        }

        @Override
        public Boolean standby() {
            return standby;
        }

        @Override
        public boolean setStandby(boolean value) {
            calls.add(value);
            if (accepts) {
                standby = value;
            }
            return accepts;
        }
    }

    // ---- where the button is

    @Test
    public void onlyTheLocalDeviceThatOffersOpenHomeWhileTheRendererRunsHasTheButton() {
        for (int bits = 0; bits < 8; bits++) {
            boolean local = (bits & 1) != 0;
            boolean openHome = (bits & 2) != 0;
            boolean running = (bits & 4) != 0;
            assertEquals("local=" + local + " openHome=" + openHome + " running=" + running, local && openHome && running,
                    OwnOpenHomePower.offered(local, openHome, running));
        }
    }

    // ---- what it shows

    @Test
    public void theCardShowsWhatTheSettingsSwitchShows() {
        // the settings switch is checked when the device is NOT in standby (OpenHomeSettingsSheet: powerSwitch(!standby))
        assertEquals(RendererPower.State.ON, OwnOpenHomePower.of(true, false).state());
        assertEquals(RendererPower.State.STANDBY, OwnOpenHomePower.of(true, true).state());
        assertEquals(RendererPower.State.UNKNOWN, OwnOpenHomePower.of(true, null).state());
        for (Boolean standby : new Boolean[]{true, false, null}) {
            assertEquals("the same model as a remote OpenHome card: " + standby, OpenHomePower.of(true, standby),
                    OwnOpenHomePower.of(true, standby));
        }
    }

    @Test
    public void aCardThatIsNotOfferedHasNoPowerWhateverTheState() {
        for (Boolean standby : new Boolean[]{true, false, null}) {
            assertFalse(OwnOpenHomePower.of(false, standby).isSupported());
            assertEquals(RendererPower.Next.NOTHING, OwnOpenHomePower.of(false, standby).next());
        }
    }

    @Test
    public void aTapOnAnOnCardSwitchesOffAndOnAStandbyCardOn() {
        assertEquals(RendererPower.Next.TURN_OFF, OwnOpenHomePower.of(true, false).next());
        assertEquals(RendererPower.Next.TURN_ON, OwnOpenHomePower.of(true, true).next());
        assertEquals(RendererPower.Next.READ_FIRST, OwnOpenHomePower.of(true, null).next());
    }

    // ---- what a tap does

    @Test
    public void theToggleGoesToStandbyAndBackAndEndsInTheStateTheRendererReports() {
        FakeOwn own = new FakeOwn(false);

        RendererPowerToggle.Outcome off = RendererPowerToggle.toggle(OwnOpenHomePower.source(own), millis -> { }, 0);
        assertEquals(RendererPower.State.STANDBY, off.state());
        assertEquals(RendererPowerToggle.Error.NONE, off.error());
        assertEquals(Boolean.TRUE, own.standby);

        RendererPowerToggle.Outcome on = RendererPowerToggle.toggle(OwnOpenHomePower.source(own), millis -> { }, 0);
        assertEquals(RendererPower.State.ON, on.state());
        assertEquals(RendererPowerToggle.Error.NONE, on.error());
        assertEquals(Boolean.FALSE, own.standby);
        assertEquals("one write per tap, standby true to go to standby", java.util.Arrays.asList(true, false), own.calls);
    }

    @Test
    public void theChangeIsDecidedFromAFreshReadNotFromTheCard() {
        FakeOwn own = new FakeOwn(true); // the card still showed "on", but the settings switch went to standby meanwhile

        RendererPowerToggle.Outcome outcome = RendererPowerToggle.toggle(OwnOpenHomePower.source(own), millis -> { }, 0);

        assertEquals(RendererPower.State.ON, outcome.state());
        assertEquals(java.util.Collections.singletonList(false), own.calls);
    }

    @Test
    public void aRendererThatIsGoneIsUnreachableAndNothingIsWritten() {
        FakeOwn own = new FakeOwn(null);

        RendererPowerToggle.Outcome outcome = RendererPowerToggle.toggle(OwnOpenHomePower.source(own), millis -> { }, 0);

        assertEquals(RendererPowerToggle.Error.UNREACHABLE, outcome.error());
        assertTrue(own.calls.isEmpty());
    }

    @Test
    public void aWriteTheRendererDoesNotTakeIsRefused() {
        FakeOwn own = new FakeOwn(false);
        own.accepts = false;

        RendererPowerToggle.Outcome outcome = RendererPowerToggle.toggle(OwnOpenHomePower.source(own), millis -> { }, 0);

        assertEquals(RendererPowerToggle.Error.REFUSED, outcome.error());
        assertEquals(RendererPower.State.ON, outcome.state());
    }
}
