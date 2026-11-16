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
package de.yaacc.multiroom;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The tick of a session runs while the connection is up. A reconnect that lands while the last tick is ending must
 * not leave the connection without a tick (the held-back re-reads are delivered by it).
 */
public class SnapcastTickGateTest {

    private final AtomicInteger scheduled = new AtomicInteger();
    private final AtomicInteger polls = new AtomicInteger();
    private final SnapcastTickGate gate = new SnapcastTickGate(scheduled::incrementAndGet);

    @Test
    public void startingSchedulesOneTick() {
        gate.start();
        gate.start();

        assertEquals(1, scheduled.get());
    }

    @Test
    public void aTickWhileActivePollsAndSchedulesTheNext() {
        gate.start();

        gate.run(() -> true, polls::incrementAndGet);

        assertEquals(1, polls.get());
        assertEquals(2, scheduled.get());
        gate.start();
        assertEquals("still running: no second chain", 2, scheduled.get());
    }

    @Test
    public void aTickWhileInactiveEndsTheChainAndAStartBeginsANewOne() {
        gate.start();

        gate.run(() -> false, polls::incrementAndGet);

        assertEquals(0, polls.get());
        assertEquals(1, scheduled.get());
        gate.start();
        assertEquals(2, scheduled.get());
    }

    @Test
    public void aReconnectThatLandsWhileTheTickLooksIsNotLost() {
        gate.start();

        // the tick sees "offline"; the connection comes back before the tick has finished ending
        gate.run(() -> {
            gate.start(); // onConnected -> startTick
            return false;
        }, polls::incrementAndGet);

        assertEquals("the reconnect started its own tick", 2, scheduled.get());
        assertEquals(0, polls.get());
    }

    @Test
    public void aReconnectThatLandsAfterTheLookIsNotLostEither() {
        gate.start();

        gate.run(() -> false, polls::incrementAndGet);
        gate.start();

        assertEquals(2, scheduled.get());
    }

    @Test
    public void aTickThatSeesTheConnectionBackKeepsTheChainAlone() {
        gate.start();

        gate.run(() -> true, polls::incrementAndGet);
        gate.run(() -> true, polls::incrementAndGet);

        assertEquals(2, polls.get());
        assertEquals("one chain, one task waiting at a time", 3, scheduled.get());
    }
}
