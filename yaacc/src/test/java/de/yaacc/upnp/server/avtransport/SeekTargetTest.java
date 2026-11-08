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
    public void aFractionOfASecondIsKept() {
        assertEquals(Long.valueOf(20_500L), SeekTarget.millis("00:00:20.5"));
        assertEquals(Long.valueOf(20_050L), SeekTarget.millis("00:00:20.05"));
        assertEquals(Long.valueOf(20_123L), SeekTarget.millis("00:00:20.123"));
        assertEquals(Long.valueOf(36_000_000L), SeekTarget.millis("10:00:00"));
        assertEquals(Long.valueOf(9999L * 3_600_000L), SeekTarget.millis("9999:00:00"));
    }

    @Test
    public void numbersOutOfRangeSignsAndTrailingTextAreNotAPosition() {
        for (String hostile : new String[]{"-5:00:00", "00:00:-9999", "+1:00:00", "99:99:99", "00:60:00", "00:00:60",
                "00:00:20junk", " 00:00:20", "00:00:20 ", "99999999999:00:00", "4294967296:00:00", "00:00:20.1234",
                "0:0:5", "00:00", "::", "00:00:20\n"}) {
            assertNull(hostile, SeekTarget.millis(hostile));
        }
    }

    @Test
    public void aVeryLongTextIsRefusedWithoutBeingParsed() {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < 100_000; i++) {
            digits.append('9');
        }
        long start = System.nanoTime();
        assertNull(SeekTarget.millis(digits.toString()));
        assertNull(SeekTarget.millis(digits + ":00:00"));
        assertNull(SeekTarget.millis("0".repeat(SeekTarget.MAX_LENGTH + 1)));
        assertEquals("a long text is refused at once", true, (System.nanoTime() - start) < 1_000_000_000L);
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
        assertNull(SeekTarget.millis("12:30"));
    }
}
