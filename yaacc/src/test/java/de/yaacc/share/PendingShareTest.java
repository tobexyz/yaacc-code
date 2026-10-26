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
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

/**
 * Unit tests for {@link PendingShare}: at most one share waits for the user's receiver pick; it
 * holds only text, expires, is handed out once and is bound to a token. Plain JVM with an
 * injected clock and random source.
 */
public class PendingShareTest {

    private static final String URI = "content://com.example.provider/files/song.mp3";
    private static final long TIMEOUT = 120_000L;

    private final AtomicLong clock = new AtomicLong(1_000L);
    private final Random random = new Random(42L);

    private PendingShare newPending() {
        return new PendingShare(clock::get, random::nextBytes, TIMEOUT);
    }

    private static String offer(PendingShare pending, String uri) {
        return pending.offer(uri, "song.mp3", "audio/mpeg");
    }

    // ---- defaults

    @Test
    public void theDefaultTimeoutIsTwoMinutes() {
        assertEquals(120_000L, PendingShare.DEFAULT_TIMEOUT_MILLIS);
        assertEquals(ShareLimits.PENDING_SHARE_TIMEOUT_MILLIS, PendingShare.DEFAULT_TIMEOUT_MILLIS);
    }

    @Test
    public void theDefaultInstanceWorks() {
        PendingShare pending = PendingShare.createDefault();

        String token = offer(pending, URI);

        assertNotNull(token);
        assertTrue(pending.isPending());
        assertEquals(URI, pending.take(token).getUri());
    }

    // ---- offer / single pending

    @Test
    public void nothingIsPendingAtFirst() {
        assertFalse(newPending().isPending());
    }

    @Test
    public void offerReturnsA128BitHexToken() {
        PendingShare pending = newPending();

        String token = offer(pending, URI);

        assertNotNull(token);
        assertEquals(32, token.length());
        assertTrue(token.matches("[0-9a-f]{32}"));
        assertTrue(pending.isPending());
    }

    @Test
    public void theTokenComesFromTheInjectedRandomSource() {
        PendingShare pending = new PendingShare(clock::get, bytes -> {
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) (0xA0 + i);
            }
        }, TIMEOUT);

        assertEquals("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf", offer(pending, URI));
    }

    @Test
    public void everyOfferGetsANewToken() {
        PendingShare pending = newPending();
        Set<String> tokens = new HashSet<>();

        for (int i = 0; i < 20; i++) {
            String token = offer(pending, URI);
            assertNotNull(token);
            tokens.add(token);
            assertNotNull(pending.take(token));
        }

        assertEquals(20, tokens.size());
    }

    @Test
    public void aSecondOfferWhileOneIsPendingIsDroppedAndChangesNothing() {
        PendingShare pending = newPending();
        String first = pending.offer(URI, "first.mp3", "audio/mpeg");

        String second = pending.offer("content://other.provider/2", "second.mp4", "video/mp4");

        assertNull(second);
        assertTrue(pending.isPending());
        PendingShare.Data data = pending.take(first);
        assertNotNull(data);
        assertEquals(URI, data.getUri());
        assertEquals("first.mp3", data.getDisplayName());
        assertEquals("audio/mpeg", data.getMimeType());
    }

    @Test
    public void aDroppedOfferDoesNotExtendTheTimeout() {
        PendingShare pending = newPending();
        String first = offer(pending, URI);
        clock.addAndGet(TIMEOUT - 1);

        assertNull(offer(pending, "content://other.provider/2"));
        clock.addAndGet(1);

        assertFalse(pending.isPending());
        assertNull(pending.take(first));
    }

    @Test
    public void aRejectedOfferIsNullAndChangesNothing() {
        PendingShare pending = newPending();

        assertNull(pending.offer(null, "x", "audio/mpeg"));
        assertNull(pending.offer("", "x", "audio/mpeg"));
        assertFalse(pending.isPending());

        String token = offer(pending, URI);
        assertNull(pending.offer(null, "x", "audio/mpeg"));
        assertNotNull(pending.take(token));
    }

    // ---- held data

    @Test
    public void theDataIsTheUriTheCleanedNameAndTheDeclaredType() {
        PendingShare pending = newPending();
        String token = pending.offer(URI, "  my‮ song\n.mp3 ", "audio/mpeg");

        PendingShare.Data data = pending.take(token);

        assertEquals(URI, data.getUri());
        assertEquals("my song .mp3", data.getDisplayName());
        assertEquals("audio/mpeg", data.getMimeType());
    }

    @Test
    public void nullNameAndTypeBecomeEmptyTexts() {
        PendingShare pending = newPending();
        String token = pending.offer(URI, null, null);

        PendingShare.Data data = pending.take(token);

        assertEquals("", data.getDisplayName());
        assertEquals("", data.getMimeType());
    }

    @Test
    public void theDataObjectDoesNotShowTheUriInToString() {
        PendingShare pending = newPending();
        PendingShare.Data data = pending.take(pending.offer(URI, "song.mp3", "audio/mpeg"));

        assertFalse(data.toString().contains("provider"));
        assertFalse(data.toString().contains("song"));
    }

    // ---- kind: a file or a link, decided by the offerer and never re-derived from the text

    @Test
    public void anOfferWithoutAKindIsAFile() {
        PendingShare pending = newPending();

        assertEquals(PendingShare.Kind.FILE, pending.take(pending.offer(URI, "song.mp3", "audio/mpeg")).getKind());
    }

    @Test
    public void aLinkOfferKeepsItsKind() {
        PendingShare pending = newPending();

        PendingShare.Data data = pending.take(pending.offer(PendingShare.Kind.LINK, "https://example.org/a.mp3", "", ""));

        assertEquals(PendingShare.Kind.LINK, data.getKind());
    }

    @Test
    public void theKindIsNotDerivedFromTheText() {
        PendingShare pending = newPending();

        // a "file" whose text looks like a link stays a file, and the other way round
        assertEquals(PendingShare.Kind.FILE,
                pending.take(pending.offer(PendingShare.Kind.FILE, "https://example.org/a.mp3", "x", "audio/mpeg")).getKind());
        assertEquals(PendingShare.Kind.LINK,
                pending.take(pending.offer(PendingShare.Kind.LINK, "content://media/1", "", "")).getKind());
    }

    @Test
    public void aNullKindIsRefused() {
        PendingShare pending = newPending();

        assertNull(pending.offer(null, URI, "x", "audio/mpeg"));
        assertFalse(pending.isPending());
    }

    // ---- take: once only, token bound

    @Test
    public void takeReturnsTheDataOnceAndClears() {
        PendingShare pending = newPending();
        String token = offer(pending, URI);

        assertNotNull(pending.take(token));
        assertNull(pending.take(token));
        assertFalse(pending.isPending());
    }

    @Test
    public void aWrongTokenGetsNothingAndKeepsTheShare() {
        PendingShare pending = newPending();
        String token = offer(pending, URI);

        assertNull(pending.take("00000000000000000000000000000000"));
        assertNull(pending.take(token + "0"));
        assertNull(pending.take(token.substring(1)));
        assertNull(pending.take(token.toUpperCase()));
        assertNull(pending.take(""));
        assertNull(pending.take(null));

        assertTrue(pending.isPending());
        assertNotNull(pending.take(token));
    }

    @Test
    public void aTokenOfAnEarlierShareDoesNotFitALaterOne() {
        PendingShare pending = newPending();
        String old = offer(pending, URI);
        assertTrue(pending.cancel(old));
        String fresh = offer(pending, "content://other.provider/2");

        assertNotEquals(old, fresh);
        assertNull(pending.take(old));
        assertTrue(pending.isPending());
        assertEquals("content://other.provider/2", pending.take(fresh).getUri());
    }

    @Test
    public void takeWithNothingPendingIsNull() {
        PendingShare pending = newPending();

        assertNull(pending.take("00000000000000000000000000000000"));
        assertNull(pending.take(null));
    }

    // ---- peek (read without consuming, for the picker title)

    @Test
    public void peekShowsTheDataWithoutConsumingIt() {
        PendingShare pending = newPending();
        String token = offer(pending, URI);

        assertEquals("song.mp3", pending.peek(token).getDisplayName());
        assertEquals("song.mp3", pending.peek(token).getDisplayName());
        assertNull(pending.peek("00000000000000000000000000000000"));
        assertNull(pending.peek(null));
        assertNotNull(pending.take(token));
        assertNull(pending.peek(token));
    }

    @Test
    public void peekOfAnExpiredShareIsNull() {
        PendingShare pending = newPending();
        String token = offer(pending, URI);
        clock.addAndGet(TIMEOUT);

        assertNull(pending.peek(token));
    }

    // ---- cancel

    @Test
    public void cancelReleasesTheShare() {
        PendingShare pending = newPending();
        String token = offer(pending, URI);

        assertTrue(pending.cancel(token));

        assertFalse(pending.isPending());
        assertNull(pending.take(token));
    }

    @Test
    public void cancelWithAWrongTokenChangesNothing() {
        PendingShare pending = newPending();
        String token = offer(pending, URI);

        assertFalse(pending.cancel("00000000000000000000000000000000"));
        assertFalse(pending.cancel(null));
        assertFalse(pending.cancel(""));

        assertTrue(pending.isPending());
        assertNotNull(pending.take(token));
    }

    @Test
    public void cancelTwiceIsHarmless() {
        PendingShare pending = newPending();
        String token = offer(pending, URI);

        assertTrue(pending.cancel(token));
        assertFalse(pending.cancel(token));
        assertFalse(pending.isPending());
    }

    @Test
    public void cancelAfterTakeDoesNotRemoveANewShare() {
        PendingShare pending = newPending();
        String first = offer(pending, URI);
        assertNotNull(pending.take(first));
        String second = offer(pending, "content://other.provider/2");

        assertFalse(pending.cancel(first));

        assertTrue(pending.isPending());
        assertNotNull(pending.take(second));
    }

    // ---- a new share may come after take, cancel and expiry

    @Test
    public void aNewShareMayBeOfferedAfterTake() {
        PendingShare pending = newPending();
        assertNotNull(pending.take(offer(pending, URI)));

        assertNotNull(offer(pending, "content://other.provider/2"));
    }

    @Test
    public void aNewShareMayBeOfferedAfterCancel() {
        PendingShare pending = newPending();
        assertTrue(pending.cancel(offer(pending, URI)));

        assertNotNull(offer(pending, "content://other.provider/2"));
    }

    // ---- timeout, including the edge

    @Test
    public void theShareLivesUntilJustBeforeTheTimeout() {
        PendingShare pending = newPending();
        String token = offer(pending, URI);
        clock.addAndGet(TIMEOUT - 1);

        assertTrue(pending.isPending());
        assertNotNull(pending.take(token));
    }

    @Test
    public void atExactlyTheTimeoutTheShareIsGone() {
        PendingShare pending = newPending();
        String token = offer(pending, URI);
        clock.addAndGet(TIMEOUT);

        assertFalse(pending.isPending());
        assertNull(pending.take(token));
    }

    @Test
    public void afterTheTimeoutTheShareIsGoneAndANewOneMayBeOffered() {
        PendingShare pending = newPending();
        String old = offer(pending, URI);
        clock.addAndGet(TIMEOUT + 5_000L);

        assertFalse(pending.isPending());
        String fresh = offer(pending, "content://other.provider/2");
        assertNotNull(fresh);
        assertNull(pending.take(old));
        assertEquals("content://other.provider/2", pending.take(fresh).getUri());
    }

    @Test
    public void anOfferJustBeforeTheTimeoutIsStillDropped() {
        PendingShare pending = newPending();
        offer(pending, URI);
        clock.addAndGet(TIMEOUT - 1);

        assertNull(offer(pending, "content://other.provider/2"));
    }

    @Test
    public void anOfferExactlyAtTheTimeoutIsAccepted() {
        PendingShare pending = newPending();
        offer(pending, URI);
        clock.addAndGet(TIMEOUT);

        assertNotNull(offer(pending, "content://other.provider/2"));
    }

    @Test
    public void theTimeoutCountsFromTheOfferNotFromTheLastCall() {
        PendingShare pending = newPending();
        String token = offer(pending, URI);
        clock.addAndGet(TIMEOUT / 2);
        assertTrue(pending.isPending());
        assertNotNull(pending.peek(token));
        clock.addAndGet(TIMEOUT / 2);

        assertFalse(pending.isPending());
    }

    @Test
    public void aClockThatRunsBackwardsExpiresTheShare() {
        PendingShare pending = newPending();
        String token = offer(pending, URI);
        clock.addAndGet(-10L);

        assertFalse(pending.isPending());
        assertNull(pending.take(token));
    }

    @Test
    public void aCustomTimeoutIsHonoured() {
        PendingShare pending = new PendingShare(clock::get, random::nextBytes, 500L);
        String token = offer(pending, URI);
        clock.addAndGet(499L);
        assertTrue(pending.isPending());
        clock.addAndGet(1L);

        assertFalse(pending.isPending());
        assertNull(pending.take(token));
    }

    @Test(expected = IllegalArgumentException.class)
    public void aNonPositiveTimeoutIsRefused() {
        new PendingShare(clock::get, random::nextBytes, 0L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void aMissingClockIsRefused() {
        new PendingShare(null, random::nextBytes, TIMEOUT);
    }

    @Test(expected = IllegalArgumentException.class)
    public void aMissingRandomSourceIsRefused() {
        new PendingShare(clock::get, null, TIMEOUT);
    }

    // ---- threads

    @Test
    public void ofManyConcurrentOffersExactlyOneWins() throws Exception {
        final int threads = 16;
        PendingShare pending = newPending();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final String uri = "content://provider/" + i;
                results.add(executor.submit((Callable<String>) () -> {
                    ready.countDown();
                    go.await();
                    return pending.offer(uri, "n" + uri, "audio/mpeg");
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();

            String winnerToken = null;
            int winners = 0;
            for (Future<String> result : results) {
                String token = result.get(10, TimeUnit.SECONDS);
                if (token != null) {
                    winners++;
                    winnerToken = token;
                }
            }

            assertEquals(1, winners);
            assertTrue(pending.isPending());
            assertTrue(pending.take(winnerToken).getUri().startsWith("content://provider/"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void ofManyConcurrentTakesExactlyOneGetsTheData() throws Exception {
        final int threads = 16;
        PendingShare pending = newPending();
        String token = offer(pending, URI);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<PendingShare.Data>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(executor.submit((Callable<PendingShare.Data>) () -> {
                    ready.countDown();
                    go.await();
                    return pending.take(token);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();

            int got = 0;
            for (Future<PendingShare.Data> result : results) {
                if (result.get(10, TimeUnit.SECONDS) != null) {
                    got++;
                }
            }

            assertEquals(1, got);
            assertFalse(pending.isPending());
        } finally {
            executor.shutdownNow();
        }
    }
}
