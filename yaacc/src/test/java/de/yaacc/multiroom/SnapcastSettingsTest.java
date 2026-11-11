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
package de.yaacc.multiroom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The keys and defaults of the Snapcast settings. Plain JVM. */
public class SnapcastSettingsTest {

    @Test
    public void discoveryIsOffByDefault() {
        assertFalse(SnapcastSettings.DISCOVERY_DEFAULT);
        assertFalse(SnapcastSettings.isDiscoveryEnabled((key, defaultValue) -> defaultValue));
    }

    @Test
    public void theDiscoverySwitchIsReadFromItsKeyWithTheDefaultOff() {
        final String[] asked = new String[1];
        final boolean[] askedDefault = new boolean[1];

        SnapcastSettings.isDiscoveryEnabled((key, defaultValue) -> {
            asked[0] = key;
            askedDefault[0] = defaultValue;
            return defaultValue;
        });

        assertEquals("settings_snapcast_discovery", asked[0]);
        assertFalse(askedDefault[0]);
    }

    @Test
    public void aSwitchThatIsOnIsOn() {
        assertTrue(SnapcastSettings.isDiscoveryEnabled((key, defaultValue) -> true));
    }

    @Test
    public void aDamagedValueCountsAsOff() {
        // SharedPreferences.getBoolean throws ClassCastException for a value of another type
        assertFalse(SnapcastSettings.isDiscoveryEnabled((key, defaultValue) -> {
            throw new ClassCastException("not a boolean");
        }));
    }

    @Test
    public void noPreferencesCountAsOff() {
        assertFalse(SnapcastSettings.isDiscoveryEnabled(null));
    }

    @Test
    public void onlyTheDiscoveryKeyIsTheDiscoveryKey() {
        assertTrue(SnapcastSettings.isDiscoveryKey("settings_snapcast_discovery"));
        assertFalse(SnapcastSettings.isDiscoveryKey("settings_snapcast_play_experimental"));
        assertFalse(SnapcastSettings.isDiscoveryKey(null));
    }
}
