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
package de.yaacc.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Unit tests for {@link MonitorSubscriptions}, the subscription bookkeeping
 * of the shared {@link RendererStatusMonitor} (Receiver tab and device sheet).
 * Plain JVM.
 */
public class MonitorSubscriptionsTest {

    private final List<String> started = new ArrayList<>();
    private final MonitorSubscriptions<Consumer<String>, String> subscriptions =
            new MonitorSubscriptions<>((device, generation) -> started.add(device + "@" + generation));

    private static Consumer<String> recorder(List<String> into) {
        return into::add;
    }

    @Test
    public void firstSubscriberStartsPollingEachDeviceOnce() {
        subscriptions.subscribe(recorder(new ArrayList<>()), Arrays.asList("tv", "kitchen", "tv"));

        assertEquals(Arrays.asList("tv@0", "kitchen@0"), started);
        assertTrue(subscriptions.isCurrent(0));
    }

    @Test
    public void secondSubscriberDoesNotRestartPolledDevices() {
        subscriptions.subscribe(recorder(new ArrayList<>()), Arrays.asList("tv", "kitchen"));

        subscriptions.subscribe(recorder(new ArrayList<>()), Arrays.asList("kitchen", "tv", "bedroom"));

        assertEquals(Arrays.asList("tv@0", "kitchen@0", "bedroom@0"), started);
    }

    @Test
    public void subscribingTheSameListenerTwiceDeliversOnce() {
        List<String> received = new ArrayList<>();
        Consumer<String> listener = recorder(received);
        subscriptions.subscribe(listener, Collections.singletonList("tv"));
        subscriptions.subscribe(listener, Collections.singletonList("tv"));

        subscriptions.deliver(0, l -> l.accept("status"));

        assertEquals(Collections.singletonList("status"), received);
    }

    @Test
    public void lastUnsubscriberStopsAndInFlightResultsAreDropped() {
        Consumer<String> first = recorder(new ArrayList<>());
        List<String> secondReceived = new ArrayList<>();
        Consumer<String> second = recorder(secondReceived);
        subscriptions.subscribe(first, Collections.singletonList("tv"));
        subscriptions.subscribe(second, Collections.singletonList("tv"));

        assertFalse("others remain", subscriptions.unsubscribe(first));
        assertTrue(subscriptions.isCurrent(0));
        assertTrue("last one leaves", subscriptions.unsubscribe(second));

        assertFalse(subscriptions.isCurrent(0));
        assertFalse(subscriptions.deliver(0, l -> l.accept("late")));
        assertTrue(secondReceived.isEmpty());
    }

    @Test
    public void unsubscribingAnUnknownListenerIsANoOp() {
        List<String> received = new ArrayList<>();
        subscriptions.subscribe(recorder(received), Collections.singletonList("tv"));

        assertFalse(subscriptions.unsubscribe(recorder(new ArrayList<>())));

        assertTrue(subscriptions.isCurrent(0));
        assertTrue(subscriptions.deliver(0, l -> l.accept("status")));
        assertEquals(Collections.singletonList("status"), received);
    }

    @Test
    public void unsubscribingWhenNothingIsMonitoredIsANoOp() {
        assertFalse(subscriptions.unsubscribe(recorder(new ArrayList<>())));
        assertTrue(subscriptions.isCurrent(0));
    }

    @Test
    public void resubscribingAfterStopStartsPollingAgainInANewGeneration() {
        Consumer<String> listener = recorder(new ArrayList<>());
        subscriptions.subscribe(listener, Collections.singletonList("tv"));
        subscriptions.unsubscribe(listener);

        subscriptions.subscribe(listener, Collections.singletonList("tv"));

        assertEquals(Arrays.asList("tv@0", "tv@1"), started);
        assertTrue(subscriptions.isCurrent(1));
        assertFalse("the old chain must stop", subscriptions.isCurrent(0));
    }

    @Test
    public void listenerUnsubscribingDuringDeliveryDoesNotBreakOthers() {
        List<String> firstReceived = new ArrayList<>();
        List<String> lastReceived = new ArrayList<>();
        List<String> selfRemovingReceived = new ArrayList<>();
        Consumer<String> first = recorder(firstReceived);
        Consumer<String> last = recorder(lastReceived);
        Consumer<String> selfRemoving = new Consumer<String>() {
            @Override
            public void accept(String status) {
                selfRemovingReceived.add(status);
                subscriptions.unsubscribe(this);
            }
        };
        subscriptions.subscribe(first, Collections.singletonList("tv"));
        subscriptions.subscribe(selfRemoving, Collections.emptyList());
        subscriptions.subscribe(last, Collections.emptyList());

        subscriptions.deliver(0, l -> l.accept("one"));
        subscriptions.deliver(0, l -> l.accept("two"));

        assertEquals(Arrays.asList("one", "two"), firstReceived);
        assertEquals(Collections.singletonList("one"), selfRemovingReceived);
        assertEquals(Arrays.asList("one", "two"), lastReceived);
        assertTrue("others remain, monitoring continues", subscriptions.isCurrent(0));
    }

    @Test
    public void listenerRemovedByAnotherDuringDeliveryIsNotCalledAfterwards() {
        List<String> victimReceived = new ArrayList<>();
        Consumer<String> victim = recorder(victimReceived);
        Consumer<String> remover = status -> subscriptions.unsubscribe(victim);
        subscriptions.subscribe(remover, Collections.singletonList("tv"));
        subscriptions.subscribe(victim, Collections.emptyList());

        subscriptions.deliver(0, l -> l.accept("status"));

        assertTrue(victimReceived.isEmpty());
    }

    @Test
    public void nullDevicesAndNullDeviceListAreIgnored() {
        subscriptions.subscribe(recorder(new ArrayList<>()), null);
        subscriptions.subscribe(recorder(new ArrayList<>()), Arrays.asList(null, "tv"));

        assertEquals(Collections.singletonList("tv@0"), started);
    }
}
