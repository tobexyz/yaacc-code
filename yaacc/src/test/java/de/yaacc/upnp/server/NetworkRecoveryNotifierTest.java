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

package de.yaacc.upnp.server;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class NetworkRecoveryNotifierTest {

    private final NetworkRecoveryNotifier notifier = new NetworkRecoveryNotifier();

    @Test
    public void tellsEveryRegisteredListener() {
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        NetworkRecoveryListener a = first::incrementAndGet;
        NetworkRecoveryListener b = second::incrementAndGet;
        notifier.add(a);
        notifier.add(b);

        notifier.onNetworkRestored();

        assertEquals(1, first.get());
        assertEquals(1, second.get());
    }

    @Test
    public void aListenerAddedTwiceIsToldOnce() {
        AtomicInteger calls = new AtomicInteger();
        NetworkRecoveryListener listener = calls::incrementAndGet;
        notifier.add(listener);
        notifier.add(listener);

        notifier.onNetworkRestored();

        assertEquals(1, calls.get());
        assertEquals(1, notifier.size());
    }

    @Test
    public void aRemovedListenerIsNotTold() {
        AtomicInteger calls = new AtomicInteger();
        NetworkRecoveryListener listener = calls::incrementAndGet;
        notifier.add(listener);
        notifier.remove(listener);

        notifier.onNetworkRestored();

        assertEquals(0, calls.get());
        assertEquals(0, notifier.size());
    }

    @Test
    public void aFailingListenerDoesNotStopTheOthers() {
        AtomicInteger calls = new AtomicInteger();
        NetworkRecoveryListener broken = () -> {
            throw new IllegalStateException("broken");
        };
        NetworkRecoveryListener fine = calls::incrementAndGet;
        notifier.add(broken);
        notifier.add(fine);

        notifier.onNetworkRestored();

        assertEquals(1, calls.get());
    }

    @Test
    public void nullIsIgnored() {
        notifier.add(null);
        notifier.remove(null);
        notifier.onNetworkRestored();
        assertEquals(0, notifier.size());
    }

    @Test
    public void canBeChainedBehindAnotherNotifier() {
        NetworkRecoveryNotifier outer = new NetworkRecoveryNotifier();
        AtomicInteger calls = new AtomicInteger();
        NetworkRecoveryListener listener = calls::incrementAndGet;
        notifier.add(listener);
        outer.add(notifier);

        outer.onNetworkRestored();

        assertEquals(1, calls.get());
    }
}
