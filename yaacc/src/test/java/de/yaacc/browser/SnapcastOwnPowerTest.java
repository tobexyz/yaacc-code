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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import de.yaacc.multiroom.client.SnapcastClientPower;

/**
 * Task 12.4 part 2 and 12.16: the power of the phone's own Snapcast client plugged into the one power model of the cards
 * ({@link RendererPower}, {@link RendererPowerToggle}). On while the session runs or starts, off otherwise; a tap on a card that is
 * on switches the client off (the session ends, which is the disconnect), on a card that is off it switches it on. Plain JVM.
 */
public class SnapcastOwnPowerTest {

    private static final class FakeSwitch implements SnapcastOwnPower.Switch {
        boolean on;
        SnapcastClientPower.Outcome answer = null;
        final List<Boolean> calls = new ArrayList<>();

        @Override
        public boolean isPoweredOn() {
            return on;
        }

        @Override
        public SnapcastClientPower.Outcome setOn(boolean value) {
            calls.add(value);
            if (answer != null) {
                return answer;
            }
            on = value;
            return value ? SnapcastClientPower.Outcome.STARTED : SnapcastClientPower.Outcome.STOPPED;
        }
    }

    @Test
    public void theCardOfAnotherClientHasNoPowerAtAll() {
        assertFalse(SnapcastOwnPower.of(false, true).isSupported());
        assertFalse(SnapcastOwnPower.of(false, false).isSupported());
        assertEquals(RendererPower.Next.NOTHING, SnapcastOwnPower.of(false, true).next());
    }

    @Test
    public void theOwnCardIsOnOrStandbyNeverUnknown() {
        RendererPower on = SnapcastOwnPower.of(true, true);
        RendererPower off = SnapcastOwnPower.of(true, false);

        assertTrue(on.isSupported() && off.isSupported());
        assertEquals(RendererPower.State.ON, on.state());
        assertEquals(RendererPower.State.STANDBY, off.state());
        assertEquals("a tap on an on card switches off", RendererPower.Next.TURN_OFF, on.next());
        assertEquals("a tap on an off card switches on, also for an offline entry", RendererPower.Next.TURN_ON, off.next());
    }

    @Test
    public void theSourceReadsTheSwitchAndNothingElse() throws Exception {
        FakeSwitch own = new FakeSwitch();
        RendererPowerToggle.Source source = SnapcastOwnPower.source(own, new AtomicReference<SnapcastClientPower.Outcome>());

        assertEquals(RendererPower.State.STANDBY, source.read());
        own.on = true;
        assertEquals(RendererPower.State.ON, source.read());
        assertTrue("reading changes nothing", own.calls.isEmpty());
    }

    @Test
    public void aTapOnAnOffCardSwitchesTheClientOn() {
        FakeSwitch own = new FakeSwitch();
        AtomicReference<SnapcastClientPower.Outcome> refusal = new AtomicReference<>();

        RendererPowerToggle.Outcome outcome = RendererPowerToggle.toggle(SnapcastOwnPower.source(own, refusal), millis -> {
        }, 0);

        assertEquals(java.util.Collections.singletonList(Boolean.TRUE), own.calls);
        assertEquals(RendererPower.State.ON, outcome.state());
        assertEquals(RendererPowerToggle.Error.NONE, outcome.error());
        assertNull(refusal.get());
    }

    @Test
    public void aTapOnAnOnCardSwitchesTheClientOffWhichIsTheDisconnect() {
        FakeSwitch own = new FakeSwitch();
        own.on = true;

        RendererPowerToggle.Outcome outcome = RendererPowerToggle.toggle(SnapcastOwnPower.source(own,
                new AtomicReference<SnapcastClientPower.Outcome>()), millis -> {
        }, 0);

        assertEquals(java.util.Collections.singletonList(Boolean.FALSE), own.calls);
        assertEquals(RendererPower.State.STANDBY, outcome.state());
        assertEquals(RendererPowerToggle.Error.NONE, outcome.error());
    }

    @Test
    public void aSessionThatRunsAlreadyIsNotAnError() {
        FakeSwitch own = new FakeSwitch();
        own.answer = SnapcastClientPower.Outcome.ALREADY_ACTIVE;

        RendererPowerToggle.Outcome outcome = RendererPowerToggle.toggle(SnapcastOwnPower.source(own,
                new AtomicReference<SnapcastClientPower.Outcome>()), millis -> {
        }, 0);

        assertEquals(RendererPowerToggle.Error.NONE, outcome.error());
    }

    @Test
    public void everyRefusalOfTheStartIsAnErrorAndKeepsItsReasonForTheToast() {
        for (SnapcastClientPower.Outcome refused : new SnapcastClientPower.Outcome[]{SnapcastClientPower.Outcome.OPTION_OFF,
                SnapcastClientPower.Outcome.NO_SERVER, SnapcastClientPower.Outcome.BAD_SERVER, SnapcastClientPower.Outcome.NOT_ALLOWED}) {
            FakeSwitch own = new FakeSwitch();
            own.answer = refused;
            AtomicReference<SnapcastClientPower.Outcome> refusal = new AtomicReference<>();

            RendererPowerToggle.Outcome outcome = RendererPowerToggle.toggle(SnapcastOwnPower.source(own, refusal), millis -> {
            }, 0);

            assertEquals(refused.name(), RendererPowerToggle.Error.REFUSED, outcome.error());
            assertEquals(refused, refusal.get());
        }
    }

    @Test
    public void aTapThatWorksClearsAnEarlierReason() {
        FakeSwitch own = new FakeSwitch();
        AtomicReference<SnapcastClientPower.Outcome> refusal = new AtomicReference<>(SnapcastClientPower.Outcome.NO_SERVER);

        RendererPowerToggle.toggle(SnapcastOwnPower.source(own, refusal), millis -> {
        }, 0);

        assertNull(refusal.get());
    }
}
