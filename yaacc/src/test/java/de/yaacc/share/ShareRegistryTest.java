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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link ShareRegistry}: random unguessable keys, in-memory entries with a
 * per-entry address set, expiry by time and by release, a cap on active entries, and targets that
 * are closed exactly once. Plain JVM, fake clock and fake random source.
 */
public class ShareRegistryTest {

    private static final long GRACE = 10_000L;
    private static final long ABSOLUTE = 100_000L;

    /** Writes a counter into the bytes, so every call yields different, predictable bytes. */
    private static final class CountingRandom implements Consumer<byte[]> {
        private long counter = 0;
        int calls = 0;
        int lastLength = -1;

        @Override
        public synchronized void accept(byte[] bytes) {
            calls++;
            lastLength = bytes.length;
            Arrays.fill(bytes, (byte) 0xA5);
            long value = ++counter;
            for (int i = bytes.length - 1; i >= bytes.length - 8 && i >= 0; i--) {
                bytes[i] = (byte) value;
                value >>>= 8;
            }
        }
    }

    /** A source that records how often it was closed. */
    private static final class CloseCounter implements AutoCloseable {
        final AtomicInteger closes = new AtomicInteger();
        final boolean failOnClose;

        CloseCounter() {
            this(false);
        }

        CloseCounter(boolean failOnClose) {
            this.failOnClose = failOnClose;
        }

        @Override
        public void close() throws java.io.IOException {
            closes.incrementAndGet();
            if (failOnClose) {
                throw new java.io.IOException("close failed");
            }
        }
    }

    private volatile long now;
    private CountingRandom random;
    private ShareRegistry registry;

    @Before
    public void setUp() {
        now = 1_000_000L;
        random = new CountingRandom();
        registry = new ShareRegistry(() -> now, random);
    }

    private static Set<String> set(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    private static ShareRegistry.Entry fileEntry(Object target) {
        return new ShareRegistry.Entry(ShareRegistry.Kind.LOCAL_FILE, target, "audio/mpeg",
                set("192.168.1.5"), GRACE, ABSOLUTE);
    }

    private static ShareRegistry.Entry fileEntry(Object target, long grace, long absolute) {
        return new ShareRegistry.Entry(ShareRegistry.Kind.LOCAL_FILE, target, "audio/mpeg",
                set("192.168.1.5"), grace, absolute);
    }

    private String registerOk(Object target) {
        String key = registry.register(fileEntry(target));
        assertNotNull("register returned null", key);
        return key;
    }

    // ---- Entry

    @Test
    public void entryExposesItsFields() {
        Object target = new Object();
        ShareRegistry.Entry entry = new ShareRegistry.Entry(ShareRegistry.Kind.HTTP_LINK, target,
                "video/mp4", set("10.0.0.2", "10.0.0.3"), 5L, 7L);

        assertEquals(ShareRegistry.Kind.HTTP_LINK, entry.getKind());
        assertSame(target, entry.getTarget());
        assertEquals("video/mp4", entry.getMimeType());
        assertEquals(set("10.0.0.2", "10.0.0.3"), entry.getAllowedAddresses());
        assertEquals(5L, entry.getIdleGraceMillis());
        assertEquals(7L, entry.getAbsoluteMaxMillis());
    }

    @Test
    public void entryCopiesTheAddressSet() {
        Set<String> addresses = set("10.0.0.2");
        ShareRegistry.Entry entry = new ShareRegistry.Entry(ShareRegistry.Kind.HTTP_LINK, "u",
                null, addresses, GRACE, ABSOLUTE);

        addresses.add("10.0.0.99");

        assertEquals(set("10.0.0.2"), entry.getAllowedAddresses());
    }

    @Test
    public void entryAddressSetIsUnmodifiable() {
        ShareRegistry.Entry entry = fileEntry("u");

        try {
            entry.getAllowedAddresses().add("10.0.0.99");
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }

    @Test
    public void entryWithNullAddressSetHasNoAddresses() {
        ShareRegistry.Entry entry = new ShareRegistry.Entry(ShareRegistry.Kind.HTTP_LINK, "u",
                null, null, GRACE, ABSOLUTE);

        assertNotNull(entry.getAllowedAddresses());
        assertTrue(entry.getAllowedAddresses().isEmpty());
    }

    @Test
    public void entryDropsNullAddressElements() {
        ShareRegistry.Entry entry = new ShareRegistry.Entry(ShareRegistry.Kind.HTTP_LINK, "u",
                null, new HashSet<>(Arrays.asList(null, "10.0.0.2")), GRACE, ABSOLUTE);

        assertEquals(set("10.0.0.2"), entry.getAllowedAddresses());
    }

    @Test
    public void entryAcceptsNullMimeTypeAndZeroGrace() {
        ShareRegistry.Entry entry = new ShareRegistry.Entry(ShareRegistry.Kind.HTTP_LINK, "u",
                null, set(), 0L, 1L);

        assertNull(entry.getMimeType());
        assertEquals(0L, entry.getIdleGraceMillis());
    }

    @Test(expected = IllegalArgumentException.class)
    public void entryRejectsNullKind() {
        new ShareRegistry.Entry(null, "u", null, set(), GRACE, ABSOLUTE);
    }

    @Test(expected = IllegalArgumentException.class)
    public void entryRejectsNullTarget() {
        new ShareRegistry.Entry(ShareRegistry.Kind.HTTP_LINK, null, null, set(), GRACE, ABSOLUTE);
    }

    @Test(expected = IllegalArgumentException.class)
    public void entryRejectsNegativeGrace() {
        new ShareRegistry.Entry(ShareRegistry.Kind.HTTP_LINK, "u", null, set(), -1L, ABSOLUTE);
    }

    @Test(expected = IllegalArgumentException.class)
    public void entryRejectsZeroAbsoluteMax() {
        new ShareRegistry.Entry(ShareRegistry.Kind.HTTP_LINK, "u", null, set(), GRACE, 0L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void entryRejectsNegativeAbsoluteMax() {
        new ShareRegistry.Entry(ShareRegistry.Kind.HTTP_LINK, "u", null, set(), GRACE, -5L);
    }

    // ---- construction

    @Test(expected = IllegalArgumentException.class)
    public void constructorRejectsNullClock() {
        new ShareRegistry(null, random);
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructorRejectsNullRandomSource() {
        new ShareRegistry(() -> now, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructorRejectsZeroMaxActive() {
        new ShareRegistry(() -> now, random, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructorRejectsNegativeMaxActive() {
        new ShareRegistry(() -> now, random, -1);
    }

    @Test
    public void defaultMaxActiveIsEight() {
        assertEquals(8, ShareRegistry.DEFAULT_MAX_ACTIVE);
    }

    @Test
    public void defaultRegistryRegistersAndLooksUp() {
        ShareRegistry byDefault = ShareRegistry.createDefault();
        assertNotNull(byDefault);
        ShareRegistry.Entry entry = fileEntry("u");

        String key = byDefault.register(entry);

        assertNotNull(key);
        assertSame(entry, byDefault.lookup(key));
    }

    // ---- keys

    @Test
    public void registerReturnsAKey() {
        assertNotNull(registry.register(fileEntry("u")));
    }

    @Test
    public void keyIsTwentyTwoUrlSafeCharacters() {
        String key = registerOk("u");

        assertTrue("key was " + key, key.matches("[A-Za-z0-9_-]{22}"));
    }

    @Test
    public void keyIsUnpaddedBase64UrlOfSixteenRandomBytes() {
        final byte[] fixed = new byte[16];
        for (int i = 0; i < fixed.length; i++) {
            fixed[i] = (byte) i;
        }
        final int[] requested = {-1};
        ShareRegistry fixedRegistry = new ShareRegistry(() -> now, bytes -> {
            requested[0] = bytes.length;
            System.arraycopy(fixed, 0, bytes, 0, Math.min(bytes.length, fixed.length));
        });

        String key = fixedRegistry.register(fileEntry("u"));

        assertEquals(16, requested[0]);
        assertEquals("AAECAwQFBgcICQoLDA0ODw", key);
    }

    @Test
    public void keyUsesUrlSafeAlphabetNotPlusSlashOrPadding() {
        ShareRegistry fixedRegistry = new ShareRegistry(() -> now, bytes -> {
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) (i % 2 == 0 ? 0xFB : 0xFF);
            }
        });

        String key = fixedRegistry.register(fileEntry("u"));

        assertEquals("-__7__v_-__7__v_-__7_w", key);
    }

    @Test
    public void twoRegistrationsGetDifferentKeys() {
        String first = registerOk("a");
        String second = registerOk("b");

        assertNotEquals(first, second);
    }

    @Test
    public void sameTargetRegisteredTwiceGetsDifferentKeys() {
        Object target = new Object();

        String first = registerOk(target);
        String second = registerOk(target);

        assertNotEquals(first, second);
    }

    @Test
    public void keyDoesNotDependOnTheTarget() {
        ShareRegistry other = new ShareRegistry(() -> now, new CountingRandom());

        String keyA = registry.register(fileEntry("content://a/1"));
        String keyB = other.register(fileEntry("http://completely.different/2"));

        assertNotNull(keyA);
        assertEquals(keyA, keyB);
    }

    @Test
    public void keyDoesNotContainTheTargetText() {
        String key = registry.register(new ShareRegistry.Entry(ShareRegistry.Kind.HTTP_LINK,
                "AAAAAAAAAAAAAAAAAAAAAA", "audio/mpeg", set(), GRACE, ABSOLUTE));

        assertNotNull(key);
        assertFalse(key.contains("AAAAAAAAAAAAAAAAAAAAAA"));
    }

    @Test
    public void aBrokenRandomSourceThatRepeatsNeverGivesTheSameKeyTwice() {
        ShareRegistry repeating = new ShareRegistry(() -> now, bytes -> Arrays.fill(bytes, (byte) 7));
        String first = repeating.register(fileEntry("a"));

        String second = repeating.register(fileEntry("b"));

        assertNotNull(first);
        assertNull(second);
        assertEquals(1, repeating.size());
        assertNotNull(repeating.lookup(first));
    }

    @Test
    public void aRandomSourceThatRepeatsOnceIsRetried() {
        final int[] calls = {0};
        ShareRegistry flaky = new ShareRegistry(() -> now, bytes -> {
            calls[0]++;
            // calls 1 and 2 give the same bytes, afterwards fresh ones
            Arrays.fill(bytes, (byte) (calls[0] <= 2 ? 1 : calls[0]));
        });
        String first = flaky.register(fileEntry("a"));

        String second = flaky.register(fileEntry("b"));

        assertNotNull(first);
        assertNotNull(second);
        assertNotEquals(first, second);
    }

    @Test
    public void manyKeysAreAllDifferent() {
        ShareRegistry big = new ShareRegistry(() -> now, new SecureRandom()::nextBytes, 2000);
        Set<String> keys = new HashSet<>();

        for (int i = 0; i < 1000; i++) {
            String key = big.register(fileEntry("t" + i));
            assertNotNull(key);
            keys.add(key);
        }

        assertEquals(1000, keys.size());
        assertEquals(1000, big.size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void registerRejectsANullEntry() {
        registry.register(null);
    }

    // ---- lookup

    @Test
    public void lookupReturnsTheRegisteredEntry() {
        ShareRegistry.Entry entry = fileEntry("u");

        String key = registry.register(entry);

        assertSame(entry, registry.lookup(key));
    }

    @Test
    public void lookupOfAnUnknownKeyReturnsNull() {
        registerOk("u");

        assertNull(registry.lookup("AAAAAAAAAAAAAAAAAAAAAA"));
    }

    @Test
    public void lookupOfNullEmptyAndGarbageKeysReturnsNullAndNeverThrows() {
        registerOk("u");
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 100_000; i++) {
            huge.append('x');
        }
        String[] garbage = {null, "", " ", "\n", "../../etc/passwd", "%00", "a\u0000b", "\u00e4\u00f6\u00fc",
                "*", "' OR '1'='1", huge.toString()};

        for (String key : garbage) {
            assertNull("key " + (key == null ? "null" : key.length() + " chars"), registry.lookup(key));
        }
    }

    @Test
    public void lookupMatchesTheKeyExactlyNoCaseFoldingNoTrimmingNoPrefix() {
        String key = registerOk("u");

        assertNotNull(registry.lookup(key));
        assertNull(registry.lookup(key.toUpperCase()));
        assertNull(registry.lookup(" " + key));
        assertNull(registry.lookup(key + " "));
        assertNull(registry.lookup(key + "A"));
        assertNull(registry.lookup(key.substring(0, key.length() - 1)));
    }

    // ---- expiry by time

    @Test
    public void entryIsValidOneMillisecondBeforeTheAbsoluteMaximum() {
        String key = registerOk("u");

        now += ABSOLUTE - 1;

        assertNotNull(registry.lookup(key));
    }

    @Test
    public void entryIsGoneExactlyAtTheAbsoluteMaximum() {
        String key = registerOk("u");

        now += ABSOLUTE;

        assertNull(registry.lookup(key));
    }

    @Test
    public void absoluteMaximumCountsFromRegistrationNotFromConstruction() {
        now += 5_000L;
        String key = registerOk("u");

        now += ABSOLUTE - 1;

        assertNotNull(registry.lookup(key));
    }

    @Test
    public void lookupDoesNotExtendTheAbsoluteMaximum() {
        String key = registerOk("u");

        now += ABSOLUTE - 1;
        assertNotNull(registry.lookup(key));
        now += 1;

        assertNull(registry.lookup(key));
    }

    @Test
    public void lookupOfAnExpiredEntryClosesItsTargetOnce() {
        CloseCounter source = new CloseCounter();
        String key = registerOk(source);

        now += ABSOLUTE;
        assertNull(registry.lookup(key));
        assertNull(registry.lookup(key));
        registry.expire();

        assertEquals(1, source.closes.get());
    }

    @Test
    public void aClockThatGoesBackwardsDoesNotExpireTheEntry() {
        String key = registerOk("u");

        now -= 5_000L;

        assertNotNull(registry.lookup(key));
    }

    // ---- expiry by release

    @Test
    public void releasedEntryStaysValidDuringTheGracePeriod() {
        String key = registerOk("u");

        registry.release(key);
        now += GRACE - 1;

        assertNotNull(registry.lookup(key));
    }

    @Test
    public void releasedEntryIsGoneWhenTheGracePeriodIsOver() {
        String key = registerOk("u");

        registry.release(key);
        now += GRACE;

        assertNull(registry.lookup(key));
    }

    @Test
    public void releasedEntryClosesItsTargetWhenTheGracePeriodIsOver() {
        CloseCounter source = new CloseCounter();
        String key = registerOk(source);

        registry.release(key);
        now += GRACE;
        registry.expire();

        assertEquals(1, source.closes.get());
    }

    @Test
    public void releaseDoesNotCloseTheTargetImmediately() {
        CloseCounter source = new CloseCounter();
        String key = registerOk(source);

        registry.release(key);

        assertEquals(0, source.closes.get());
        assertNotNull(registry.lookup(key));
    }

    @Test
    public void graceNeverOutlivesTheAbsoluteMaximum() {
        String key = registry.register(fileEntry("u", 10_000L, 5_000L));

        now += 1_000L;
        registry.release(key);
        now += 4_000L;

        assertNull(registry.lookup(key));
    }

    @Test
    public void zeroGraceExpiresTheEntryAtOnceOnRelease() {
        String key = registry.register(fileEntry("u", 0L, ABSOLUTE));

        registry.release(key);

        assertNull(registry.lookup(key));
    }

    @Test
    public void releasingTwiceDoesNotRestartTheGracePeriod() {
        String key = registerOk("u");

        registry.release(key);
        now += GRACE - 1;
        registry.release(key);
        now += 1;

        assertNull(registry.lookup(key));
    }

    @Test
    public void releaseOfNullUnknownAndGarbageKeysIsIgnored() {
        registerOk("u");

        registry.release(null);
        registry.release("");
        registry.release("nope");
        registry.release("\u0000");

        assertEquals(1, registry.size());
    }

    // ---- markActive

    @Test
    public void markActiveOfALiveEntryReturnsTrue() {
        String key = registerOk("u");

        assertTrue(registry.markActive(key));
    }

    @Test
    public void markActiveCancelsAPendingRelease() {
        String key = registerOk("u");
        registry.release(key);
        now += GRACE - 1;

        assertTrue(registry.markActive(key));
        now += GRACE;

        assertNotNull(registry.lookup(key));
    }

    @Test
    public void releaseAfterMarkActiveStartsANewGracePeriod() {
        String key = registerOk("u");
        registry.release(key);
        now += GRACE - 1;
        registry.markActive(key);
        now += 5_000L;
        registry.release(key);

        now += GRACE - 1;
        assertNotNull(registry.lookup(key));
        now += 1;

        assertNull(registry.lookup(key));
    }

    @Test
    public void markActiveAfterTheGracePeriodIsOverReturnsFalseAndDoesNotRevive() {
        String key = registerOk("u");
        registry.release(key);
        now += GRACE;

        assertFalse(registry.markActive(key));
        assertNull(registry.lookup(key));
    }

    @Test
    public void markActiveDoesNotExtendTheAbsoluteMaximum() {
        String key = registerOk("u");
        now += ABSOLUTE - 1;
        assertTrue(registry.markActive(key));
        now += 1;

        assertNull(registry.lookup(key));
    }

    @Test
    public void markActiveOfNullUnknownAndGarbageKeysReturnsFalse() {
        registerOk("u");

        assertFalse(registry.markActive(null));
        assertFalse(registry.markActive(""));
        assertFalse(registry.markActive("nope"));
    }

    // ---- remove / removeAll

    @Test
    public void removeTakesTheEntryOutAndReturnsTrue() {
        String key = registerOk("u");

        assertTrue(registry.remove(key));
        assertNull(registry.lookup(key));
        assertEquals(0, registry.size());
    }

    @Test
    public void removeClosesTheTargetExactlyOnce() {
        CloseCounter source = new CloseCounter();
        String key = registerOk(source);

        registry.remove(key);
        registry.remove(key);

        assertEquals(1, source.closes.get());
    }

    @Test
    public void secondRemoveReturnsFalse() {
        String key = registerOk("u");
        registry.remove(key);

        assertFalse(registry.remove(key));
    }

    @Test
    public void removeOfNullUnknownAndGarbageKeysReturnsFalseAndNeverThrows() {
        registerOk("u");

        assertFalse(registry.remove(null));
        assertFalse(registry.remove(""));
        assertFalse(registry.remove("nope"));
        assertEquals(1, registry.size());
    }

    @Test
    public void removeSurvivesATargetThatFailsOnClose() {
        CloseCounter source = new CloseCounter(true);
        String key = registerOk(source);

        assertTrue(registry.remove(key));

        assertNull(registry.lookup(key));
        assertEquals(1, source.closes.get());
    }

    @Test
    public void removeWorksForTargetsThatAreNotCloseable() {
        String key = registerOk("http://example.com/a.mp3");

        assertTrue(registry.remove(key));
    }

    @Test
    public void removeKeepsTheOtherEntries() {
        CloseCounter keep = new CloseCounter();
        String removed = registerOk(new CloseCounter());
        String kept = registerOk(keep);

        registry.remove(removed);

        assertNotNull(registry.lookup(kept));
        assertEquals(0, keep.closes.get());
    }

    @Test
    public void removeAllClosesEveryTargetOnceAndEmptiesTheRegistry() {
        CloseCounter a = new CloseCounter();
        CloseCounter b = new CloseCounter(true);
        String keyA = registerOk(a);
        String keyB = registerOk(b);

        registry.removeAll();
        registry.removeAll();

        assertEquals(1, a.closes.get());
        assertEquals(1, b.closes.get());
        assertNull(registry.lookup(keyA));
        assertNull(registry.lookup(keyB));
        assertEquals(0, registry.size());
    }

    // ---- expire (sweep)

    @Test
    public void expireWithNothingExpiredRemovesNothing() {
        CloseCounter source = new CloseCounter();
        String key = registerOk(source);
        now += ABSOLUTE - 1;

        assertEquals(0, registry.expire());

        assertNotNull(registry.lookup(key));
        assertEquals(0, source.closes.get());
    }

    @Test
    public void expireRemovesAndClosesExpiredEntriesAndReturnsTheCount() {
        CloseCounter old1 = new CloseCounter();
        CloseCounter old2 = new CloseCounter();
        registerOk(old1);
        registerOk(old2);
        now += ABSOLUTE;
        CloseCounter fresh = new CloseCounter();
        String freshKey = registerOk(fresh);

        assertEquals(2, registry.expire());

        assertEquals(1, old1.closes.get());
        assertEquals(1, old2.closes.get());
        assertEquals(0, fresh.closes.get());
        assertNotNull(registry.lookup(freshKey));
    }

    @Test
    public void expireSweepsReleasedEntriesWhoseGraceIsOver() {
        CloseCounter released = new CloseCounter();
        CloseCounter running = new CloseCounter();
        String releasedKey = registerOk(released);
        String runningKey = registerOk(running);
        registry.release(releasedKey);
        now += GRACE;

        assertEquals(1, registry.expire());

        assertEquals(1, released.closes.get());
        assertEquals(0, running.closes.get());
        assertNotNull(registry.lookup(runningKey));
    }

    @Test
    public void expireTwiceDoesNotCloseTwice() {
        CloseCounter source = new CloseCounter();
        registerOk(source);
        now += ABSOLUTE;

        assertEquals(1, registry.expire());
        assertEquals(0, registry.expire());

        assertEquals(1, source.closes.get());
    }

    @Test
    public void expireSurvivesATargetThatFailsOnClose() {
        CloseCounter bad = new CloseCounter(true);
        CloseCounter good = new CloseCounter();
        registerOk(bad);
        registerOk(good);
        now += ABSOLUTE;

        assertEquals(2, registry.expire());

        assertEquals(1, good.closes.get());
    }

    @Test
    public void sizeDoesNotCountExpiredEntries() {
        registerOk("a");
        registerOk("b");
        now += ABSOLUTE;
        registerOk("c");

        assertEquals(1, registry.size());
    }

    // ---- maximum of active entries

    @Test
    public void defaultRegistryHoldsEightEntriesAndRefusesTheNinth() {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < ShareRegistry.DEFAULT_MAX_ACTIVE; i++) {
            keys.add(registerOk("t" + i));
        }

        assertNull(registry.register(fileEntry("one too many")));

        assertEquals(8, registry.size());
        for (String key : keys) {
            assertNotNull(registry.lookup(key));
        }
    }

    @Test
    public void refusedRegistrationDoesNotCloseTheCallersTarget() {
        ShareRegistry small = new ShareRegistry(() -> now, random, 1);
        small.register(fileEntry(new CloseCounter()));
        CloseCounter refused = new CloseCounter();

        assertNull(small.register(fileEntry(refused)));

        assertEquals(0, refused.closes.get());
    }

    @Test
    public void customMaximumIsHonored() {
        ShareRegistry small = new ShareRegistry(() -> now, random, 2);

        assertNotNull(small.register(fileEntry("a")));
        assertNotNull(small.register(fileEntry("b")));
        assertNull(small.register(fileEntry("c")));
    }

    @Test
    public void removeFreesASlot() {
        ShareRegistry small = new ShareRegistry(() -> now, random, 1);
        String key = small.register(fileEntry("a"));
        assertNotNull(key);
        assertNull(small.register(fileEntry("b")));

        small.remove(key);

        assertNotNull(small.register(fileEntry("c")));
    }

    @Test
    public void expiryFreesASlotWithoutAnExplicitSweep() {
        ShareRegistry small = new ShareRegistry(() -> now, random, 1);
        CloseCounter old = new CloseCounter();
        assertNotNull(small.register(fileEntry(old)));
        now += ABSOLUTE;

        assertNotNull(small.register(fileEntry("b")));

        assertEquals(1, old.closes.get());
    }

    @Test
    public void releasedEntryStillOccupiesItsSlotDuringTheGracePeriod() {
        ShareRegistry small = new ShareRegistry(() -> now, random, 1);
        String key = small.register(fileEntry("a"));
        small.release(key);
        now += GRACE - 1;

        assertNull(small.register(fileEntry("b")));
        now += 1;
        assertNotNull(small.register(fileEntry("b")));
    }

    // ---- threads

    @Test
    public void concurrentRegistrationsLoseNoEntryAndNeverShareAKey() throws Exception {
        final int threads = 8;
        final int perThread = 50;
        final ShareRegistry shared = new ShareRegistry(() -> now, new SecureRandom()::nextBytes, 1000);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        List<Future<List<String>>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int id = t;
            futures.add(pool.submit(() -> {
                start.await();
                List<String> keys = new ArrayList<>();
                for (int i = 0; i < perThread; i++) {
                    keys.add(shared.register(fileEntry("t" + id + "-" + i)));
                }
                return keys;
            }));
        }
        start.countDown();
        Set<String> all = new HashSet<>();
        try {
            for (Future<List<String>> future : futures) {
                for (String key : future.get(30, TimeUnit.SECONDS)) {
                    assertNotNull(key);
                    all.add(key);
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(threads * perThread, all.size());
        assertEquals(threads * perThread, shared.size());
        for (String key : all) {
            assertNotNull(shared.lookup(key));
        }
    }

    @Test
    public void concurrentRegistrationsNeverExceedTheMaximum() throws Exception {
        final int threads = 16;
        final ShareRegistry shared = new ShareRegistry(() -> now, new SecureRandom()::nextBytes, 8);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int id = t;
            futures.add(pool.submit(() -> {
                start.await();
                return shared.register(fileEntry("t" + id));
            }));
        }
        start.countDown();
        int accepted = 0;
        try {
            for (Future<String> future : futures) {
                if (future.get(30, TimeUnit.SECONDS) != null) {
                    accepted++;
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(8, accepted);
        assertEquals(8, shared.size());
    }

    @Test
    public void concurrentRemovesOfTheSameKeyCloseTheTargetOnce() throws Exception {
        final int threads = 8;
        CloseCounter source = new CloseCounter();
        final String key = registerOk(source);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                return registry.remove(key);
            }));
        }
        start.countDown();
        int removed = 0;
        try {
            for (Future<Boolean> future : futures) {
                if (future.get(30, TimeUnit.SECONDS)) {
                    removed++;
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, removed);
        assertEquals(1, source.closes.get());
    }

    @Test
    public void concurrentExpireAndLookupCloseAnExpiredTargetOnce() throws Exception {
        final int threads = 8;
        CloseCounter source = new CloseCounter();
        final String key = registerOk(source);
        now += ABSOLUTE;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final boolean sweep = t % 2 == 0;
            futures.add(pool.submit(() -> {
                start.await();
                if (sweep) {
                    registry.expire();
                } else {
                    registry.lookup(key);
                }
                return null;
            }));
        }
        start.countDown();
        try {
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, source.closes.get());
        assertEquals(0, registry.size());
    }

    @Test
    public void kindEnumHasTheTwoDocumentedValues() {
        assertEquals(Arrays.asList(ShareRegistry.Kind.HTTP_LINK, ShareRegistry.Kind.LOCAL_FILE),
                Arrays.asList(ShareRegistry.Kind.values()));
    }

    // ---- isWellFormedKey (the handler's format check before the lookup)

    private static String repeated(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    @Test
    public void keyTextLengthIs22() {
        assertEquals(22, ShareRegistry.KEY_TEXT_LENGTH);
    }

    @Test
    public void aKeyOf22UrlSafeBase64CharactersIsWellFormed() {
        assertTrue(ShareRegistry.isWellFormedKey(repeated('A', 22)));
        assertTrue(ShareRegistry.isWellFormedKey(repeated('z', 22)));
        assertTrue(ShareRegistry.isWellFormedKey(repeated('7', 22)));
        assertTrue(ShareRegistry.isWellFormedKey("abcdefghijklmnopqrstuv"));
        assertTrue(ShareRegistry.isWellFormedKey("ABCDEFGHIJKLMNOPQRSTUV"));
        assertTrue(ShareRegistry.isWellFormedKey("0123456789-_ABCDEFGHIJ"));
    }

    @Test
    public void dashAndUnderscoreAreAllowed() {
        assertTrue(ShareRegistry.isWellFormedKey(repeated('-', 22)));
        assertTrue(ShareRegistry.isWellFormedKey(repeated('_', 22)));
        assertTrue(ShareRegistry.isWellFormedKey("-_-_-_-_-_-_-_-_-_-_-_"));
    }

    @Test
    public void everyKeyTheRegistryHandsOutIsWellFormed() {
        for (int i = 0; i < 5; i++) {
            String key = registry.register(fileEntry(new CloseCounter()));

            assertNotNull(key);
            assertTrue(key, ShareRegistry.isWellFormedKey(key));
            assertEquals(ShareRegistry.KEY_TEXT_LENGTH, key.length());
            registry.remove(key);
        }
    }

    @Test
    public void nullAndEmptyAreNotWellFormed() {
        assertFalse(ShareRegistry.isWellFormedKey(null));
        assertFalse(ShareRegistry.isWellFormedKey(""));
    }

    @Test
    public void twentyOneAndTwentyThreeCharactersAreNotWellFormed() {
        assertFalse(ShareRegistry.isWellFormedKey(repeated('A', 21)));
        assertFalse(ShareRegistry.isWellFormedKey(repeated('A', 23)));
        assertFalse(ShareRegistry.isWellFormedKey(repeated('A', 1)));
        assertFalse(ShareRegistry.isWellFormedKey(repeated('A', 43)));
        assertFalse(ShareRegistry.isWellFormedKey(repeated('A', 4096)));
    }

    @Test
    public void aValidLengthWithAForeignCharacterIsNotWellFormed() {
        String valid = repeated('A', 21);

        assertFalse(ShareRegistry.isWellFormedKey(valid + " "));
        assertFalse(ShareRegistry.isWellFormedKey(valid + "/"));
        assertFalse(ShareRegistry.isWellFormedKey(valid + "."));
        assertFalse(ShareRegistry.isWellFormedKey(valid + "%"));
        assertFalse(ShareRegistry.isWellFormedKey(valid + "+"));
        assertFalse(ShareRegistry.isWellFormedKey(valid + "="));
        assertFalse(ShareRegistry.isWellFormedKey(valid + "\\"));
        assertFalse(ShareRegistry.isWellFormedKey(valid + "?"));
        assertFalse(ShareRegistry.isWellFormedKey(valid + "*"));
        assertFalse(ShareRegistry.isWellFormedKey(" " + valid));
        assertFalse(ShareRegistry.isWellFormedKey("/" + valid));
        assertFalse(ShareRegistry.isWellFormedKey("A.AAAAAAAAAAAAAAAAAAAA"));
    }

    @Test
    public void linebreaksAndControlCharactersAreNotWellFormed() {
        String valid = repeated('A', 21);

        assertFalse(ShareRegistry.isWellFormedKey(valid + "\n"));
        assertFalse(ShareRegistry.isWellFormedKey(valid + "\r"));
        assertFalse(ShareRegistry.isWellFormedKey(valid + "\u0000"));
        assertFalse(ShareRegistry.isWellFormedKey("\n" + valid));
        // a valid key plus a trailing newline is 23 characters; also 22 characters with a newline
        assertFalse(ShareRegistry.isWellFormedKey(repeated('A', 20) + "\nA"));
    }

    @Test
    public void unicodeLettersAndDigitsAreNotWellFormed() {
        String valid = repeated('A', 21);

        assertFalse(ShareRegistry.isWellFormedKey(valid + "\u00e9"));        // e acute
        assertFalse(ShareRegistry.isWellFormedKey(valid + "\uff21"));        // fullwidth A
        assertFalse(ShareRegistry.isWellFormedKey(valid + "\u0661"));        // arabic-indic digit one
        assertFalse(ShareRegistry.isWellFormedKey(valid + "\u0410"));        // cyrillic A
        assertFalse(ShareRegistry.isWellFormedKey(valid + "\u212a"));        // kelvin sign
    }

    @Test
    public void pathTraversalAndPercentEncodingAreNotWellFormed() {
        assertFalse(ShareRegistry.isWellFormedKey("../x"));
        assertFalse(ShareRegistry.isWellFormedKey("../../../../etc/passwd"));
        assertFalse(ShareRegistry.isWellFormedKey("..%2F..%2F..%2Fetc%2Fpasswd"));
        assertFalse(ShareRegistry.isWellFormedKey(repeated('.', 22)));
        assertFalse(ShareRegistry.isWellFormedKey("AAAAAAAAAA%2FAAAAAAAAAA"));
        assertFalse(ShareRegistry.isWellFormedKey("AAAAAAAAAAAAAAAAAAAAA%"));
    }

    @Test
    public void paddedAndStandardBase64FormsAreNotWellFormed() {
        // 16 bytes as padded standard base64 is 24 characters, ending in "=="
        assertFalse(ShareRegistry.isWellFormedKey("AAAAAAAAAAAAAAAAAAAAAA=="));
        assertFalse(ShareRegistry.isWellFormedKey("AAAAAAAAAAAAAAAAAAAAA+"));
        assertFalse(ShareRegistry.isWellFormedKey("AAAAAAAAAAAAAAAAAAAAA/"));
    }

    // ---- retired keys are not handed out again (review: Group 12, Warning 3)

    @Test
    public void aRetiredKeyIsNotHandedOutAgainAfterRemove() {
        ShareRegistry repeating = new ShareRegistry(() -> now, bytes -> Arrays.fill(bytes, (byte) 7));
        String first = repeating.register(fileEntry("a"));
        assertNotNull(first);
        assertTrue(repeating.remove(first));

        String second = repeating.register(fileEntry("b"));

        // the only key this broken source can produce was used and retired: no reuse, no new entry
        assertNull(second);
        assertEquals(0, repeating.size());
        assertNull(repeating.lookup(first));
    }

    @Test
    public void aRetiredKeyIsNotHandedOutAgainAfterExpiry() {
        ShareRegistry repeating = new ShareRegistry(() -> now, bytes -> Arrays.fill(bytes, (byte) 7));
        String first = repeating.register(fileEntry("a", GRACE, ABSOLUTE));
        assertNotNull(first);
        now += ABSOLUTE; // absolute maximum reached
        assertNull(repeating.lookup(first));

        String second = repeating.register(fileEntry("b"));

        assertNull(second);
        assertEquals(0, repeating.size());
    }

    // ---- default registry and grace period (review: suggestions)

    @Test
    public void twoDefaultRegistriesGiveDifferentKeys() {
        String a = ShareRegistry.createDefault().register(fileEntry("a"));
        String b = ShareRegistry.createDefault().register(fileEntry("b"));

        assertNotNull(a);
        assertNotNull(b);
        assertNotEquals(a, b);
        assertTrue(ShareRegistry.isWellFormedKey(a));
    }

    @Test
    public void aLookupDuringTheGracePeriodDoesNotExtendIt() {
        String key = registerOk("a");
        registry.release(key);
        now += GRACE - 1;
        assertNotNull(registry.lookup(key)); // still inside the grace period

        now += 1; // grace over; the lookup above must not have restarted it

        assertNull(registry.lookup(key));
    }
}
