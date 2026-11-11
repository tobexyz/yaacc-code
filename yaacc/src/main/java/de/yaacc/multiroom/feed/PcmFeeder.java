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
package de.yaacc.multiroom.feed;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Feeds raw PCM into the tcp source of a Snapcast server in real time.
 *
 * <p><b>Where it connects.</b> {@link #connect} takes an {@link InetAddress} and a port and connects to exactly that:
 * the address of the <b>control connection's peer</b> ({@code SnapcastRpc.getPeerAddress()}) and the port that YAACC
 * itself asked for in {@code Stream.AddStream}. A host, port or URI that a server reply contains is never a connect
 * target (the server reports {@code 0.0.0.0:<port>}; connecting to that would reach the phone itself, so the wildcard
 * and multicast addresses are refused). The address is not logged here and not put into an exception.
 *
 * <p><b>How it writes.</b> The producer thread calls {@link #write}, which copies the bytes into a queue of at most
 * {@value #MAX_QUEUE_CHUNKS} chunks. A writer thread of its own takes one chunk at a time and writes it at the
 * deadline given by a {@link RealTimePacer} (absolute deadlines, a look-ahead of 3 chunks, a re-anchor instead of a
 * burst after a stall). The socket gets a small send buffer and {@code TCP_NODELAY}, so what is in flight stays a few
 * chunks and a pause or stop is heard soon. The server reads one chunk per {@code chunk_ms} and resyncs when a read is
 * late, so the writer <b>never underruns</b>: when the queue is empty at the time a chunk is due it writes silence (an
 * all-zero chunk, which the server counts as silent), for at most {@link Config#silenceFillChunks} chunks; after that
 * it waits without writing until audio arrives and anchors the clock again.
 *
 * <p><b>Back pressure.</b> {@link #write} waits up to {@link Config#writeWaitMillis} for room in the queue and then
 * reports failure (and counts the dropped bytes); the queue never grows.
 *
 * <p><b>A stalled peer.</b> Java sockets have no write timeout. A watchdog thread closes the connection when one write
 * has not finished after {@link Config#stallTimeoutMillis} (default 10 s); the feeder is then FAILED with
 * {@link Failure#STALLED} and the listener is told once.
 *
 * <p>{@link #close()} stops both threads and closes the socket. Everything is safe to call from any thread.
 */
public final class PcmFeeder implements PcmSink {

    /** The most chunks the queue between the producer and the writer thread can hold. */
    public static final int MAX_QUEUE_CHUNKS = 8;

    /** The state of a feeder. */
    public enum State {
        /** Connected; the threads run. */
        RUNNING,
        /** A failure ended it (see {@link #getFailure()}); the connection is closed. */
        FAILED,
        /** {@link #close()} was called. */
        CLOSED
    }

    /** Why a feeder failed. No text, no address: only the kind. */
    public enum Failure {
        /** A write to the connection failed (the server closed it, the network went away). */
        WRITE,
        /** A write did not finish within the stall limit: the peer does not read. */
        STALLED
    }

    /** Told once when the feeder fails (not when it is closed). Called on a thread of the feeder: hand work off. */
    public interface Listener {
        void onFailed(Failure failure);
    }

    /** The settings; the defaults are the ones of the design. Setters check their range. */
    public static final class Config {
        private int chunkMillis = 20;
        private int queueChunks = MAX_QUEUE_CHUNKS;
        private int lookAheadChunks = RealTimePacer.DEFAULT_LOOK_AHEAD_CHUNKS;
        private int stallChunks = RealTimePacer.DEFAULT_STALL_CHUNKS;
        private int silenceFillChunks = 50;
        private int writeWaitMillis = 200;
        private int connectTimeoutMillis = 5_000;
        private int sendBufferChunks = 4;
        private int stallTimeoutMillis = 10_000;
        private int watchdogPollMillis = 250;

        public Config() {
        }

        private Config(Config other) {
            chunkMillis = other.chunkMillis;
            queueChunks = other.queueChunks;
            lookAheadChunks = other.lookAheadChunks;
            stallChunks = other.stallChunks;
            silenceFillChunks = other.silenceFillChunks;
            writeWaitMillis = other.writeWaitMillis;
            connectTimeoutMillis = other.connectTimeoutMillis;
            sendBufferChunks = other.sendBufferChunks;
            stallTimeoutMillis = other.stallTimeoutMillis;
            watchdogPollMillis = other.watchdogPollMillis;
        }

        private static int range(int value, int min, int max, String what) {
            if (value < min || value > max) {
                throw new IllegalArgumentException(what + " out of range");
            }
            return value;
        }

        /** The length of a chunk, 5 to 100 ms (the {@code chunk_ms} of the server, default 20). */
        public Config chunkMillis(int value) {
            chunkMillis = range(value, 5, 100, "chunk length");
            return this;
        }

        /** The queue between producer and writer in chunks, 1 to {@link PcmFeeder#MAX_QUEUE_CHUNKS}. */
        public Config queueChunks(int value) {
            queueChunks = range(value, 1, MAX_QUEUE_CHUNKS, "queue size");
            return this;
        }

        /** How far the written audio may run ahead of real time, 1 to 8 chunks (default 3). */
        public Config lookAheadChunks(int value) {
            lookAheadChunks = range(value, 1, 8, "look-ahead");
            return this;
        }

        /** How many chunks late a write may be before the pacer re-anchors, 1 to 16 (default 2). */
        public Config stallChunks(int value) {
            stallChunks = range(value, 1, 16, "stall limit");
            return this;
        }

        /** How many chunks of silence are written when the producer has nothing, 0 to 1000 (default 50 = 1 s). */
        public Config silenceFillChunks(int value) {
            silenceFillChunks = range(value, 0, 1000, "silence fill");
            return this;
        }

        /** How long {@link #write} waits for room in the queue, 0 to 10,000 ms (default 200). */
        public Config writeWaitMillis(int value) {
            writeWaitMillis = range(value, 0, 10_000, "write wait");
            return this;
        }

        /** The connect timeout, 100 to 30,000 ms (default 5000). */
        public Config connectTimeoutMillis(int value) {
            connectTimeoutMillis = range(value, 100, 30_000, "connect timeout");
            return this;
        }

        /** The socket send buffer in chunks, 1 to 16 (default 4). */
        public Config sendBufferChunks(int value) {
            sendBufferChunks = range(value, 1, 16, "send buffer");
            return this;
        }

        /** How long one write may take before the peer counts as stalled, 100 to 60,000 ms (default 10,000). */
        public Config stallTimeoutMillis(int value) {
            stallTimeoutMillis = range(value, 100, 60_000, "stall timeout");
            return this;
        }

        /** How often the watchdog looks (real time), 1 to 5000 ms (default 250). */
        public Config watchdogPollMillis(int value) {
            watchdogPollMillis = range(value, 1, 5_000, "watchdog interval");
            return this;
        }
    }

    private static final long NOT_WRITING = Long.MIN_VALUE;

    private final FeedConnection connection;
    private final FeedClock clock;
    private final Listener listener;
    private final RealTimePacer pacer;
    private final int chunkBytes;
    private final int silenceFillChunks;
    private final long writeWaitNanos;
    private final long stallTimeoutNanos;
    private final int watchdogPollMillis;

    // the queue: a ring of queueChunks * chunkBytes bytes
    private final ReentrantLock queueLock = new ReentrantLock();
    private final Condition notFull = queueLock.newCondition();
    private final Condition notEmpty = queueLock.newCondition();
    private final byte[] ring;
    private int head;
    private int size;
    private boolean dormant;

    private final Object stateLock = new Object();
    private volatile State state = State.RUNNING;
    private volatile Failure failure;

    private final AtomicLong writeStartedNanos = new AtomicLong(NOT_WRITING);
    private final AtomicLong bytesWritten = new AtomicLong();
    private final AtomicLong chunksWritten = new AtomicLong();
    private final AtomicLong silenceChunks = new AtomicLong();
    private final AtomicLong droppedBytes = new AtomicLong();

    private Thread writerThread;
    private Thread watchdogThread;

    private PcmFeeder(FeedConnection connection, PcmFormat format, Config config, FeedClock clock, Listener listener) {
        this.connection = connection;
        this.clock = clock;
        this.listener = listener;
        this.chunkBytes = RealTimePacer.chunkBytesFor(format, config.chunkMillis);
        this.pacer = new RealTimePacer(clock, format.bytesPerSecond(), chunkBytes, config.lookAheadChunks,
                config.stallChunks);
        this.ring = new byte[config.queueChunks * chunkBytes];
        this.silenceFillChunks = config.silenceFillChunks;
        this.writeWaitNanos = TimeUnit.MILLISECONDS.toNanos(config.writeWaitMillis);
        this.stallTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(config.stallTimeoutMillis);
        this.watchdogPollMillis = config.watchdogPollMillis;
    }

    /**
     * Connects and starts the threads. Blocking (up to the connect timeout): never call it on the main thread.
     *
     * @param address  the peer address of the control connection, nothing else
     * @param port     the port that YAACC asked for in {@code Stream.AddStream}
     * @param format   the sample format of the stream
     * @param config   the settings, or null for the defaults
     * @param factory  opens the connection, see {@link SocketFeedConnection#FACTORY}
     * @param clock    the time source, {@link FeedClock#SYSTEM} in the app
     * @param listener told once if the feeder fails; may be null
     * @throws IllegalArgumentException for no address, the wildcard or a multicast address, or a port outside 1 to 65535
     * @throws IOException              when the connection can not be made (a fixed text: no address, no cause)
     */
    public static PcmFeeder connect(InetAddress address, int port, PcmFormat format, Config config,
                                    FeedConnection.Factory factory, FeedClock clock, Listener listener)
            throws IOException {
        if (address == null || address.isAnyLocalAddress() || address.isMulticastAddress()) {
            throw new IllegalArgumentException("not a usable feed address");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range");
        }
        if (format == null || factory == null || clock == null) {
            throw new IllegalArgumentException("missing argument");
        }
        Config settings = new Config(config == null ? new Config() : config);
        int chunk = RealTimePacer.chunkBytesFor(format, settings.chunkMillis);
        FeedConnection connection;
        try {
            connection = factory.open(address, port, settings.connectTimeoutMillis, settings.sendBufferChunks * chunk);
        } catch (IOException | RuntimeException e) {
            // the text of a socket exception can carry the address: say only that it failed
            throw new IOException("feed connection failed");
        }
        if (connection == null) {
            throw new IOException("feed connection failed");
        }
        PcmFeeder feeder = new PcmFeeder(connection, format, settings, clock, listener);
        feeder.start();
        return feeder;
    }

    private void start() {
        writerThread = new Thread(this::writerLoop, "snapcast-feed-writer");
        writerThread.setDaemon(true);
        watchdogThread = new Thread(this::watchdogLoop, "snapcast-feed-watchdog");
        watchdogThread.setDaemon(true);
        writerThread.start();
        watchdogThread.start();
    }

    // ---- producer side

    /**
     * Copies PCM into the queue. Waits up to {@link Config#writeWaitMillis} for room; what does not fit by then is
     * dropped and counted.
     *
     * @return true if everything was queued; false if the feeder is not running or bytes were dropped
     */
    @Override
    public boolean write(byte[] data, int offset, int length) {
        if (data == null || offset < 0 || length < 0 || (long) offset + length > data.length) {
            throw new IllegalArgumentException("range outside the array");
        }
        if (length == 0) {
            return state == State.RUNNING;
        }
        long waitNanos = writeWaitNanos;
        queueLock.lock();
        try {
            int position = offset;
            int remaining = length;
            while (remaining > 0) {
                if (state != State.RUNNING) {
                    droppedBytes.addAndGet(remaining);
                    return false;
                }
                int free = ring.length - size;
                if (free == 0) {
                    if (waitNanos <= 0) {
                        droppedBytes.addAndGet(remaining);
                        return false;
                    }
                    try {
                        waitNanos = notFull.awaitNanos(waitNanos);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        droppedBytes.addAndGet(remaining);
                        return false;
                    }
                    continue;
                }
                int count = Math.min(free, remaining);
                int tail = (head + size) % ring.length;
                int first = Math.min(count, ring.length - tail);
                System.arraycopy(data, position, ring, tail, first);
                System.arraycopy(data, position + first, ring, 0, count - first);
                size += count;
                position += count;
                remaining -= count;
                notEmpty.signalAll();
            }
            return true;
        } finally {
            queueLock.unlock();
        }
    }

    /** Drops what is queued (a seek or a stop); the writer keeps its clock and writes silence if nothing follows. */
    @Override
    public void discard() {
        queueLock.lock();
        try {
            head = 0;
            size = 0;
            notFull.signalAll();
        } finally {
            queueLock.unlock();
        }
    }

    private int queuedBytes() {
        queueLock.lock();
        try {
            return size;
        } finally {
            queueLock.unlock();
        }
    }

    /** Moves up to {@code max} queued bytes into {@code target}; returns how many. */
    private int take(byte[] target, int max) {
        queueLock.lock();
        try {
            int count = Math.min(max, size);
            int first = Math.min(count, ring.length - head);
            System.arraycopy(ring, head, target, 0, first);
            System.arraycopy(ring, 0, target, first, count - first);
            head = (head + count) % ring.length;
            size -= count;
            if (count > 0) {
                notFull.signalAll();
            }
            return count;
        } finally {
            queueLock.unlock();
        }
    }

    /** Blocks until there is queued audio; false if the feeder stopped meanwhile. */
    private boolean awaitData() throws InterruptedException {
        queueLock.lockInterruptibly();
        try {
            dormant = true;
            while (size == 0 && state == State.RUNNING) {
                notEmpty.await();
            }
            return state == State.RUNNING;
        } finally {
            dormant = false;
            queueLock.unlock();
        }
    }

    private void wakeQueueWaiters() {
        queueLock.lock();
        try {
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            queueLock.unlock();
        }
    }

    // ---- writer thread

    private void writerLoop() {
        final byte[] chunk = new byte[chunkBytes];
        // starts dormant: the first audio decides when the clock is anchored, no silence goes out before it
        int silenceFilled = silenceFillChunks;
        try {
            while (state == State.RUNNING) {
                long wait = pacer.nanosUntilWritable(chunkBytes);
                if (wait > 0) {
                    clock.sleepNanos(wait);
                    continue;
                }
                boolean silence = false;
                int available = queuedBytes();
                if (available >= chunkBytes) {
                    take(chunk, chunkBytes);
                    silenceFilled = 0;
                } else {
                    long due = pacer.nanosUntilDue();
                    if (due > 0) {
                        // the audio sent so far is not played yet: give the producer until then
                        clock.sleepNanos(due);
                        continue;
                    }
                    if (available > 0) {
                        // the tail of a burst: pad it to a whole chunk
                        int taken = take(chunk, available);
                        Arrays.fill(chunk, taken, chunkBytes, (byte) 0);
                        silenceFilled = 0;
                    } else if (silenceFilled < silenceFillChunks) {
                        Arrays.fill(chunk, (byte) 0);
                        silenceFilled++;
                        silence = true;
                    } else {
                        if (!awaitData()) {
                            return;
                        }
                        pacer.reset();
                        silenceFilled = 0;
                        continue;
                    }
                }
                writeStartedNanos.set(clock.nanoTime());
                connection.write(chunk, 0, chunkBytes);
                writeStartedNanos.set(NOT_WRITING);
                pacer.onWritten(chunkBytes);
                bytesWritten.addAndGet(chunkBytes);
                chunksWritten.incrementAndGet();
                if (silence) {
                    silenceChunks.incrementAndGet();
                }
            }
        } catch (InterruptedException e) {
            // closing or failing: the thread ends
        } catch (IOException | RuntimeException e) {
            fail(Failure.WRITE);
        }
    }

    // ---- watchdog thread

    private void watchdogLoop() {
        try {
            while (state == State.RUNNING) {
                Thread.sleep(watchdogPollMillis);
                long started = writeStartedNanos.get();
                if (started != NOT_WRITING && clock.nanoTime() - started > stallTimeoutNanos) {
                    fail(Failure.STALLED);
                    return;
                }
            }
        } catch (InterruptedException e) {
            // closing or failing: the thread ends
        }
    }

    // ---- ending

    private void fail(Failure reason) {
        synchronized (stateLock) {
            if (state != State.RUNNING) {
                return;
            }
            state = State.FAILED;
            failure = reason;
        }
        stopIo();
        if (listener != null) {
            try {
                listener.onFailed(reason);
            } catch (RuntimeException ignored) {
                // a misbehaving listener must not break the shutdown of the feeder
            }
        }
    }

    /** Closes the socket (which ends a blocked write) and wakes everything that waits. */
    private void stopIo() {
        connection.close();
        wakeQueueWaiters();
        interruptOthers();
    }

    private void interruptOthers() {
        Thread current = Thread.currentThread();
        if (writerThread != null && writerThread != current) {
            writerThread.interrupt();
        }
        if (watchdogThread != null && watchdogThread != current) {
            watchdogThread.interrupt();
        }
    }

    /** Stops the threads, closes the socket and drops the queue. Safe to call twice and from the listener. */
    public void close() {
        synchronized (stateLock) {
            if (state == State.CLOSED) {
                return;
            }
            state = State.CLOSED;
        }
        stopIo();
        discard();
        Thread current = Thread.currentThread();
        joinQuietly(writerThread, current);
        joinQuietly(watchdogThread, current);
    }

    private static void joinQuietly(Thread thread, Thread current) {
        if (thread == null || thread == current) {
            return;
        }
        try {
            thread.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- state and statistics

    public State getState() {
        return state;
    }

    /** Why the feeder failed; null unless the state is FAILED (or CLOSED after a failure). */
    public Failure getFailure() {
        return failure;
    }

    /** The size of a chunk in bytes. */
    public int getChunkBytes() {
        return chunkBytes;
    }

    /** Bytes in the queue now. */
    public int getQueuedBytes() {
        return queuedBytes();
    }

    /** The most bytes the queue can hold. */
    public int getQueueCapacity() {
        return ring.length;
    }

    /** Chunks handed to the connection (including silence). */
    public long getChunksWritten() {
        return chunksWritten.get();
    }

    /** Bytes handed to the connection (including silence). */
    public long getBytesWritten() {
        return bytesWritten.get();
    }

    /** Chunks of silence written because the producer had nothing. */
    public long getSilenceChunks() {
        return silenceChunks.get();
    }

    /** Bytes that {@link #write} had to drop. */
    public long getDroppedBytes() {
        return droppedBytes.get();
    }

    /** How far the written audio is ahead of real time now (negative: behind). */
    public long getLeadNanos() {
        return pacer.leadNanos();
    }

    /** The largest lead seen right after a write. */
    public long getMaxLeadNanos() {
        return pacer.getMaxLeadNanos();
    }

    /** Chunks that were written more than one chunk after they were due. */
    public long getLateChunks() {
        return pacer.getLateChunks();
    }

    /** How often the pacer re-anchored after a stall. */
    public long getReanchors() {
        return pacer.getReanchors();
    }

    /** True while the writer waits for audio without writing anything. */
    boolean isDormant() {
        queueLock.lock();
        try {
            return dormant;
        } finally {
            queueLock.unlock();
        }
    }
}
