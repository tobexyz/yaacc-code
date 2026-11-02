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
package de.yaacc.upnp.server.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Locale;
import java.util.TimeZone;

public class HttpDatesTest {

    private Locale locale;
    private TimeZone zone;

    @Before
    public void foreignDevice() {
        locale = Locale.getDefault();
        zone = TimeZone.getDefault();
        Locale.setDefault(Locale.GERMANY);
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
    }

    @After
    public void restore() {
        Locale.setDefault(locale);
        TimeZone.setDefault(zone);
    }

    @Test
    public void theEpochIsInEnglishAndGmtWhateverTheDeviceLanguageAndZone() {
        assertEquals("Thu, 01 Jan 1970 00:00:00 GMT", HttpDates.imfFixdate(0L));
    }

    @Test
    public void theOwnersExampleOfTheReport() {
        // 2026-11-01 17:55:36 UTC: a German phone sent "So., 01 Nov. 2026 17:55:36 GMT+00:00"
        assertEquals("Sun, 01 Nov 2026 17:55:36 GMT", HttpDates.imfFixdate(1793555736000L));
    }

    @Test
    public void theDayHasTwoDigitsAndTheMonthIsAbbreviatedWithoutADot() {
        assertEquals("Mon, 05 Mar 2001 03:04:05 GMT", HttpDates.imfFixdate(983761445000L));
        assertEquals("Sat, 31 Dec 2022 23:59:59 GMT", HttpDates.imfFixdate(1672531199000L));
    }

    @Test
    public void nowMatchesTheFormat() {
        assertTrue(HttpDates.now(), HttpDates.now().matches("[A-Z][a-z]{2}, \\d{2} [A-Z][a-z]{2} \\d{4} \\d{2}:\\d{2}:\\d{2} GMT"));
    }
}
