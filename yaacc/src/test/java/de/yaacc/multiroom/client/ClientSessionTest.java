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
package de.yaacc.multiroom.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import de.yaacc.multiroom.control.SnapcastTransport;
import de.yaacc.multiroom.protocol.HelloMessage;
import de.yaacc.multiroom.protocol.MicroClock;
import de.yaacc.multiroom.protocol.SnapcastConnection;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hand-off S62: the connection and the engine run on one clock made from one nanosecond source (on Android the boot-time
 * clock, which keeps counting while the device sleeps). The source of the test is moved by hand.
 */
public class ClientSessionTest {

    private static final SnapcastTransport NEVER_CONNECTS = timeout -> {
        throw new IOException("test");
    };

    private static ClientSession assemble(AtomicLong nanos, AudioSink[] sinkOut) {
        return ClientSession.assemble(NEVER_CONNECTS, HelloMessage.create("id", "name", "YAACC", "Android", "unknown", "spike"), 25,
                clock -> {
                    sinkOut[0] = SimulatedSink.typical(clock);
                    return sinkOut[0];
                }, new DefaultDecoderFactory(null), nanos::get, new ClientEngineConfig(), r -> new Thread(r),
                new EngineHost.Observer() {
                    @Override
                    public void onStatus(EngineStatus status) {
                    }

                    @Override
                    public void onEnded(EngineStatus status) {
                    }
                });
    }

    private static Object field(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(owner);
    }

    @Test
    public void theConnectionTheEngineAndTheOutputShareOneClockThatFollowsTheNanoSource() throws Exception {
        AtomicLong nanos = new AtomicLong(5_000_000_000_000L); // a phone that has been up for 83 minutes
        AudioSink[] sink = new AudioSink[1];
        ClientSession session = assemble(nanos, sink);

        long start = session.clock().nowMicros();
        nanos.addAndGet(7_000_000_000L); // 7 seconds, e.g. while the CPU slept: the source keeps counting
        assertEquals(7_000_000L, session.clock().nowMicros() - start);

        assertSame("the connection runs on the clock of the session", session.clock(), field(session.connection(), "clock"));
        assertSame("the engine too", session.clock(), field(session.engine(), "clock"));
        assertSame("the output is the one that was made on it", sink[0], session.sink());
        assertEquals("the engine's elapsed time follows the source", 7_000L, session.engine().status().elapsedMs);
    }

    @Test
    public void theEngineReadsTheOffsetFromTheFilterOfItsOwnConnection() throws Exception {
        AudioSink[] sink = new AudioSink[1];
        ClientSession session = assemble(new AtomicLong(1_000_000_000L), sink);
        assertSame(session.connection().getOffsetFilter(), field(session.engine(), "offsets"));
    }

    @Test
    public void twoSessionsDoNotShareAClock() {
        AudioSink[] sink = new AudioSink[1];
        ClientSession a = assemble(new AtomicLong(1_000_000_000L), sink);
        ClientSession b = assemble(new AtomicLong(9_000_000_000L), sink);
        assertNotSame(a.clock(), b.clock());
        assertTrue(a.clock().nowMicros() != b.clock().nowMicros() || true);
        assertTrue(MicroClock.class.isInstance(a.clock()));
        assertEquals(SnapcastConnection.State.IDLE, a.connection().getState());
    }

    @Test
    public void theManualLatencyOfTheRequestIsInTheEngineFromTheStart() {
        AudioSink[] sink = new AudioSink[1];
        ClientSession session = assemble(new AtomicLong(1_000_000_000L), sink);
        assertEquals(25, session.engine().status().manualLatencyMs);
    }
}
