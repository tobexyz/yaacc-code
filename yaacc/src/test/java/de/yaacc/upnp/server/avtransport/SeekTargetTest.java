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
package de.yaacc.upnp.server.avtransport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.After;
import org.junit.Test;

import java.util.TimeZone;

/** The REL_TIME target of a Seek. Plain JVM. */
public class SeekTargetTest {

    private final TimeZone defaultZone = TimeZone.getDefault();

    @After
    public void restoreTheTimeZone() {
        TimeZone.setDefault(defaultZone);
    }

    @Test
    public void aTimeCodeIsReadAsMillisecondsFromTheStartIndependentOfTheTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata")); // not GMT, with a half hour offset
        assertEquals(Long.valueOf(0L), SeekTarget.millis("00:00:00"));
        assertEquals(Long.valueOf(20_000L), SeekTarget.millis("00:00:20"));
        assertEquals(Long.valueOf(65_000L), SeekTarget.millis("00:01:05"));
        assertEquals(Long.valueOf(3_600_000L), SeekTarget.millis("01:00:00"));
    }

    @Test
    public void noTargetMeansTheStart() {
        assertEquals(Long.valueOf(0L), SeekTarget.millis(null));
    }

    @Test
    public void textThatIsNoTimeCodeIsNotReadAndTheSeekIsIgnored() {
        assertNull(SeekTarget.millis("soon"));
        assertNull(SeekTarget.millis(""));
        assertNull(SeekTarget.millis("20"));
    }
}
