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
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.yaacc.openhome.OhControl;
import de.yaacc.openhome.OhControlException;
import de.yaacc.openhome.OhRemote;

/**
 * The power button of an OpenHome card (task 12.4): the capability is that the Product service of the device description has the
 * actions {@code Standby} and {@code SetStandby}, the state is the {@code Standby} value (true is standby), the change is
 * {@code SetStandby}. The book keeps the last value per device and says when it changed. Plain JVM, a fake control.
 */
public class OpenHomePowerTest {

    private static final class FakeProduct implements OhControl {
        final List<String> calls = new ArrayList<>();
        String standby = "false";
        OhControlException failure;

        @Override
        public Map<String, String> invoke(String service, String action, Map<String, String> arguments) throws OhControlException {
            calls.add(service + "." + action + arguments);
            if (failure != null) {
                throw failure;
            }
            Map<String, String> out = new HashMap<>();
            if (action.equals("Standby")) {
                out.put("Value", standby);
            }
            return out;
        }
    }

    // ---- capability

    @Test
    public void aProductServiceWithBothActionsOffersPower() {
        assertTrue(OpenHomePower.offers(Arrays.asList("Product", "Standby", "SetStandby", "SourceXml")));
    }

    @Test
    public void aProductServiceWithoutOneOfThemDoesNot() {
        assertFalse(OpenHomePower.offers(Arrays.asList("Standby", "SourceXml")));
        assertFalse(OpenHomePower.offers(Arrays.asList("SetStandby")));
        assertFalse(OpenHomePower.offers(Collections.<String>emptyList()));
        assertFalse(OpenHomePower.offers(null));
    }

    @Test
    public void theActionNamesAreMatchedExactly() {
        assertFalse(OpenHomePower.offers(Arrays.asList("standby", "setstandby")));
    }

    // ---- state

    @Test
    public void standbyTrueIsStandbyAndFalseIsOn() {
        assertEquals(RendererPower.of(RendererPower.State.STANDBY), OpenHomePower.of(true, Boolean.TRUE));
        assertEquals(RendererPower.of(RendererPower.State.ON), OpenHomePower.of(true, Boolean.FALSE));
    }

    @Test
    public void aStateNotReadYetIsUnknownButTheButtonIsThere() {
        assertEquals(RendererPower.of(RendererPower.State.UNKNOWN), OpenHomePower.of(true, null));
    }

    @Test
    public void aDeviceWithoutTheActionsHasNoButtonWhatEverWasRead() {
        assertEquals(RendererPower.unsupported(), OpenHomePower.of(false, Boolean.TRUE));
        assertEquals(RendererPower.unsupported(), OpenHomePower.of(false, null));
    }

    // ---- the calls

    @Test
    public void theDeviceIsReadAndSwitchedWithStandbyAndSetStandby() throws Exception {
        FakeProduct device = new FakeProduct();
        device.standby = "true";
        RendererPowerToggle.Source source = OpenHomePower.source(new OhRemote(device, millis -> { }));

        assertEquals(RendererPower.State.STANDBY, source.read());
        source.set(true);
        source.set(false);

        assertEquals(Arrays.asList("Product.Standby{}", "Product.SetStandby{Value=false}", "Product.SetStandby{Value=true}"), device.calls);
    }

    @Test
    public void aDeviceThatSaysNotStandbyIsOn() throws Exception {
        FakeProduct device = new FakeProduct();
        device.standby = "0";

        assertEquals(RendererPower.State.ON, OpenHomePower.source(new OhRemote(device, millis -> { })).read());
    }

    @Test
    public void aDeviceThatFailsIsAPowerExceptionWithoutItsText() {
        FakeProduct device = new FakeProduct();
        device.failure = new OhControlException(OhControlException.NO_ANSWER, "no answer from 192.168.1.9");
        RendererPowerToggle.Source source = OpenHomePower.source(new OhRemote(device, millis -> { }));

        try {
            source.read();
            fail();
        } catch (RendererPowerToggle.PowerException e) {
            assertNull(e.getMessage());
        }
        try {
            source.set(false);
            fail();
        } catch (RendererPowerToggle.PowerException e) {
            assertNull(e.getMessage());
        }
    }

    // ---- the book of the last values

    @Test
    public void theBookSaysWhenAValueChangedAndRemembersIt() {
        OpenHomePowerBook book = new OpenHomePowerBook();

        assertNull(book.standbyOf("a"));
        assertTrue("first value", book.update("a", Boolean.TRUE));
        assertFalse("same value", book.update("a", Boolean.TRUE));
        assertEquals(Boolean.TRUE, book.standbyOf("a"));
        assertTrue(book.update("a", Boolean.FALSE));
        assertTrue("not known any more", book.update("a", null));
        assertNull(book.standbyOf("a"));
        assertFalse(book.update("a", null));
    }

    @Test
    public void theBookKeepsDevicesApart() {
        OpenHomePowerBook book = new OpenHomePowerBook();
        book.update("a", Boolean.TRUE);
        book.update("b", Boolean.FALSE);

        assertEquals(Boolean.TRUE, book.standbyOf("a"));
        assertEquals(Boolean.FALSE, book.standbyOf("b"));
        book.forget("a");
        assertNull(book.standbyOf("a"));
        assertEquals(Boolean.FALSE, book.standbyOf("b"));
    }

    @Test
    public void theBookIsBounded() {
        OpenHomePowerBook book = new OpenHomePowerBook();
        for (int i = 0; i < 1_000; i++) {
            book.update("device" + i, Boolean.TRUE);
        }

        assertTrue(book.size() <= OpenHomePowerBook.MAX_DEVICES);
        assertEquals("the latest ones stay", Boolean.TRUE, book.standbyOf("device999"));
    }

    @Test
    public void aNullKeyIsIgnored() {
        OpenHomePowerBook book = new OpenHomePowerBook();

        assertFalse(book.update(null, Boolean.TRUE));
        assertNull(book.standbyOf(null));
    }
}
