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
package de.yaacc.upnp.protocol.sync;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts the failed deliveries of events to a subscriber. A control point whose callback address cannot be
 * reached from here (Home Assistant behind a proxy or in a container network is the usual case) fails every
 * delivery after a connect timeout of several seconds on a pool thread; after
 * {@link #MAX_FAILURES_IN_A_ROW} failures in a row the subscription is given up, and the control point has to
 * subscribe again. A delivery that succeeds starts the count again. Thread safe, plain JVM.
 */
final class EventDeliveryHealth {

    /** Failed deliveries in a row after which the subscription is dropped. */
    static final int MAX_FAILURES_IN_A_ROW = 3;

    private final AtomicInteger failures = new AtomicInteger();

    /** A delivery succeeded. */
    void succeeded() {
        failures.set(0);
    }

    /**
     * A delivery failed.
     *
     * @return true if this was the failure that gives the subscription up, and only for that one
     */
    boolean failed() {
        return failures.incrementAndGet() == MAX_FAILURES_IN_A_ROW;
    }

    int failuresInARow() {
        return failures.get();
    }
}
