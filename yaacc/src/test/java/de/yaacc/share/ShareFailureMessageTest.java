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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * {@link ShareFailureMessage} and the "refused for lack of room" signal of {@link BoundedCall}
 * that feeds it. Plain JVM.
 */
public class ShareFailureMessageTest {

    private static final long LONG = 5_000L;

    // ---- the mapping

    @Test
    public void aProviderCallRefusedForRoomIsBusy() {
        assertEquals(ShareFailureMessage.Kind.BUSY, ShareFailureMessage.of(true, false));
    }

    @Test
    public void aBusyCopySlotIsBusy() {
        assertEquals(ShareFailureMessage.Kind.BUSY, ShareFailureMessage.of(false, true));
    }

    @Test
    public void bothBusyIsBusy() {
        assertEquals(ShareFailureMessage.Kind.BUSY, ShareFailureMessage.of(true, true));
    }

    @Test
    public void anyOtherFailureStaysTheGenericMessage() {
        assertEquals(ShareFailureMessage.Kind.OPEN_FAILED, ShareFailureMessage.of(false, false));
    }

    // ---- the signal from BoundedCall

    @Test
    public void aGateThatIsFullRefusesTheCallAsBusy() throws Exception {
        ProviderCallGate gate = new ProviderCallGate(1);
        BoundedCall calls = new BoundedCall(2, "test-busy", gate);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread hanging = new Thread(() -> calls.call("provider-a", () -> {
            inside.countDown();
            awaitUninterruptibly(release);
            return "x";
        }, LONG, null));
        hanging.start();
        assertTrue(inside.await(5, TimeUnit.SECONDS));

        // the same provider again, and another provider while the gate has no place left
        BoundedCall.Result<String> sameProvider = calls.callDetailed("provider-a", () -> "y", LONG, null);
        BoundedCall.Result<String> otherProvider = calls.callDetailed("provider-b", () -> "y", LONG, null);

        assertNull(sameProvider.value());
        assertTrue(sameProvider.isRefusedBusy());
        assertNull(otherProvider.value());
        assertTrue(otherProvider.isRefusedBusy());
        release.countDown();
        hanging.join(5_000L);
    }

    @Test
    public void aPoolWithoutAFreeThreadRefusesTheCallAsBusy() throws Exception {
        BoundedCall calls = new BoundedCall(1, "test-pool-busy");
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread hanging = new Thread(() -> calls.call(() -> {
            inside.countDown();
            awaitUninterruptibly(release);
            return "x";
        }, LONG, null));
        hanging.start();
        assertTrue(inside.await(5, TimeUnit.SECONDS));

        BoundedCall.Result<String> result = calls.callDetailed(null, () -> "y", LONG, null);

        assertNull(result.value());
        assertTrue(result.isRefusedBusy());
        release.countDown();
        hanging.join(5_000L);
    }

    @Test
    public void aProviderThatDoesNotAnswerInTimeIsNotReportedAsBusy() throws Exception {
        BoundedCall calls = new BoundedCall(1, "test-timeout", new ProviderCallGate(1));
        CountDownLatch release = new CountDownLatch(1);

        BoundedCall.Result<String> result = calls.callDetailed("provider-a", () -> {
            awaitUninterruptibly(release);
            return "x";
        }, 100L, null);

        assertNull(result.value());
        assertFalse(result.isRefusedBusy());
        release.countDown();
    }

    @Test
    public void aFailingCallIsNotReportedAsBusy() {
        BoundedCall calls = new BoundedCall(1, "test-fail", new ProviderCallGate(1));

        BoundedCall.Result<String> result = calls.callDetailed("provider-a", () -> {
            throw new IllegalStateException("provider text must not matter");
        }, LONG, null);

        assertNull(result.value());
        assertFalse(result.isRefusedBusy());
    }

    @Test
    public void aCallThatAnswersNullIsNotReportedAsBusy() {
        BoundedCall calls = new BoundedCall(1, "test-null", new ProviderCallGate(1));

        BoundedCall.Result<String> result = calls.callDetailed("provider-a", () -> null, LONG, null);

        assertNull(result.value());
        assertFalse(result.isRefusedBusy());
    }

    @Test
    public void aCallThatAnswersHasTheValueAndIsNotBusy() {
        BoundedCall calls = new BoundedCall(1, "test-ok", new ProviderCallGate(1));

        BoundedCall.Result<String> result = calls.callDetailed("provider-a", () -> "ok", LONG, null);

        assertEquals("ok", result.value());
        assertFalse(result.isRefusedBusy());
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        while (true) {
            try {
                latch.await();
                return;
            } catch (InterruptedException ignored) {
                // keep waiting, like an uninterruptible Binder call
            }
        }
    }
}
