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
package de.yaacc.multiroom.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The switch of the built-in server: off unless the user switched it on, and a value that can not be read counts as off (issue #330, S1). */
public class SnapcastServerSettingsTest {

    @Test
    public void theServerIsOffByDefault() {
        assertFalse(SnapcastServerSettings.isEnabled((key, def) -> def));
        assertFalse(SnapcastServerSettings.DEFAULT_ENABLED);
    }

    @Test
    public void theStoredChoiceIsUsed() {
        assertTrue(SnapcastServerSettings.isEnabled((key, def) -> key.equals(SnapcastServerSettings.KEY_ENABLED)));
        assertFalse(SnapcastServerSettings.isEnabled((key, def) -> false));
    }

    @Test
    public void aValueThatCanNotBeReadOrNoSourceCountsAsOff() {
        assertFalse(SnapcastServerSettings.isEnabled(null));
        assertFalse(SnapcastServerSettings.isEnabled((key, def) -> {
            throw new ClassCastException("another type");
        }));
    }

    @Test
    public void theBufferIsHeldToTheRangeOfTheProtocolAndDefaultsToOneSecond() {
        assertEquals(1000, SnapcastServerSettings.DEFAULT_BUFFER_MS);
        assertEquals(1000, SnapcastServerSettings.bufferMs(100000));
        assertEquals(200, SnapcastServerSettings.bufferMs(5));
        assertEquals(750, SnapcastServerSettings.bufferMs(750));
        assertEquals(5000, SnapcastServerSettings.bufferMs(5000));
    }

    @Test
    public void theServiceNameTellsThatItIsAPhoneAndIsCleaned() {
        assertEquals("YAACC Pixel 8", SnapcastServerSettings.serviceName("Pixel 8"));
        assertEquals("YAACC", SnapcastServerSettings.serviceName("  "));
        assertEquals("YAACC", SnapcastServerSettings.serviceName(null));
        assertEquals("YAACC a b", SnapcastServerSettings.serviceName("a\nb"));
        assertTrue(SnapcastServerSettings.serviceName("x".repeat(200)).length() <= 63);
    }
}
