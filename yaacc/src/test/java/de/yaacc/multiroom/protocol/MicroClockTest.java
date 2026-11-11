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
package de.yaacc.multiroom.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

/** The clock of the time sync: counted from its creation, on any source of nanoseconds. */
public class MicroClockTest {

    @Test
    public void aClockOnAnInjectedSourceCountsFromItsCreation() {
        AtomicLong nanos = new AtomicLong(Long.MAX_VALUE - 5_000_000_000L); // an arbitrary origin, as nanoTime has
        MicroClock clock = MicroClock.fromNanoSource(nanos::get);

        long start = clock.nowMicros();
        nanos.addAndGet(2_500_000_000L);

        assertEquals(1_000_000L, start);
        assertEquals(start + 2_500_000L, clock.nowMicros());
    }

    @Test
    public void theRealClockIsSmallAndNeverGoesBack() {
        MicroClock clock = MicroClock.monotonic();

        long first = clock.nowMicros();
        long second = clock.nowMicros();

        assertTrue(first >= 1_000_000L && first < Integer.MAX_VALUE * 1_000_000L);
        assertTrue(second >= first);
    }
}
