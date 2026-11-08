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
package de.yaacc.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

/**
 * {@link LaunchNonces}: the single-use token the launcher puts into the intent it forwards, so
 * that the browser accepts a shared file only from the launcher (after its checks), never from an
 * app that starts the exported browser activity directly. Plain JVM, injected clock and random.
 */
public class LaunchNoncesTest {

    private static final long TTL = 30_000L;

    private final AtomicLong clock = new AtomicLong(1_000L);
    private final Random random = new Random(7L);

    private LaunchNonces newNonces() {
        return new LaunchNonces(clock::get, random::nextBytes, TTL, 4);
    }

    @Test
    public void aMintedNonceIsAcceptedOnce() {
        LaunchNonces nonces = newNonces();
        String nonce = nonces.mint();

        assertTrue(nonces.consume(nonce));
        assertFalse("single use", nonces.consume(nonce));
    }

    @Test
    public void aNonceIs128BitsOfHex() {
        String nonce = newNonces().mint();

        assertNotNull(nonce);
        assertEquals(32, nonce.length());
        assertTrue(nonce.matches("[0-9a-f]{32}"));
    }

    @Test
    public void everyMintedNonceIsDifferent() {
        LaunchNonces nonces = new LaunchNonces(clock::get, new java.security.SecureRandom()::nextBytes, TTL, 100);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            assertTrue(seen.add(nonces.mint()));
        }
    }

    @Test
    public void aMissingNonceIsRejected() {
        LaunchNonces nonces = newNonces();
        nonces.mint();

        assertFalse(nonces.consume(null));
        assertFalse(nonces.consume(""));
    }

    @Test
    public void aForgedNonceIsRejectedAndDoesNotSpendTheRealOne() {
        LaunchNonces nonces = newNonces();
        String real = nonces.mint();
        String forged = real.substring(0, 31) + (real.charAt(31) == '0' ? '1' : '0');

        assertFalse(nonces.consume(forged));
        assertFalse(nonces.consume("00000000000000000000000000000000"));
        assertFalse(nonces.consume(real + "0"));
        assertFalse(nonces.consume(real.substring(1)));
        assertFalse(nonces.consume(real.toUpperCase()));
        assertTrue("the real one is still good", nonces.consume(real));
    }

    @Test
    public void withNothingMintedEverythingIsRejected() {
        assertFalse(newNonces().consume("00000000000000000000000000000000"));
    }

    @Test
    public void anExpiredNonceIsRejected() {
        LaunchNonces nonces = newNonces();
        String nonce = nonces.mint();

        clock.addAndGet(TTL);

        assertFalse(nonces.consume(nonce));
    }

    @Test
    public void aNonceJustBeforeItsExpiryIsStillAccepted() {
        LaunchNonces nonces = newNonces();
        String nonce = nonces.mint();

        clock.addAndGet(TTL - 1L);

        assertTrue(nonces.consume(nonce));
    }

    @Test
    public void aClockThatRunsBackwardsCountsAsExpired() {
        LaunchNonces nonces = newNonces();
        String nonce = nonces.mint();

        clock.addAndGet(-5L);

        assertFalse(nonces.consume(nonce));
    }

    @Test
    public void severalNoncesLiveSideBySideAndEachIsSpentOnItsOwn() {
        LaunchNonces nonces = newNonces();
        String first = nonces.mint();
        String second = nonces.mint();
        assertNotEquals(first, second);

        assertTrue(nonces.consume(second));
        assertFalse(nonces.consume(second));
        assertTrue(nonces.consume(first));
    }

    @Test
    public void theStoreIsBoundedAndDropsTheOldestFirst() {
        LaunchNonces nonces = newNonces();
        String oldest = nonces.mint();
        String second = nonces.mint();
        nonces.mint();
        nonces.mint();
        assertEquals(4, nonces.size());

        String newest = nonces.mint();

        assertEquals(4, nonces.size());
        assertFalse("the oldest was dropped", nonces.consume(oldest));
        assertTrue(nonces.consume(second));
        assertTrue(nonces.consume(newest));
    }

    @Test
    public void expiredNoncesFreeTheirPlaceBeforeALiveOneIsDropped() {
        LaunchNonces nonces = newNonces();
        for (int i = 0; i < 4; i++) {
            nonces.mint();
        }
        clock.addAndGet(TTL);
        String fresh = nonces.mint();

        assertEquals(1, nonces.size());
        assertTrue(nonces.consume(fresh));
    }

    @Test
    public void aSpentNonceFreesItsPlace() {
        LaunchNonces nonces = newNonces();
        String nonce = nonces.mint();
        nonces.consume(nonce);

        assertEquals(0, nonces.size());
    }

    @Test
    public void theDefaultsAreThirtySecondsAndTheExtraHasAFixedName() {
        assertEquals(30_000L, ShareLimits.LAUNCH_NONCE_TTL_MILLIS);
        assertEquals("de.yaacc.extra.LAUNCH_NONCE", LaunchNonces.EXTRA_NAME);
        LaunchNonces nonces = LaunchNonces.createDefault();
        assertTrue(nonces.consume(nonces.mint()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void aNonPositiveTtlIsRefused() {
        new LaunchNonces(clock::get, random::nextBytes, 0L, 4);
    }
}
