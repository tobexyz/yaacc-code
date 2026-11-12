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

import de.yaacc.multiroom.protocol.CodecHeaderMessage;
import de.yaacc.multiroom.protocol.ServerSettingsMessage;
import de.yaacc.multiroom.protocol.SnapcastConnection;
import de.yaacc.multiroom.protocol.WireChunkMessage;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Runs a {@link SnapcastClientEngine} on its own thread and connects it to a {@link SnapcastConnection}: it is the
 * connection's listener, hands every event to the engine thread through a bounded queue (the connection's listener
 * thread never waits for the audio), and cycles the engine. The engine is touched by that one thread only.
 *
 * <p>The queue holds 1024 events; chunks are accepted only while it is less than three quarters full, so settings, headers
 * and a loss always find room. A chunk that finds no room is dropped and counted. A watchdog ({@link #isStalled}) tells
 * the owner when the thread has not come round for too long (a stuck decoder or output).
 */
public final class EngineHost implements SnapcastConnection.Listener {

    /** Receives the status; called on the engine thread. */
    public interface Observer {
        void onStatus(EngineStatus status);

        /** The engine ended (stopped, failed or abandoned); called once, last. */
        void onEnded(EngineStatus status);
    }

    private static final int QUEUE_CAPACITY = 1024;
    private static final int CHUNK_LIMIT = QUEUE_CAPACITY * 3 / 4;
    private static final long STATUS_INTERVAL_NANOS = 250_000_000L;

    private final SnapcastClientEngine engine;
    private final Observer observer;
    private final int tickMs;
    private final LongSupplier nanos;
    private final Thread thread;
    private final BlockingQueue<Runnable> events = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicReference<EngineStatus> latest = new AtomicReference<>();
    private final AtomicLong droppedForLackOfRoom = new AtomicLong();
    private volatile boolean running;
    private volatile boolean started;
    private volatile long loopStartedNanos;
    private volatile boolean busy;
    private volatile boolean abandoned;
    private boolean stopRequested; // guarded by this
    private boolean endReported; // guarded by this

    public EngineHost(SnapcastClientEngine engine, int tickMs, ThreadFactory threads, Observer observer) {
        this(engine, tickMs, threads, observer, System::nanoTime);
    }

    /** As above with the source of the nanosecond clock used for the cycle timing (a test gives one with another origin). */
    EngineHost(SnapcastClientEngine engine, int tickMs, ThreadFactory threads, Observer observer, LongSupplier nanos) {
        this.nanos = nanos;
        this.engine = engine;
        this.tickMs = tickMs;
        this.observer = observer;
        this.thread = threads.newThread(this::loop);
    }

    /** Starts the thread, unless {@link #stop()} or {@link #abandon()} came first: a stop is final. */
    public synchronized void start() {
        if (started || stopRequested) {
            return;
        }
        started = true;
        running = true;
        loopStartedNanos = nanos.getAsLong();
        thread.start();
    }

    /**
     * Asks the thread to stop the engine and end; waits a little for it. Do not call it on the main thread. A stop is
     * final: after it {@link #start()} does nothing, so a caller that is too late can not leave a thread behind. A host that
     * never ran is ended here (its engine is touched by nobody else) and the end is reported.
     */
    public void stop() {
        synchronized (this) {
            stopRequested = true;
            running = false;
            if (!started) {
                if (!endReported) {
                    endReported = true;
                    engine.stop();
                    EngineStatus status = engine.status();
                    latest.set(status);
                    observer.onEnded(status);
                }
                return;
            }
        }
        events.offer(() -> { });
        if (thread != Thread.currentThread()) {
            try {
                thread.join(2_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isRunning() {
        return started && thread.isAlive();
    }

    /** The newest status, or null before the first cycle. */
    public EngineStatus latestStatus() {
        return latest.get();
    }

    /** Chunks that found no room in the queue. */
    public long droppedForLackOfRoom() {
        return droppedForLackOfRoom.get();
    }

    /**
     * True when the engine thread is inside one cycle for longer than {@code limitMs} (it is stuck in the decoder or the
     * output) or the loop stopped coming round.
     */
    public boolean isStalled(long limitMs) {
        return started && thread.isAlive() && !abandoned
                && (nanos.getAsLong() - loopStartedNanos) > limitMs * 1_000_000L;
    }

    /**
     * Gives the thread up (it is stuck): the owner frees the output itself. The observer hears the end once, now.
     */
    public void abandon() {
        synchronized (this) {
            stopRequested = true;
            if (endReported) {
                return;
            }
            endReported = true;
        }
        abandoned = true;
        running = false;
        EngineStatus last = latest.get();
        EngineStatus status = last != null ? last.copy() : new EngineStatus(); // never change an object others hold
        status.state = EngineState.FAILED;
        status.failure = EngineFailure.STALLED;
        status.hostQueueDrops = droppedForLackOfRoom.get();
        latest.set(status);
        observer.onEnded(status);
        thread.interrupt();
    }

    private void post(Runnable event) {
        if (!events.offer(event)) {
            droppedForLackOfRoom.incrementAndGet();
        }
    }

    public void setManualLatencyMs(int milliseconds) {
        post(() -> engine.setManualLatencyMs(milliseconds));
    }

    // ---- SnapcastConnection.Listener

    @Override
    public void onServerSettings(ServerSettingsMessage settings) {
        post(() -> engine.onServerSettings(settings));
    }

    @Override
    public void onCodecHeader(CodecHeaderMessage header) {
        post(() -> engine.onCodecHeader(header));
    }

    @Override
    public void onChunk(WireChunkMessage chunk) {
        if (events.size() >= CHUNK_LIMIT) {
            droppedForLackOfRoom.incrementAndGet();
            post(engine::onChunkLost); // a flac stream can not find the hole by itself
            return;
        }
        post(() -> engine.onChunk(chunk));
    }

    @Override
    public void onOffsetUpdate(long offsetMicros, long jitterMicros) {
        post(() -> engine.onOffsetUpdate(offsetMicros, jitterMicros));
    }

    @Override
    public void onLost(SnapcastConnection.Loss loss) {
        post(engine::onConnectionLost);
    }

    @Override
    public void onGaveUp(SnapcastConnection.Loss loss) {
        post(engine::onConnectionLost);
    }

    // ---- the thread

    private void loop() {
        long lastPublish = nanos.getAsLong() - STATUS_INTERVAL_NANOS; // the first cycle publishes, whatever the origin
        try {
            while (running && !engine.isTerminal()) {
                loopStartedNanos = nanos.getAsLong();
                Runnable event = events.poll(tickMs, TimeUnit.MILLISECONDS);
                loopStartedNanos = nanos.getAsLong();
                int handled = 0;
                while (event != null && handled++ < 256) {
                    event.run();
                    event = engine.isTerminal() ? null : events.poll();
                }
                engine.tick();
                if (abandoned) {
                    return;
                }
                long now = nanos.getAsLong();
                if (now - lastPublish >= STATUS_INTERVAL_NANOS) {
                    lastPublish = now;
                    publish();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable unexpected) {
            // on a worker an Error would end the process; the engine ends with a typed failure instead
            engine.abort(EngineFailure.INTERNAL);
        }
        if (abandoned) {
            return;
        }
        engine.stop();
        EngineStatus status = engine.status();
        status.hostQueueDrops = droppedForLackOfRoom.get();
        synchronized (this) {
            if (endReported) {
                return;
            }
            endReported = true;
        }
        latest.set(status);
        observer.onEnded(status);
    }

    private void publish() {
        if (abandoned) {
            return; // the end was reported already; a late status must not replace it
        }
        EngineStatus status = engine.status();
        status.hostQueueDrops = droppedForLackOfRoom.get();
        latest.set(status);
        observer.onStatus(status);
    }
}
