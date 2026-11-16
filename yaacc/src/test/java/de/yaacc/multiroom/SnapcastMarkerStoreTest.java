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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import de.yaacc.multiroom.control.SnapcastOperations;
import de.yaacc.multiroom.feed.SnapcastStreams;

import org.junit.Test;


/** The marker of the stream name is kept strictly: exactly 8 lower-case hex characters, or nothing. */
public class SnapcastMarkerStoreTest {

    private static final class Memory implements SnapcastMarkerStore.Storage {
        volatile String value;
        volatile int writes;
        volatile long readDelayMillis;

        @Override
        public String read() {
            String seen = value;
            if (readDelayMillis > 0) {
                try {
                    Thread.sleep(readDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return seen;
        }

        @Override
        public void write(String text) {
            value = text;
            writes++;
        }
    }

    @Test
    public void aMarkerThatWasSavedIsLoadedAgain() {
        Memory memory = new Memory();
        SnapcastMarkerStore store = new SnapcastMarkerStore(memory);

        store.save("0123abcd");

        assertEquals("0123abcd", store.load());
        assertEquals("0123abcd", new SnapcastMarkerStore(memory).load());
    }

    @Test
    public void nothingStoredIsNull() {
        assertNull(new SnapcastMarkerStore(new Memory()).load());
    }

    @Test
    public void aDamagedOrForgedValueCountsAsNothing() {
        String[] bad = {"", "0123abc", "0123abcde", "0123ABCD", "0123abcg", "0123abc ", " 0123abc", "0123abc\n",
                "0123abcd&mode=client", "%30123abc", "../../ab", "0123abc\u0000", "٠123abcd"};
        for (String value : bad) {
            Memory memory = new Memory();
            memory.value = value;
            assertNull("[" + value + "]", new SnapcastMarkerStore(memory).load());
        }
    }

    @Test
    public void aBadMarkerIsNotSaved() {
        Memory memory = new Memory();
        SnapcastMarkerStore store = new SnapcastMarkerStore(memory);

        store.save("0123ABCD");
        store.save("short");
        store.save(null);
        store.save("0123abcd&mode=client");

        assertEquals(0, memory.writes);
        assertNull(store.load());
    }

    @Test
    public void everyEightHexCharactersAreValidAndNothingElse() {
        assertTrue(SnapcastMarkerStore.isValid("00000000"));
        assertTrue(SnapcastMarkerStore.isValid("ffffffff"));
        assertTrue(SnapcastMarkerStore.isValid("0a1b2c3d"));
        assertFalse(SnapcastMarkerStore.isValid("0a1b2c3"));
        assertFalse(SnapcastMarkerStore.isValid("0a1b2c3dd"));
        assertFalse(SnapcastMarkerStore.isValid(null));
    }

    @Test
    public void theStreamHandlingMakesTheMarkerOnceAndKeepsItAcrossRestarts() {
        Memory memory = new Memory();
        SnapcastOperations operations = new SnapcastOperations((method, params) -> {
            throw new AssertionError("no call expected");
        });

        SnapcastStreams first = new SnapcastStreams(operations, "Phone", new SnapcastMarkerStore(memory));
        SnapcastStreams second = new SnapcastStreams(operations, "Phone", new SnapcastMarkerStore(memory));

        assertTrue("a marker was made and stored", SnapcastMarkerStore.isValid(memory.value));
        assertEquals(1, memory.writes);
        assertEquals(first.getMarker(), memory.value);
        assertEquals("the second run reads it", first.getMarker(), second.getMarker());
        assertEquals(first.getStreamName(), second.getStreamName());
    }

    @Test
    public void aDamagedStoreGetsANewMarkerThatReplacesTheOldValue() {
        Memory memory = new Memory();
        memory.value = "not a marker";

        SnapcastStreams streams = new SnapcastStreams(new SnapcastOperations((method, params) -> {
            throw new AssertionError("no call expected");
        }), "Phone", new SnapcastMarkerStore(memory));

        assertTrue(SnapcastMarkerStore.isValid(streams.getMarker()));
        assertEquals(streams.getMarker(), memory.value);
    }

    @Test
    public void markersOfTwoInstallsDiffer() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 20; i++) {
            Memory memory = new Memory();
            new SnapcastStreams(new SnapcastOperations((method, params) -> {
                throw new AssertionError("no call expected");
            }), "Phone", new SnapcastMarkerStore(memory));
            seen.add(memory.value);
        }
        assertTrue("20 installs, " + seen.size() + " different markers", seen.size() >= 19);
    }

    @Test
    public void twoServersStartedAtTheSameMomentShareOneMarker() throws Exception {
        // first use ever: two workers make the stream handling of two servers at once, from two store objects
        final Memory memory = new Memory();
        memory.readDelayMillis = 150; // widens the window between "nothing stored" and "stored"
        final SnapcastOperations operations = new SnapcastOperations((method, params) -> {
            throw new AssertionError("no call expected");
        });
        final String[] markers = new String[2];
        Thread[] threads = new Thread[2];
        for (int i = 0; i < 2; i++) {
            final int index = i;
            threads[i] = new Thread(() -> markers[index] =
                    new SnapcastStreams(operations, "Phone", new SnapcastMarkerStore(memory)).getMarker());
            threads[i].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }

        assertEquals("one marker for the install", markers[0], markers[1]);
        assertEquals("written once", 1, memory.writes);
        assertEquals(markers[0], memory.value);
    }

    @Test
    public void loadOrCreateReturnsWhatIsStoredAndMakesTheMarkerOnlyWhenNothingIs() {
        Memory memory = new Memory();
        SnapcastMarkerStore store = new SnapcastMarkerStore(memory);
        int[] made = new int[1];

        assertEquals("0123abcd", store.loadOrCreate(() -> {
            made[0]++;
            return "0123abcd";
        }));
        assertEquals("0123abcd", store.loadOrCreate(() -> {
            made[0]++;
            return "ffffffff";
        }));

        assertEquals(1, made[0]);
        assertEquals(1, memory.writes);
    }

    @Test
    public void aMarkerThatIsNotValidIsNeverWritten() {
        Memory memory = new Memory();
        SnapcastMarkerStore store = new SnapcastMarkerStore(memory);

        store.loadOrCreate(() -> "not a marker");

        assertEquals(0, memory.writes);
        assertNull(store.load());
    }
}
