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
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link PendingPick}: the one receiver pick that can be open in the new layout and
 * what the answer of the sheet does with it. A confirmed pick runs its action once, a cancel (or an
 * abandon) only releases, a confirm without a receiver changes nothing, a second pick never
 * replaces the open one, and a stale or repeated answer is ignored. Plain JVM.
 */
public class PendingPickTest {

    /** Counts what the pick told its owner. */
    private static final class Recorder implements PendingPick.Callbacks {
        final List<Set<String>> confirmed = new ArrayList<>();
        final AtomicInteger cancelled = new AtomicInteger();

        @Override
        public void onConfirmed(Set<String> receiverIds) {
            confirmed.add(receiverIds);
        }

        @Override
        public void onCancelled() {
            cancelled.incrementAndGet();
        }
    }

    private int counter;
    private PendingPick pick;

    @Before
    public void setUp() {
        counter = 0;
        pick = new PendingPick(() -> "pick-" + (++counter));
    }

    private static Set<String> ids(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    // ---- open

    @Test
    public void openReturnsAnIdAndTheNewPickIsOpen() {
        String id = pick.open(new Recorder());

        assertNotNull(id);
        assertTrue(pick.isOpen(id));
    }

    @Test
    public void aSecondPickWhileOneIsOpenIsDroppedAndNeverReplacesIt() {
        Recorder first = new Recorder();
        Recorder second = new Recorder();
        String firstId = pick.open(first);

        String secondId = pick.open(second);

        assertNull(secondId);
        assertTrue(pick.isOpen(firstId));
        pick.resolve(firstId, true, ids("a"));
        assertEquals(1, first.confirmed.size());
        assertEquals(0, second.confirmed.size());
        assertEquals(0, second.cancelled.get());
    }

    @Test
    public void openWithoutCallbacksIsRefused() {
        assertNull(pick.open(null));
        assertNotNull(pick.open(new Recorder()));
    }

    @Test
    public void aBlankIdFromTheSupplierIsNotUsed() {
        PendingPick blank = new PendingPick(() -> "");

        assertNull(blank.open(new Recorder()));
        assertNull(new PendingPick(() -> null).open(new Recorder()));
    }

    @Test
    public void theDefaultPickHandsOutDifferentIds() {
        PendingPick real = PendingPick.createDefault();
        String first = real.open(new Recorder());
        real.abandon();
        String second = real.open(new Recorder());

        assertNotNull(first);
        assertNotNull(second);
        assertNotEquals(first, second);
    }

    // ---- confirm

    @Test
    public void confirmWithReceiversRunsTheActionOnceWithExactlyThoseIds() {
        Recorder recorder = new Recorder();
        String id = pick.open(recorder);

        PendingPick.Outcome outcome = pick.resolve(id, true, ids("a", "b"));

        assertEquals(PendingPick.Outcome.STARTED, outcome);
        assertEquals(1, recorder.confirmed.size());
        assertEquals(ids("a", "b"), recorder.confirmed.get(0));
        assertEquals(0, recorder.cancelled.get());
    }

    @Test
    public void thePickIsClosedAfterTheConfirm() {
        String id = pick.open(new Recorder());

        pick.resolve(id, true, ids("a"));

        assertFalse(pick.isOpen(id));
    }

    @Test
    public void theIdsHandedOutAreAnUnmodifiableCopy() {
        Recorder recorder = new Recorder();
        String id = pick.open(recorder);
        Set<String> given = ids("a");

        pick.resolve(id, true, given);
        given.add("later");

        Set<String> received = recorder.confirmed.get(0);
        assertEquals(ids("a"), received);
        try {
            received.add("x");
            fail("expected an unmodifiable set");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }

    @Test
    public void nullAndEmptyIdsInsideTheSetAreDropped() {
        Recorder recorder = new Recorder();
        String id = pick.open(recorder);
        Set<String> given = new LinkedHashSet<>();
        given.add(null);
        given.add("");
        given.add("a");

        pick.resolve(id, true, given);

        assertEquals(ids("a"), recorder.confirmed.get(0));
    }

    @Test
    public void confirmWithoutAReceiverKeepsThePickOpenAndRunsNothing() {
        Recorder recorder = new Recorder();
        String id = pick.open(recorder);

        PendingPick.Outcome outcome = pick.resolve(id, true, Collections.<String>emptySet());

        assertEquals(PendingPick.Outcome.KEPT_OPEN, outcome);
        assertTrue(pick.isOpen(id));
        assertEquals(0, recorder.confirmed.size());
        assertEquals(0, recorder.cancelled.get());
    }

    @Test
    public void confirmWithNullIdsKeepsThePickOpen() {
        String id = pick.open(new Recorder());

        assertEquals(PendingPick.Outcome.KEPT_OPEN, pick.resolve(id, true, null));
        assertTrue(pick.isOpen(id));
    }

    @Test
    public void aKeptOpenPickCanStillBeConfirmedLater() {
        Recorder recorder = new Recorder();
        String id = pick.open(recorder);
        pick.resolve(id, true, Collections.<String>emptySet());

        PendingPick.Outcome outcome = pick.resolve(id, true, ids("a"));

        assertEquals(PendingPick.Outcome.STARTED, outcome);
        assertEquals(1, recorder.confirmed.size());
    }

    // ---- cancel

    @Test
    public void cancelOnlyReleasesAndNeverHandsOutTheTickedIds() {
        Recorder recorder = new Recorder();
        String id = pick.open(recorder);

        PendingPick.Outcome outcome = pick.resolve(id, false, ids("a", "b"));

        assertEquals(PendingPick.Outcome.CANCELLED, outcome);
        assertEquals(0, recorder.confirmed.size());
        assertEquals(1, recorder.cancelled.get());
        assertFalse(pick.isOpen(id));
    }

    @Test
    public void abandonCancelsTheOpenPickOnce() {
        Recorder recorder = new Recorder();
        String id = pick.open(recorder);

        assertEquals(PendingPick.Outcome.CANCELLED, pick.abandon());
        assertEquals(PendingPick.Outcome.IGNORED, pick.abandon());

        assertEquals(1, recorder.cancelled.get());
        assertFalse(pick.isOpen(id));
    }

    @Test
    public void abandonWithoutAnOpenPickChangesNothing() {
        assertEquals(PendingPick.Outcome.IGNORED, pick.abandon());
    }

    @Test
    public void anAnswerAfterAnAbandonIsIgnoredSoNothingIsDeliveredTwice() {
        Recorder recorder = new Recorder();
        String id = pick.open(recorder);
        pick.abandon();

        assertEquals(PendingPick.Outcome.IGNORED, pick.resolve(id, true, ids("a")));
        assertEquals(PendingPick.Outcome.IGNORED, pick.resolve(id, false, ids()));

        assertEquals(0, recorder.confirmed.size());
        assertEquals(1, recorder.cancelled.get());
    }

    // ---- stale, wrong and repeated answers

    @Test
    public void theSameAnswerTwiceRunsTheActionOnlyOnce() {
        Recorder recorder = new Recorder();
        String id = pick.open(recorder);

        pick.resolve(id, true, ids("a"));
        PendingPick.Outcome second = pick.resolve(id, true, ids("a"));

        assertEquals(PendingPick.Outcome.IGNORED, second);
        assertEquals(1, recorder.confirmed.size());
    }

    @Test
    public void aCancelAfterAConfirmIsIgnored() {
        Recorder recorder = new Recorder();
        String id = pick.open(recorder);
        pick.resolve(id, true, ids("a"));

        assertEquals(PendingPick.Outcome.IGNORED, pick.resolve(id, false, ids()));

        assertEquals(0, recorder.cancelled.get());
    }

    @Test
    public void aWrongIdChangesNothing() {
        Recorder recorder = new Recorder();
        String id = pick.open(recorder);

        assertEquals(PendingPick.Outcome.IGNORED, pick.resolve("pick-other", true, ids("a")));
        assertEquals(PendingPick.Outcome.IGNORED, pick.resolve(null, true, ids("a")));
        assertEquals(PendingPick.Outcome.IGNORED, pick.resolve("", false, ids()));

        assertTrue(pick.isOpen(id));
        assertEquals(0, recorder.confirmed.size());
        assertEquals(0, recorder.cancelled.get());
    }

    @Test
    public void anAnswerWithNoOpenPickIsIgnored() {
        assertEquals(PendingPick.Outcome.IGNORED, pick.resolve("pick-1", true, ids("a")));
        assertFalse(pick.isOpen("pick-1"));
        assertFalse(pick.isOpen(null));
    }

    @Test
    public void aStaleAnswerForAnEarlierPickDoesNotTouchTheNewOne() {
        Recorder first = new Recorder();
        Recorder second = new Recorder();
        String firstId = pick.open(first);
        pick.resolve(firstId, false, ids());
        String secondId = pick.open(second);

        PendingPick.Outcome stale = pick.resolve(firstId, true, ids("a"));

        assertEquals(PendingPick.Outcome.IGNORED, stale);
        assertTrue(pick.isOpen(secondId));
        assertEquals(0, second.confirmed.size());
        assertEquals(0, second.cancelled.get());
    }

    // ---- robustness

    @Test
    public void aThrowingActionStillLeavesThePickClosed() {
        String id = pick.open(new PendingPick.Callbacks() {
            @Override
            public void onConfirmed(Set<String> receiverIds) {
                throw new IllegalStateException("boom");
            }

            @Override
            public void onCancelled() {
            }
        });

        try {
            pick.resolve(id, true, ids("a"));
            fail("expected the exception to reach the caller");
        } catch (IllegalStateException expected) {
            // ok
        }

        assertFalse(pick.isOpen(id));
        assertNotNull(pick.open(new Recorder()));
    }

    @Test
    public void theActionMayOpenTheNextPick() {
        final List<String> nextId = new ArrayList<>();
        String id = pick.open(new PendingPick.Callbacks() {
            @Override
            public void onConfirmed(Set<String> receiverIds) {
                nextId.add(pick.open(new Recorder()));
            }

            @Override
            public void onCancelled() {
            }
        });

        pick.resolve(id, true, ids("a"));

        assertEquals(1, nextId.size());
        assertNotNull(nextId.get(0));
        assertTrue(pick.isOpen(nextId.get(0)));
    }

    @Test
    public void cancelMayOpenTheNextPick() {
        final List<String> nextId = new ArrayList<>();
        String id = pick.open(new PendingPick.Callbacks() {
            @Override
            public void onConfirmed(Set<String> receiverIds) {
            }

            @Override
            public void onCancelled() {
                nextId.add(pick.open(new Recorder()));
            }
        });

        pick.resolve(id, false, ids());

        assertNotNull(nextId.get(0));
    }
}
