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

import de.yaacc.multiroom.control.SnapcastTransport;
import de.yaacc.multiroom.protocol.HelloMessage;
import de.yaacc.multiroom.protocol.MicroClock;
import de.yaacc.multiroom.protocol.OffsetSource;
import de.yaacc.multiroom.protocol.SnapcastConnection;

import java.util.concurrent.ThreadFactory;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * The pieces of one session, put together in one place so that the wiring can be tested: <b>one</b> {@link MicroClock}
 * made from the given source of nanoseconds (on Android {@code SystemClock::elapsedRealtimeNanos}, which keeps counting
 * while the device sleeps; hand-off S62) is given to the connection (its time sync is measured on it) and to the
 * engine (it schedules on it), and the engine reads the offset from the connection's own filter. Nothing is started.
 */
public final class ClientSession {

    private final MicroClock clock;
    private final SnapcastConnection connection;
    private final AudioSink sink;
    private final SnapcastClientEngine engine;
    private final EngineHost host;

    private ClientSession(MicroClock clock, SnapcastConnection connection, AudioSink sink, SnapcastClientEngine engine,
                          EngineHost host) {
        this.clock = clock;
        this.sink = sink;
        this.connection = connection;
        this.engine = engine;
        this.host = host;
    }

    public static ClientSession assemble(SnapcastTransport transport, HelloMessage hello, int manualLatencyMs,
                                         Function<MicroClock, ? extends AudioSink> sinks, DecoderFactory decoders, LongSupplier nanoSource, ClientEngineConfig config,
                                         ThreadFactory audioThreads, EngineHost.Observer observer) {
        MicroClock clock = MicroClock.fromNanoSource(nanoSource);
        AudioSink sink = sinks.apply(clock); // an output that reports positions needs the same clock
        SnapcastConnection connection = new SnapcastConnection(transport, hello, new SnapcastConnection.Config(), clock, null, null);
        OffsetSource offsets = connection.getOffsetFilter();
        SnapcastClientEngine engine = new SnapcastClientEngine(config, sink, decoders, offsets, clock);
        engine.setManualLatencyMs(manualLatencyMs);
        EngineHost host = new EngineHost(engine, config.getTickMs(), audioThreads, observer);
        return new ClientSession(clock, connection, sink, engine, host);
    }

    public MicroClock clock() {
        return clock;
    }

    public SnapcastConnection connection() {
        return connection;
    }

    public AudioSink sink() {
        return sink;
    }

    SnapcastClientEngine engine() {
        return engine;
    }

    public EngineHost host() {
        return host;
    }
}
