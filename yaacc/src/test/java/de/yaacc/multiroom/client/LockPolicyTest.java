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
package de.yaacc.multiroom.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Review W3 of Group 8: the Wi-Fi lock that works with the screen off, and a wake lock that does not run out in a long session. */
public class LockPolicyTest {

    @Test
    public void upToAndroid13TheHighPerformanceLockIsUsedBecauseItWorksWithTheScreenOff() {
        for (int sdk : new int[]{27, 28, 29, 30, 31, 32, 33}) {
            assertEquals("api " + sdk, LockPolicy.WIFI_MODE_FULL_HIGH_PERF, LockPolicy.wifiMode(sdk));
            assertTrue("api " + sdk, LockPolicy.wifiLockWorksWithScreenOff(sdk));
        }
    }

    @Test
    public void fromAndroid14TheLowLatencyLockIsUsedAndItDoesNotWorkWithTheScreenOff() {
        for (int sdk : new int[]{34, 35, 36, 40}) {
            assertEquals("api " + sdk, LockPolicy.WIFI_MODE_FULL_LOW_LATENCY, LockPolicy.wifiMode(sdk));
            assertFalse("api " + sdk, LockPolicy.wifiLockWorksWithScreenOff(sdk));
        }
    }

    @Test
    public void theModeNumbersAreThoseOfThePlatform() {
        assertEquals(3, LockPolicy.WIFI_MODE_FULL_HIGH_PERF);
        assertEquals(4, LockPolicy.WIFI_MODE_FULL_LOW_LATENCY);
    }

    @Test
    public void theWakeLockIsRenewedLongBeforeItsSafetyTimeoutEndsAndNeverTooOften() {
        assertTrue(LockPolicy.WAKE_LOCK_RENEW_INTERVAL_MS * 4 <= LockPolicy.WAKE_LOCK_SAFETY_TIMEOUT_MS);
        assertFalse(LockPolicy.shouldRenewWakeLock(1000, 1000));
        assertFalse(LockPolicy.shouldRenewWakeLock(1000 + LockPolicy.WAKE_LOCK_RENEW_INTERVAL_MS - 1, 1000));
        assertTrue(LockPolicy.shouldRenewWakeLock(1000 + LockPolicy.WAKE_LOCK_RENEW_INTERVAL_MS, 1000));
    }

    @Test
    public void aSessionOfTwentyHoursKeepsItsWakeLockWhenRenewedOnTime() {
        long now = 0;
        long lastRenew = 0;
        long timeoutEnd = LockPolicy.WAKE_LOCK_SAFETY_TIMEOUT_MS;
        for (long minute = 0; minute <= 20 * 60; minute++) {
            now = minute * 60_000L;
            assertTrue("the lock is held at minute " + minute, now < timeoutEnd);
            if (LockPolicy.shouldRenewWakeLock(now, lastRenew)) {
                lastRenew = now;
                timeoutEnd = now + LockPolicy.WAKE_LOCK_SAFETY_TIMEOUT_MS;
            }
        }
    }
}
