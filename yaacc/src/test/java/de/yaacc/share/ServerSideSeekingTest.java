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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * Tests for {@link ServerSideSeeking}: for a renderer that cannot seek, the byte position the
 * server substitutes for the range the renderer asked for (original behavior, built on the
 * bounded per-share state).
 */
public class ServerSideSeekingTest {

    private static final long SIZE = 10_000_000L;

    private static long expected(long timeMs, long durationMs) {
        return SeekPositionCalculator.bytePositionForTime(SIZE, timeMs, durationMs);
    }

    @Test
    public void theFirstRequestWithoutAStoredPositionCreatesStateAndChangesNothing() {
        RendererStateMap states = new RendererStateMap(8);
        long position = ServerSideSeeking.plan(states, "dev-1", () -> 0L, SIZE);
        assertEquals(0L, position);
        assertNotNull(states.get("dev-1"));
        assertEquals(0L, states.get("dev-1").getTimePositionMs());
    }

    @Test
    public void theFirstRequestWithAStoredPositionUsesIt() {
        RendererStateMap states = new RendererStateMap(8);
        long duration = SeekPositionCalculator.estimateDurationMs(SIZE);
        long position = ServerSideSeeking.plan(states, "dev-1", () -> 30_000L, SIZE);
        assertEquals(expected(30_000L, duration), position);
        assertTrue(position > 0L);
        assertEquals(30_000L, states.get("dev-1").getTimePositionMs());
    }

    @Test
    public void aLaterRequestUsesTheStatesTimePositionAndCachesTheDuration() {
        RendererStateMap states = new RendererStateMap(8);
        ServerSideSeeking.plan(states, "dev-1", () -> 0L, SIZE);
        states.update("dev-1", 120_000L, false);
        AtomicInteger supplierCalls = new AtomicInteger();
        long position = ServerSideSeeking.plan(states, "dev-1", () -> {
            supplierCalls.incrementAndGet();
            return 1L;
        }, SIZE);
        long duration = SeekPositionCalculator.estimateDurationMs(SIZE);
        assertEquals(expected(120_000L, duration), position);
        assertEquals("stored position is only read for a new state", 0, supplierCalls.get());
        assertEquals(duration, states.get("dev-1").getTotalDurationMs());
        assertEquals(position, states.get("dev-1").getLastBytePosition());
    }

    @Test
    public void aPausedRendererGetsTheLastBytePositionAgain() {
        RendererStateMap states = new RendererStateMap(8);
        ServerSideSeeking.plan(states, "dev-1", () -> 0L, SIZE);
        states.update("dev-1", 60_000L, false);
        long first = ServerSideSeeking.plan(states, "dev-1", () -> 0L, SIZE);
        states.update("dev-1", 90_000L, true);
        long paused = ServerSideSeeking.plan(states, "dev-1", () -> 0L, SIZE);
        assertEquals(first, paused);
    }

    @Test
    public void aPausedRendererWithoutALastPositionGetsNoOverride() {
        RendererStateMap states = new RendererStateMap(8);
        ServerSideSeeking.plan(states, "dev-1", () -> 0L, SIZE);
        states.update("dev-1", 90_000L, true);
        assertEquals(0L, ServerSideSeeking.plan(states, "dev-1", () -> 0L, SIZE));
    }

    @Test
    public void anUnknownLengthGivesNoOverrideButStillKeepsState() {
        RendererStateMap states = new RendererStateMap(8);
        assertEquals(0L, ServerSideSeeking.plan(states, "dev-1", () -> 50_000L, -1L));
        assertEquals(0L, ServerSideSeeking.plan(states, "dev-1", () -> 50_000L, 0L));
        assertNotNull(states.get("dev-1"));
    }

    @Test
    public void anInvalidDeviceIdCreatesNoStateAndChangesNothing() {
        RendererStateMap states = new RendererStateMap(8);
        AtomicInteger supplierCalls = new AtomicInteger();
        assertEquals(0L, ServerSideSeeking.plan(states, "a/b", () -> {
            supplierCalls.incrementAndGet();
            return 50_000L;
        }, SIZE));
        assertEquals(0L, ServerSideSeeking.plan(states, null, () -> 50_000L, SIZE));
        assertEquals(0L, ServerSideSeeking.plan(states, "", () -> 50_000L, SIZE));
        assertEquals(0, states.size());
        assertEquals(0, supplierCalls.get());
    }

    @Test
    public void aNullStateMapOrSupplierIsHarmless() {
        assertEquals(0L, ServerSideSeeking.plan(null, "dev-1", () -> 5L, SIZE));
        RendererStateMap states = new RendererStateMap(8);
        assertEquals(0L, ServerSideSeeking.plan(states, "dev-1", null, SIZE));
    }

    @Test
    public void manyDevicesStayWithinTheCap() {
        RendererStateMap states = new RendererStateMap(RendererStateMap.DEFAULT_MAX);
        for (int i = 0; i < 500; i++) {
            ServerSideSeeking.plan(states, "device-" + i, () -> 0L, SIZE);
        }
        assertEquals(RendererStateMap.DEFAULT_MAX, states.size());
    }

    @Test
    public void theOverrideIsAlwaysInsideTheFile() {
        RendererStateMap states = new RendererStateMap(8);
        long position = ServerSideSeeking.plan(states, "dev-1", () -> Long.MAX_VALUE, SIZE);
        assertTrue(position >= 0L && position < SIZE);
    }
}
