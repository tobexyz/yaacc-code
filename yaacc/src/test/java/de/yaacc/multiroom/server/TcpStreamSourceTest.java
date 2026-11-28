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
package de.yaacc.multiroom.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import de.yaacc.multiroom.protocol.MicroClock;

import org.junit.After;
import org.junit.Test;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The tcp source of a stream (issue #330, task 2.4): one producer pushes raw PCM; the server reads it in real time, so a producer that is
 * ahead is slowed by TCP; the stream is idle when the producer is gone.
 */
public class TcpStreamSourceTest {

    private static final class Record {
        final long stamp;
        final long arrivedMicros;

        Record(long stamp, long arrivedMicros) {
            this.stamp = stamp;
            this.arrivedMicros = arrivedMicros;
        }
    }

    private final MicroClock clock = MicroClock.monotonic();
    private final List<Record> chunks = new CopyOnWriteArrayList<>();
    private final List<String> statuses = new CopyOnWriteArrayList<>();
    private TcpStreamSource source;

    private final StreamPipeline.Sink sink = new StreamPipeline.Sink() {
        @Override
        public void chunk(String streamId, long timestampMicros, byte[] payload) {
            chunks.add(new Record(timestampMicros, clock.nowMicros()));
        }

        @Override
        public void status(String streamId, StreamActivity.Status status) {
            statuses.add(status.json());
        }
    };

    @After
    public void tearDown() {
        if (source != null) {
            source.close();
        }
    }

    private TcpStreamSource open(java.util.function.Predicate<InetAddress> accept) throws Exception {
        StreamPipeline pipeline = new StreamPipeline("s", 48000, 4, new ChunkEncoder.Pcm(48000, 2, 16), sink);
        source = new TcpStreamSource(pipeline, 0, accept, clock);
        source.start();
        return source;
    }

    private static void waitFor(String what, java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(what, condition.getAsBoolean());
    }

    private static byte[] sound(int chunks) {
        byte[] b = new byte[3840 * chunks];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) (i % 100 + 1);
        }
        return b;
    }

    @Test(timeout = 60000)
    public void aProducerThatPushesFasterThanRealTimeIsReadInRealTime() throws Exception {
        open(a -> true);
        try (Socket producer = new Socket(InetAddress.getLoopbackAddress(), source.port())) {
            OutputStream out = producer.getOutputStream();
            out.write(sound(6)); // 120 ms of audio at once
            out.flush();
            waitFor("six chunks", () -> chunks.size() >= 6);
        }
        Record first = chunks.get(0);
        Record last = chunks.get(5);
        assertEquals("the stamps are consecutive on the stream clock", first.stamp + 5 * 20_000L, last.stamp);
        assertTrue("the last chunk was read about 100 ms after the first, not at once: " + (last.arrivedMicros - first.arrivedMicros),
                last.arrivedMicros - first.arrivedMicros >= 80_000L);
        assertEquals("playing", statuses.get(0));
    }

    @Test(timeout = 60000)
    public void whenTheProducerGoesTheStreamIsIdleAndANewProducerStartsANewClock() throws Exception {
        open(a -> true);
        try (Socket producer = new Socket(InetAddress.getLoopbackAddress(), source.port())) {
            producer.getOutputStream().write(sound(2));
            producer.getOutputStream().flush();
            waitFor("two chunks", () -> chunks.size() >= 2);
        }
        waitFor("idle", () -> statuses.contains("idle"));
        int before = chunks.size();
        long laterStart = clock.nowMicros();
        try (Socket producer = new Socket(InetAddress.getLoopbackAddress(), source.port())) {
            producer.getOutputStream().write(sound(1));
            producer.getOutputStream().flush();
            waitFor("a chunk of the second producer", () -> chunks.size() > before);
        }
        assertTrue("the clock started again with the new producer", chunks.get(before).stamp >= laterStart);
    }

    @Test(timeout = 60000)
    public void aSecondProducerWhileOneIsActiveIsRefused() throws Exception {
        open(a -> true);
        try (Socket first = new Socket(InetAddress.getLoopbackAddress(), source.port())) {
            first.getOutputStream().write(sound(1));
            first.getOutputStream().flush();
            waitFor("the first is read", () -> chunks.size() >= 1);
            try (Socket second = new Socket(InetAddress.getLoopbackAddress(), source.port())) {
                second.setSoTimeout(5000);
                assertEquals("the second is closed at once", -1, second.getInputStream().read());
            }
        }
    }

    @Test(timeout = 60000)
    public void anAddressTheFilterRefusesIsClosed() throws Exception {
        open(a -> false);
        try (Socket producer = new Socket(InetAddress.getLoopbackAddress(), source.port())) {
            producer.setSoTimeout(5000);
            assertEquals(-1, producer.getInputStream().read());
        }
        assertEquals(0, chunks.size());
    }

    @Test(timeout = 60000)
    public void aPortThatIsTakenFailsToStart() throws Exception {
        open(a -> true);
        StreamPipeline other = new StreamPipeline("t", 48000, 4, new ChunkEncoder.Pcm(48000, 2, 16), sink);
        TcpStreamSource clash = new TcpStreamSource(other, source.port(), a -> true, clock);
        try {
            clash.start();
            org.junit.Assert.fail("the port is taken");
        } catch (java.io.IOException expected) {
            // fine
        }
    }
}
