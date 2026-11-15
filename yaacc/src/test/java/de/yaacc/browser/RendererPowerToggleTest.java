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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.yaacc.browser.RendererPower.State;
import de.yaacc.browser.RendererPowerToggle.Error;
import de.yaacc.browser.RendererPowerToggle.Outcome;
import de.yaacc.browser.RendererPowerToggle.PowerException;

/**
 * The tap on a power button (task 12.4): the state is read fresh first, the action follows from what the device says now and not
 * from what the card showed, the change is sent once, the device is read again, and every way it can fail is a typed error. A fake
 * device and a fake pause; plain JVM.
 */
public class RendererPowerToggleTest {

    /** A device that answers the reads in turn and records every call. */
    private static final class FakeDevice implements RendererPowerToggle.Source {
        final List<String> calls = new ArrayList<>();
        final List<Object> reads = new ArrayList<>(); // State or PowerException
        PowerException setFails;

        FakeDevice reads(Object... answers) {
            reads.addAll(Arrays.asList(answers));
            return this;
        }

        @Override
        public State read() throws PowerException {
            calls.add("read");
            Object next = reads.isEmpty() ? State.UNKNOWN : reads.remove(0);
            if (next instanceof PowerException) {
                throw (PowerException) next;
            }
            return (State) next;
        }

        @Override
        public void set(boolean on) throws PowerException {
            calls.add("set " + on);
            if (setFails != null) {
                throw setFails;
            }
        }
    }

    private final List<Long> pauses = new ArrayList<>();

    private Outcome toggle(FakeDevice device) {
        return RendererPowerToggle.toggle(device, millis -> pauses.add(millis), 800);
    }

    @Test
    public void aDeviceThatIsOnIsSwitchedToStandbyAndReadAgain() {
        FakeDevice device = new FakeDevice().reads(State.ON, State.STANDBY);

        Outcome outcome = toggle(device);

        assertEquals(Arrays.asList("read", "set false", "read"), device.calls);
        assertEquals(State.STANDBY, outcome.state());
        assertEquals(Error.NONE, outcome.error());
        assertEquals("it waits a moment before it asks again", Arrays.asList(800L), pauses);
    }

    @Test
    public void aDeviceInStandbyIsSwitchedOn() {
        FakeDevice device = new FakeDevice().reads(State.STANDBY, State.ON);

        Outcome outcome = toggle(device);

        assertEquals(Arrays.asList("read", "set true", "read"), device.calls);
        assertEquals(State.ON, outcome.state());
    }

    @Test
    public void theActionFollowsTheFreshReadNotTheStateTheCardShowed() {
        // the card showed "on" (a tap would switch off), but the device is in standby now: it is switched ON
        FakeDevice device = new FakeDevice().reads(State.STANDBY, State.ON);

        toggle(device);

        assertTrue(device.calls.toString(), device.calls.contains("set true"));
    }

    @Test
    public void aStateThatCanNotBeReadSendsNothing() {
        FakeDevice device = new FakeDevice().reads(new PowerException());

        Outcome outcome = toggle(device);

        assertEquals(Arrays.asList("read"), device.calls);
        assertEquals(Error.UNREACHABLE, outcome.error());
        assertEquals(State.UNKNOWN, outcome.state());
        assertTrue(pauses.isEmpty());
    }

    @Test
    public void aDeviceThatDoesNotSayWhereItIsIsNotGuessed() {
        FakeDevice device = new FakeDevice().reads(State.UNKNOWN);

        Outcome outcome = toggle(device);

        assertEquals("no change is sent on a guess", Arrays.asList("read"), device.calls);
        assertEquals(Error.UNKNOWN_STATE, outcome.error());
        assertEquals(State.UNKNOWN, outcome.state());
    }

    @Test
    public void aRefusedChangeKeepsTheStateOfTheFreshReadAndIsReported() {
        FakeDevice device = new FakeDevice().reads(State.ON);
        device.setFails = new PowerException();

        Outcome outcome = toggle(device);

        assertEquals(Arrays.asList("read", "set false"), device.calls);
        assertEquals(Error.REFUSED, outcome.error());
        assertEquals("the device is still on", State.ON, outcome.state());
        assertTrue("no waiting for a change that did not happen", pauses.isEmpty());
    }

    @Test
    public void aReadAfterTheChangeThatFailsIsNotAnErrorOfTheChange() {
        FakeDevice device = new FakeDevice().reads(State.ON, new PowerException());

        Outcome outcome = toggle(device);

        assertEquals("the change went through", Error.NONE, outcome.error());
        assertEquals("the new state is not known until the device says so", State.UNKNOWN, outcome.state());
    }

    @Test
    public void aDeviceThatHasNotChangedYetIsReportedAsItAnswered() {
        FakeDevice device = new FakeDevice().reads(State.STANDBY, State.STANDBY); // waking up takes longer than the pause

        Outcome outcome = toggle(device);

        assertEquals(State.STANDBY, outcome.state());
        assertEquals(Error.NONE, outcome.error());
    }

    @Test
    public void anInterruptedPauseEndsTheToggleWithoutAnError() {
        FakeDevice device = new FakeDevice().reads(State.ON, State.STANDBY);

        Outcome outcome = RendererPowerToggle.toggle(device, millis -> {
            throw new InterruptedException();
        }, 800);

        assertEquals(Error.NONE, outcome.error());
        assertEquals(State.UNKNOWN, outcome.state());
        assertTrue("the flag is set again for the thread", Thread.interrupted());
    }

    @Test
    public void aRuntimeFailureOfTheDeviceCodeIsAnErrorAndNotACrash() {
        RendererPowerToggle.Source broken = new RendererPowerToggle.Source() {
            @Override
            public State read() {
                throw new IllegalStateException("odd");
            }

            @Override
            public void set(boolean on) {
            }
        };

        Outcome outcome = RendererPowerToggle.toggle(broken, millis -> { }, 800);

        assertEquals(Error.UNREACHABLE, outcome.error());
    }

    @Test
    public void exceptionsAndOutcomesNeverCarryTheTextOfTheDevice() {
        assertEquals("PowerException", new PowerException().toString());
        assertEquals(null, new PowerException(new java.io.IOException("192.168.1.9 said Kitchen")).getMessage());
        assertEquals("Outcome{STANDBY, NONE}", new Outcome(State.STANDBY, Error.NONE).toString());
    }

    @Test
    public void aDeviceTheAppHasNoWayToCallEndsAsUnreachableAndNothingIsSent() {
        Outcome outcome = RendererPowerToggle.toggle(RendererPowerToggle.unreachable(), millis -> pauses.add(millis), 800);

        assertEquals(Error.UNREACHABLE, outcome.error());
        assertEquals(State.UNKNOWN, outcome.state());
        assertTrue(pauses.isEmpty());
    }
}
