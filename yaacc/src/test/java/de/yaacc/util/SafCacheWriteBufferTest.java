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
package de.yaacc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

public class SafCacheWriteBufferTest {

    private static final java.util.function.UnaryOperator<String> STORED = key -> key.equals("stored") ? "old" : null;

    @Test
    public void newestWriteWinsPerKey() {
        SafCacheWriteBuffer buffer = new SafCacheWriteBuffer();
        buffer.put("a", "1");
        buffer.put("a", "2");
        buffer.remove("b");
        buffer.put("b", "3");
        buffer.put("c", "4");
        buffer.remove("c");

        SafCacheWriteBuffer.Batch batch = buffer.drain();
        assertEquals(Map.of("a", "2", "b", "3"), batch.puts);
        assertEquals(java.util.Set.of("c"), batch.removes);
        assertFalse(batch.clearAll);
        assertTrue(buffer.isEmpty());
    }

    @Test
    public void readsSeeWaitingWritesBeforeTheStoredState() {
        SafCacheWriteBuffer buffer = new SafCacheWriteBuffer();
        assertEquals("old", buffer.read("stored", STORED));
        buffer.put("stored", "new");
        assertEquals("new", buffer.read("stored", STORED));
        buffer.remove("stored");
        assertNull(buffer.read("stored", STORED));
        assertNull(buffer.read("other", STORED));
    }

    @Test
    public void clearAllVoidsEarlierWritesAndHidesTheStoredState() {
        SafCacheWriteBuffer buffer = new SafCacheWriteBuffer();
        buffer.put("a", "1");
        buffer.putCounter(9);
        buffer.clearAll();
        assertNull(buffer.read("stored", STORED));
        buffer.put("b", "2");
        assertEquals("2", buffer.read("b", STORED));

        SafCacheWriteBuffer.Batch batch = buffer.drain();
        assertTrue(batch.clearAll);
        assertEquals(Map.of("b", "2"), batch.puts);
        assertNull(batch.counter);
        assertEquals("after the drain the stored state is read again", "old", buffer.read("stored", STORED));
    }

    @Test
    public void theCounterKeepsTheLargestValue() {
        SafCacheWriteBuffer buffer = new SafCacheWriteBuffer();
        buffer.putCounter(5);
        buffer.putCounter(3);
        buffer.putCounter(8);
        assertEquals(Long.valueOf(8), buffer.drain().counter);
    }

    @Test
    public void emptyBufferDrainsToAnEmptyBatch() {
        SafCacheWriteBuffer buffer = new SafCacheWriteBuffer();
        assertTrue(buffer.isEmpty());
        assertTrue(buffer.drain().isEmpty());
    }

    @Test
    public void aFailedBatchComesBackBehindNewerWrites() {
        SafCacheWriteBuffer buffer = new SafCacheWriteBuffer();
        buffer.put("a", "old-a");
        buffer.put("b", "old-b");
        buffer.remove("c");
        buffer.putCounter(5);
        SafCacheWriteBuffer.Batch failed = buffer.drain();

        buffer.put("a", "new-a");   // newer put wins over the failed one
        buffer.remove("b");         // newer remove wins
        buffer.put("c", "new-c");   // newer put cancels the old remove
        buffer.restore(failed);

        SafCacheWriteBuffer.Batch batch = buffer.drain();
        assertEquals(Map.of("a", "new-a", "c", "new-c"), batch.puts);
        assertEquals(java.util.Set.of("b"), batch.removes);
        assertEquals(Long.valueOf(5), batch.counter);
    }

    @Test
    public void aFailedBatchIsVoidAfterANewerClear() {
        SafCacheWriteBuffer buffer = new SafCacheWriteBuffer();
        buffer.put("a", "1");
        SafCacheWriteBuffer.Batch failed = buffer.drain();
        buffer.clearAll();
        buffer.restore(failed);
        SafCacheWriteBuffer.Batch batch = buffer.drain();
        assertTrue(batch.clearAll);
        assertTrue(batch.puts.isEmpty());
    }

    @Test
    public void aFailedClearComesBackWithItsWrites() {
        SafCacheWriteBuffer buffer = new SafCacheWriteBuffer();
        buffer.clearAll();
        buffer.put("a", "1");
        SafCacheWriteBuffer.Batch failed = buffer.drain();
        buffer.put("b", "2");
        buffer.restore(failed);
        SafCacheWriteBuffer.Batch batch = buffer.drain();
        assertTrue(batch.clearAll);
        assertEquals(Map.of("a", "1", "b", "2"), batch.puts);
    }
}
