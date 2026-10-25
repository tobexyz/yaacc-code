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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.After;
import org.junit.Test;

/**
 * {@link BoundedCall} with a {@link ProviderCallGate}: one hostile or hanging provider can never
 * occupy more than one pool thread, and it cannot stop a share from another provider.
 */
public class BoundedCallGateTest {

    private static final long STUCK = 600_000L;
    private static final long WAIT = 100L;
    private static final long LONG = 5_000L;

    private final AtomicLong clock = new AtomicLong(0L);
    private final CountDownLatch release = new CountDownLatch(1);
    private final ProviderCallGate gate = new ProviderCallGate(clock::get, 3, STUCK);
    private final BoundedCall calls = new BoundedCall(4, "test-gated-call", gate);

    @After
    public void releaseHungCalls() {
        release.countDown();
    }

    /** Blocks like a binder call: interrupts do not end it, only the latch. */
    private String hang() {
        while (true) {
            try {
                release.await();
                return "late";
            } catch (InterruptedException ignored) {
                // keep waiting
            }
        }
    }

    @Test
    public void aGatedCallReturnsItsResultAndFreesTheProvider() {
        assertEquals("answer", calls.call("pkg:a", () -> "answer", LONG, null));
        assertEquals(0, gate.trackedCount());
        assertEquals("again", calls.call("pkg:a", () -> "again", LONG, null));
    }

    @Test
    public void aFailingCallFreesTheProviderToo() {
        assertNull(calls.<String>call("pkg:a", () -> {
            throw new IllegalStateException("provider text");
        }, LONG, null));

        assertEquals(0, gate.trackedCount());
    }

    @Test
    public void aHungProviderDoesNotStopAShareFromAnotherProvider() {
        assertNull(calls.call("pkg:a", this::hang, WAIT, null));

        assertEquals("b answers", calls.call("pkg:b", () -> "b answers", LONG, null));
    }

    @Test
    public void aSecondShareFromAStuckProviderIsDroppedWithoutAProviderCall() {
        assertNull(calls.call("pkg:a", this::hang, WAIT, null));
        AtomicInteger asked = new AtomicInteger();

        String second = calls.call("pkg:a", () -> {
            asked.incrementAndGet();
            return "x";
        }, LONG, null);

        assertNull(second);
        assertEquals("no provider call, no thread", 0, asked.get());
    }

    @Test
    public void aHungProviderNeverHoldsMoreThanOneThreadHoweverOftenItIsAsked() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger running = new AtomicInteger();
        for (int i = 0; i < 20; i++) {
            calls.call("pkg:a", () -> {
                running.incrementAndGet();
                started.countDown();
                return hang();
            }, 20L, null);
        }

        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertEquals("one call reached the provider, the other 19 were dropped", 1, running.get());
        // and the pool still has its other threads for other providers
        assertEquals("ok", calls.call("pkg:b", () -> "ok", LONG, null));
        assertEquals("ok", calls.call("pkg:c", () -> "ok", LONG, null));
    }

    @Test
    public void theStuckMarkerExpiresAndTheProviderIsAskedAgain() {
        assertNull(calls.call("pkg:a", this::hang, WAIT, null));
        assertNull(calls.call("pkg:a", () -> "dropped", LONG, null));

        clock.addAndGet(STUCK);

        assertEquals("asked again", calls.call("pkg:a", () -> "asked again", LONG, null));
    }

    @Test
    public void whenTheHungCallReturnsTheProviderIsFreeAtOnce() throws Exception {
        CountDownLatch disposed = new CountDownLatch(1);
        assertNull(calls.call("pkg:a", this::hang, WAIT, late -> disposed.countDown()));

        release.countDown();
        assertTrue(disposed.await(5, TimeUnit.SECONDS));

        assertEquals("fine again", calls.call("pkg:a", () -> "fine again", LONG, null));
    }

    @Test
    public void aCallRefusedByAFullPoolGivesItsPlaceBack() throws Exception {
        BoundedCall oneThread = new BoundedCall(1, "test-gated-one", gate);
        CountDownLatch started = new CountDownLatch(1);
        Thread hanging = new Thread(() -> oneThread.call("pkg:a", () -> {
            started.countDown();
            return hang();
        }, LONG, null));
        hanging.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));

        assertNull("the pool is busy", oneThread.call("pkg:b", () -> "never", LONG, null));

        assertEquals("the refused call did not stay tracked", 1, gate.trackedCount());
        release.countDown();
        hanging.join(5_000L);
    }

    @Test
    public void aMissingKeyIsDroppedWithoutACall() {
        AtomicInteger asked = new AtomicInteger();

        assertNull(calls.call((String) null, () -> {
            asked.incrementAndGet();
            return "x";
        }, LONG, null));
        assertEquals(0, asked.get());
    }

    @Test
    public void theUngatedCallStillWorksWithoutAKey() {
        assertEquals("plain", calls.call(() -> "plain", LONG, null));
    }
}
