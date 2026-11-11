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
package de.yaacc.multiroom.protocol;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import de.yaacc.multiroom.control.SocketTransport;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Opt-in test against the binary port of a real snapserver (the Docker rig in {@code tools/snapcast-rig/}, port 1704
 * on the host). It is skipped unless the system property {@code snapcast.rig} names the host, exactly like
 * {@code SnapcastRigTest}; it never runs in the normal gate.
 * <pre>
 *   ./gradlew :yaacc:testDebugUnitTest --offline --tests 'de.yaacc.multiroom.protocol.SnapcastBinaryRigTest' -Dsnapcast.rig=127.0.0.1
 *   # ten minutes, and fail if no chunk arrives (feed the stream first, see tools/snapcast-rig/README.md):
 *   ./gradlew ... -Dsnapcast.rig=127.0.0.1 -Dsnapcast.rig.seconds=600 -Dsnapcast.rig.expectChunks=true
 * </pre>
 * It is a client like any other: it joins the group of the default stream, receives for {@code snapcast.rig.seconds}
 * (default 10) and prints what the clock sync and the chunks looked like (also to
 * {@code build/snapcast-rig/offset-report.txt}). It changes nothing on the server.
 */
public class SnapcastBinaryRigTest {

    private String host;
    private int seconds;
    private boolean expectChunks;

    @Before
    public void requireTheRig() {
        host = System.getProperty("snapcast.rig");
        Assume.assumeTrue("no rig: pass -Dsnapcast.rig=<host> to Gradle to run this test",
                host != null && !host.trim().isEmpty());
        host = host.trim();
        seconds = Math.max(1, Integer.getInteger("snapcast.rig.seconds", 10));
        expectChunks = Boolean.getBoolean("snapcast.rig.expectChunks");
    }

    @Test
    public void receivesTheStreamAndTheClockOffsetConverges() throws Exception {
        final MicroClock clock = MicroClock.monotonic();
        final AtomicReference<ServerSettingsMessage> settings = new AtomicReference<>();
        final AtomicReference<CodecHeaderMessage> header = new AtomicReference<>();
        final AtomicLong chunks = new AtomicLong();
        final AtomicLong bytes = new AtomicLong();
        final AtomicLong ageMin = new AtomicLong(Long.MAX_VALUE);
        final AtomicLong ageMax = new AtomicLong(Long.MIN_VALUE);
        final AtomicLong offsetMin = new AtomicLong(Long.MAX_VALUE);
        final AtomicLong offsetMax = new AtomicLong(Long.MIN_VALUE);
        final AtomicLong offsetFirst = new AtomicLong();
        final AtomicLong offsetLast = new AtomicLong();
        final AtomicLong jitterMax = new AtomicLong();
        final AtomicLong updates = new AtomicLong();
        final AtomicReference<SnapcastConnection.Loss> failure = new AtomicReference<>();
        final CountDownLatch failed = new CountDownLatch(1);
        final SnapcastConnection[] holder = new SnapcastConnection[1];

        SnapcastConnection.Listener listener = new SnapcastConnection.Listener() {
            @Override
            public void onServerSettings(ServerSettingsMessage message) {
                settings.set(message);
            }

            @Override
            public void onCodecHeader(CodecHeaderMessage message) {
                header.set(message);
            }

            @Override
            public void onChunk(WireChunkMessage chunk) {
                chunks.incrementAndGet();
                bytes.addAndGet(chunk.getPayloadLength());
                SnapcastConnection connection = holder[0];
                if (connection != null && connection.getOffsetFilter().isSynchronized()) {
                    // how long ago the server recorded this part of the stream, on the corrected clock: a few ms to a
                    // few tens of ms when the offset is right; seconds when it is wrong
                    long age = connection.getOffsetFilter().serverNowMicros(clock.nowMicros()) - chunk.timestampMicros();
                    ageMin.accumulateAndGet(age, Math::min);
                    ageMax.accumulateAndGet(age, Math::max);
                }
            }

            @Override
            public void onOffsetUpdate(long offsetMicros, long jitterMicros) {
                if (updates.getAndIncrement() == 0) {
                    offsetFirst.set(offsetMicros);
                }
                offsetLast.set(offsetMicros);
                offsetMin.accumulateAndGet(offsetMicros, Math::min);
                offsetMax.accumulateAndGet(offsetMicros, Math::max);
                jitterMax.accumulateAndGet(jitterMicros, Math::max);
            }

            @Override
            public void onGaveUp(SnapcastConnection.Loss loss) {
                failure.set(loss);
                failed.countDown();
            }
        };
        HelloMessage hello = HelloMessage.create("yaacc-rig-test", "rig-test", "YAACC rig test", "Linux", "x86_64", "test");
        SnapcastConnection connection = new SnapcastConnection(new SocketTransport(host, SnapcastConnection.DEFAULT_PORT),
                hello, new SnapcastConnection.Config(), clock, null, null);
        holder[0] = connection;
        connection.addListener(listener);
        long startedAt = System.nanoTime();
        connection.start();
        try {
            // the wait for the duration is the test's clock; it ends early when the connection gives up
            failed.await(seconds, TimeUnit.SECONDS);
        } finally {
            connection.close();
        }
        double elapsed = (System.nanoTime() - startedAt) / 1e9;

        StringWriter text = new StringWriter();
        PrintWriter report = new PrintWriter(text);
        report.printf("snapcast rig test: %.1f s against the binary port, expectChunks=%s%n", elapsed, expectChunks);
        report.printf("settings: %s%n", settings.get() == null ? "none" : "bufferMs=" + settings.get().getBufferMs()
                + " latency=" + settings.get().getLatencyMs() + " volume=" + settings.get().getVolume());
        report.printf("codec header: %s%n", header.get() == null ? "none" : header.get().getCodec() + " "
                + header.get().getSampleRate() + " Hz, " + header.get().getChannels() + " ch, "
                + header.get().getBitsPerSample() + " bit");
        report.printf("chunks: %d, %d bytes, age on the corrected clock min %s max %s us%n", chunks.get(), bytes.get(),
                ageMin.get() == Long.MAX_VALUE ? "-" : ageMin.get(), ageMax.get() == Long.MIN_VALUE ? "-" : ageMax.get());
        ClockOffsetFilter filter = connection.getOffsetFilter();
        report.printf("time sync: %d updates, offset first %d last %d min %d max %d us (spread %d us), jitter max %d us, "
                        + "error bound %d us, resyncs %d, rejected samples %d, dropped chunks %d, before header %d%n",
                updates.get(), offsetFirst.get(), offsetLast.get(),
                updates.get() == 0 ? 0 : offsetMin.get(), updates.get() == 0 ? 0 : offsetMax.get(),
                updates.get() == 0 ? 0 : offsetMax.get() - offsetMin.get(), jitterMax.get(), filter.errorBoundMicros(),
                filter.resyncCount(), filter.rejectedSamples(), connection.getDroppedChunks(), connection.getDroppedBeforeHeader());
        report.flush();
        System.out.println(text);
        File directory = new File("build/snapcast-rig");
        if (directory.isDirectory() || directory.mkdirs()) {
            try (PrintWriter file = new PrintWriter(new File(directory, "offset-report.txt"), "UTF-8")) {
                file.print(text);
            }
        }

        assertTrue("the connection gave up: " + (failure.get() == null ? "" : failure.get().getReason()), failure.get() == null);
        assertNotNull("no ServerSettings arrived", settings.get());
        assertNotNull("no CodecHeader arrived", header.get());
        assertTrue("the clock never synchronised", updates.get() > 0);
        assertTrue("the offset moved by more than 20 ms on a loopback connection: " + (offsetMax.get() - offsetMin.get()) + " us",
                offsetMax.get() - offsetMin.get() <= 20_000);
        if (expectChunks) {
            assertTrue("no chunk arrived: is the stream playing? (tools/snapcast-rig/README.md)", chunks.get() > 0);
        }
    }
}
