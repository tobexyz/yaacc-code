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
package de.yaacc.yxc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Whether the main zone of a MusicCast device offers power (task 12.4): {@code power} in the {@code func_list} of the zone in
 * {@code system/getFeatures}, the rule of docs/tech.md ("needs power in zone func_list"). A device that does not list it gets no
 * power button. Plain JVM.
 */
public class YxcSourceCapabilitiesPowerTest {

    private static YxcSourceCapabilities of(String featuresJson) throws Exception {
        return new YxcClient(new RecordingTransport().answer("system/getFeatures", featuresJson)).getFeatures().getSourceCapabilities();
    }

    @Test
    public void theCapturedDevicesOfferPower() throws Exception {
        for (String device : new String[]{"a_features", "b_features", "c_features"}) {
            assertTrue(device, of(YxcFixtures.read("captured/" + device + ".json")).hasPower());
        }
    }

    @Test
    public void aMainZoneWithoutPowerInItsFunctionListDoesNot() throws Exception {
        assertFalse(of("{\"response_code\":0,\"zone\":[{\"id\":\"main\",\"func_list\":[\"volume\",\"mute\"]}]}").hasPower());
    }

    @Test
    public void onlyThePowerOfTheMainZoneCounts() throws Exception {
        assertFalse(of("{\"response_code\":0,\"zone\":[{\"id\":\"main\",\"func_list\":[\"volume\"]},"
                + "{\"id\":\"zone2\",\"func_list\":[\"power\"]}]}").hasPower());
    }

    @Test
    public void noZonesAtAllOrNothingKnownOffersNothing() throws Exception {
        assertFalse(of("{\"response_code\":0}").hasPower());
        assertFalse(YxcSourceCapabilities.none().hasPower());
    }

    @Test
    public void powerIsPartOfTheEquality() throws Exception {
        YxcSourceCapabilities withPower = of("{\"response_code\":0,\"zone\":[{\"id\":\"main\",\"func_list\":[\"power\"]}]}");
        YxcSourceCapabilities without = of("{\"response_code\":0,\"zone\":[{\"id\":\"main\",\"func_list\":[\"volume\"]}]}");

        assertNotEquals(withPower, without);
        assertNotEquals(withPower, YxcSourceCapabilities.none());
        assertEquals(withPower, of("{\"response_code\":0,\"zone\":[{\"id\":\"main\",\"func_list\":[\"power\",\"volume\"]}]}"));
    }
}
