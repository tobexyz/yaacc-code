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
package de.yaacc.fcast.protocol;

import de.yaacc.multiroom.control.MonotonicClock;
import de.yaacc.util.YaaccLogger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One TCP connection to one FCast receiver, protocol v3 (docs/tech.md, "FCast"; design.md, section 1).
 *
 * <p><b>Handshake.</b> {@link #connect()} connects (with a timeout) and sends {@code Version 3} <b>at once</b> without waiting
 * for the receiver's (both reference receivers expect the sender's first). The receiver's {@code Version} is only an upper
 * bound: the connection speaks the smaller number ({@link #getNegotiatedVersion()}); a receiver of version 4 is spoken to in 3,
 * and this class never sends {@code Version 4} (the desktop receiver would switch to TLS). Our own {@code Initial} follows
 * the receiver's {@code Version} (or {@code initialWaitMillis} without one) and only if the connection speaks 3: a receiver
 * of version 1 or 2 never gets an opcode it does not have (task 2.4). Until the receiver's {@code Version} is known the
 * connection assumes 3.
 *
 * <p><b>Threads.</b> One reader thread (it answers a {@code Ping} with a {@code Pong} before anything else, so a slow listener
 * can not make the receiver drop us), one writer thread with a <b>bounded</b> queue (a receiver that stops reading can not
 * make memory grow: the queue is full, the connection is closed with {@code WRITE_OVERFLOW}; a single write that blocks
 * longer than {@code writeTimeoutMillis} closes it with {@code WRITE_TIMEOUT}), one dispatcher thread that calls the
 * {@link Listener} in order (bounded as well: {@code FLOOD}), and one watchdog thread (heartbeat, write timeout, the late
 * {@code Initial}). All are daemon threads and end when the connection is closed ({@link #awaitTermination}).
 *
 * <p><b>Version 1.</b> A receiver of version 1 has no {@code Ping}, so it is never pinged and silence does not end the connection (a failing socket does).
 *
 * <p><b>Errors.</b> A framing error (size 0, over 32000 bytes, truncated) ends the connection; a body that does not parse is
 * dropped and logged, the connection goes on; an opcode that is not known is skipped.
 */
public final class FCastConnection implements AutoCloseable {
    /** Why a connection ended. */
    public enum CloseReason {
        /** {@link #close()} was called. */
        CLOSED_BY_US,
        /** The receiver closed the connection in an orderly way. */
        PEER_CLOSED,
        /** The stream failed (a reset, a network change). */
        IO_ERROR,
        /** The receiver broke the framing rules. */
        PROTOCOL_ERROR,
        /** Nothing was received for {@code deadAfterMillis}. */
        SILENT,
        /** The write queue was full: the receiver does not read. */
        WRITE_OVERFLOW,
        /** One write took longer than {@code writeTimeoutMillis}. */
        WRITE_TIMEOUT,
        /** The listener is too slow for what the receiver sends. */
        FLOOD
    }

    /** Receives what the connection learns, in order, on one thread. Must not block for long. */
    public interface Listener {
        /** A parsed message of the receiver ({@code Version}, {@code Initial}, updates, errors, events). */
        void onMessage(FCastMessages.Incoming message);

        /** The connection ended; the last call, once. */
        void onClosed(CloseReason reason, String detail);
    }

    /** The times and sizes, injectable so tests do not wait. */
    public static final class Config {
        public long connectTimeoutMillis = 5_000;
        public long pingAfterMillis = 5_000;
        public long deadAfterMillis = 10_000;
        public long writeTimeoutMillis = 10_000;
        public long initialWaitMillis = 1_000;
        public long tickMillis = 250;
        public int writeQueueCapacity = 64;
        public int listenerQueueCapacity = 256;
    }

    private static final String TAG = FCastConnection.class.getName();

    private final InetSocketAddress target;
    private final Listener listener;
    private final Config config;
    private final MonotonicClock clock;
    private final String senderName;
    private final String appName;
    private final String appVersion;

    private final BlockingQueue<byte[]> writeQueue;
    private final BlockingQueue<Runnable> listenerQueue;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object heartbeatLock = new Object();

    private volatile Socket socket;
    private volatile int peerVersion;           // 0 until the receiver's Version arrived
    private volatile boolean initialSent;
    private volatile long writeStartedAt;       // 0 when no write is going on
    private FCastHeartbeat heartbeat;
    private long connectedAt;
    private Thread reader;
    private Thread writer;
    private Thread dispatcher;
    private Thread watchdog;

    /**
     * @param target      the receiver's address
     * @param listener    receives the messages
     * @param config      times and sizes
     * @param clock       the time source (monotonic)
     * @param senderName  the name sent in {@code Initial} ({@code displayName})
     * @param appName     sent in {@code Initial}
     * @param appVersion  sent in {@code Initial}
     */
    public FCastConnection(InetSocketAddress target, Listener listener, Config config, MonotonicClock clock,
                           String senderName, String appName, String appVersion) {
        this.target = target;
        this.listener = listener;
        this.config = config;
        this.clock = clock;
        this.senderName = senderName;
        this.appName = appName;
        this.appVersion = appVersion;
        this.writeQueue = new LinkedBlockingQueue<>(config.writeQueueCapacity);
        this.listenerQueue = new LinkedBlockingQueue<>(config.listenerQueueCapacity);
    }

    /**
     * Connects and starts the threads; the connection is usable when this returns. Blocking: not for the main thread.
     *
     * @throws IOException if the receiver can not be reached within {@code connectTimeoutMillis}
     */
    public void connect() throws IOException {
        Socket s = new Socket();
        try {
            s.connect(target, (int) config.connectTimeoutMillis);
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
        } catch (IOException e) {
            try {
                s.close();
            } catch (IOException ignored) {
                // nothing to close
            }
            throw e;
        }
        socket = s;
        connectedAt = clock.nowMillis();
        synchronized (heartbeatLock) {
            heartbeat = new FCastHeartbeat(connectedAt, config.pingAfterMillis, config.deadAfterMillis);
        }
        reader = daemon("fcast-reader", this::readLoop);
        writer = daemon("fcast-writer", this::writeLoop);
        dispatcher = daemon("fcast-dispatcher", this::dispatchLoop);
        watchdog = daemon("fcast-watchdog", this::watchLoop);
        // the sender's Version goes first, before anything the receiver says
        enqueue(FCastOpcode.VERSION, FCastMessages.version(FCastMessages.MAX_VERSION));
        reader.start();
        writer.start();
        dispatcher.start();
        watchdog.start();
    }

    private static Thread daemon(String name, Runnable body) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        return thread;
    }

    /** The receiver's address. */
    public InetSocketAddress getTarget() {
        return target;
    }

    /** The version the receiver announced (1 or more), 0 while it has not said. */
    public int getPeerVersion() {
        return peerVersion;
    }

    /** The version this connection speaks: the smaller of the receiver's and {@link FCastMessages#MAX_VERSION}; 3 until the receiver said. */
    public int getNegotiatedVersion() {
        int peer = peerVersion;
        return peer == 0 ? FCastMessages.MAX_VERSION : Math.min(peer, FCastMessages.MAX_VERSION);
    }

    public boolean isClosed() {
        return closed.get();
    }

    /**
     * Queues a message for the receiver. Never blocks.
     *
     * @param opcode a sender opcode of the negotiated version
     * @param body   the JSON text or null
     * @return false if the connection is closed (or the queue overflowed and the connection was closed by this call)
     * @throws FCastProtocolException {@code UNSUPPORTED} if the receiver's version does not have the opcode (say a {@code SetSpeed}
     *                                to a version 1 receiver), {@code TOO_LARGE} if the message does not fit into a packet
     */
    public boolean send(FCastOpcode opcode, String body) throws FCastProtocolException {
        if (opcode.sinceVersion() > getNegotiatedVersion()) {
            throw new FCastProtocolException(FCastProtocolException.Kind.UNSUPPORTED,
                    opcode + " does not exist in protocol version " + getNegotiatedVersion() + " of this receiver");
        }
        return enqueue(opcode, body);
    }

    private boolean enqueue(FCastOpcode opcode, String body) throws FCastProtocolException {
        byte[] packet = FCastPacketCodec.encode(opcode.code(), body);
        if (closed.get()) {
            return false;
        }
        if (!writeQueue.offer(packet)) {
            close(CloseReason.WRITE_OVERFLOW, "the receiver does not read (" + config.writeQueueCapacity + " messages waiting)");
            return false;
        }
        return true;
    }

    private void sendQuietly(FCastOpcode opcode, String body) {
        try {
            enqueue(opcode, body);
        } catch (FCastProtocolException e) {
            YaaccLogger.w(TAG, "FCast: " + opcode + " not sent: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ threads

    private void readLoop() {
        try {
            InputStream in = socket.getInputStream();
            FCastFrameReader frames = new FCastFrameReader(in);
            while (!closed.get()) {
                FCastPacket packet;
                try {
                    packet = frames.read();
                } catch (FCastProtocolException e) {
                    if (e.endsConnection()) {
                        close(CloseReason.PROTOCOL_ERROR, e.getMessage());
                        return;
                    }
                    YaaccLogger.w(TAG, "FCast: a packet was dropped: " + e.getMessage());
                    markAlive();
                    continue;
                }
                if (packet == null) {
                    close(CloseReason.PEER_CLOSED, "the receiver closed the connection");
                    return;
                }
                markAlive();
                handle(packet);
            }
        } catch (IOException e) {
            if (!closed.get()) {
                close(CloseReason.IO_ERROR, e.getClass().getSimpleName());
            }
        }
    }

    private void markAlive() {
        synchronized (heartbeatLock) {
            heartbeat.onPacket(clock.nowMillis());
        }
    }

    private void handle(FCastPacket packet) {
        FCastOpcode opcode = packet.getOpcode();
        if (opcode == null) {
            YaaccLogger.d(TAG, "FCast: an unknown opcode " + packet.getOpcodeCode() + " was skipped");
            return;
        }
        if (opcode == FCastOpcode.PING) {
            if (getNegotiatedVersion() >= 2) {
                sendQuietly(FCastOpcode.PONG, null);
            }
            return;
        }
        FCastMessages.Incoming message;
        try {
            message = FCastMessages.parse(packet);
        } catch (FCastProtocolException e) {
            YaaccLogger.w(TAG, "FCast: " + opcode + " was dropped: " + e.getMessage());
            return;
        }
        if (message == null) {
            return;
        }
        if (message instanceof FCastMessages.Version) {
            peerVersion = ((FCastMessages.Version) message).getVersion();
            sendInitialIfDue();
        }
        deliver(() -> listener.onMessage(message));
    }

    /** Our {@code Initial}, once, when the receiver speaks version 3 (see the class comment). */
    private synchronized void sendInitialIfDue() {
        if (initialSent || getNegotiatedVersion() < 3) {
            return;
        }
        initialSent = true;
        sendQuietly(FCastOpcode.INITIAL, FCastMessages.initialSender(senderName, appName, appVersion));
    }

    private void writeLoop() {
        try {
            OutputStream out = socket.getOutputStream();
            while (!closed.get()) {
                byte[] packet = writeQueue.poll(100, TimeUnit.MILLISECONDS);
                if (packet == null) {
                    continue;
                }
                writeStartedAt = clock.nowMillis();
                out.write(packet);
                out.flush();
                writeStartedAt = 0;
            }
        } catch (IOException e) {
            if (!closed.get()) {
                close(CloseReason.IO_ERROR, e.getClass().getSimpleName());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void dispatchLoop() {
        try {
            while (true) {
                Runnable task = listenerQueue.take();
                try {
                    task.run();
                } catch (RuntimeException e) {
                    YaaccLogger.e(TAG, "FCast: a listener failed: " + e);
                }
                if (task instanceof CloseTask) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void watchLoop() {
        try {
            while (!closed.get()) {
                Thread.sleep(config.tickMillis);
                long now = clock.nowMillis();
                long started = writeStartedAt;
                if (started != 0 && now - started >= config.writeTimeoutMillis) {
                    close(CloseReason.WRITE_TIMEOUT, "a write took more than " + config.writeTimeoutMillis + " ms");
                    return;
                }
                FCastHeartbeat.Action action;
                synchronized (heartbeatLock) {
                    action = heartbeat.tick(now);
                }
                if (getNegotiatedVersion() < 2) {
                    action = FCastHeartbeat.Action.NONE; // a version 1 receiver has no Ping to ask with: silence proves nothing
                }
                if (action == FCastHeartbeat.Action.DEAD) {
                    close(CloseReason.SILENT, "nothing received for " + config.deadAfterMillis + " ms");
                    return;
                }
                if (action == FCastHeartbeat.Action.PING && getNegotiatedVersion() >= 2) {
                    sendQuietly(FCastOpcode.PING, null);
                }
                if (!initialSent && peerVersion == 0 && now - connectedAt >= config.initialWaitMillis) {
                    sendInitialIfDue();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ listener delivery and closing

    private void deliver(Runnable task) {
        if (closed.get()) {
            return;
        }
        if (!listenerQueue.offer(task)) {
            close(CloseReason.FLOOD, "the listener does not keep up (" + config.listenerQueueCapacity + " messages waiting)");
        }
    }

    private static final class CloseTask implements Runnable {
        private final Runnable body;

        CloseTask(Runnable body) {
            this.body = body;
        }

        @Override
        public void run() {
            body.run();
        }
    }

    private void close(CloseReason reason, String detail) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing
            }
        }
        for (Thread thread : new Thread[]{writer, watchdog}) {
            if (thread != null && thread != Thread.currentThread()) {
                thread.interrupt();
            }
        }
        CloseTask end = new CloseTask(() -> listener.onClosed(reason, detail));
        if (dispatcher == null) {
            return; // never connected: nobody listens
        }
        // the messages that came before the end are delivered first; when the listener is stuck the end replaces them
        boolean fatalForQueue = reason == CloseReason.FLOOD || reason == CloseReason.CLOSED_BY_US;
        if (fatalForQueue) {
            listenerQueue.clear();
        }
        try {
            if (!listenerQueue.offer(end, 1, TimeUnit.SECONDS)) {
                listenerQueue.clear();
                listenerQueue.offer(end);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            listenerQueue.clear();
            listenerQueue.offer(end);
        }
    }

    /** Closes the connection; the listener gets {@code onClosed(CLOSED_BY_US)}. Safe to call again. */
    @Override
    public void close() {
        close(CloseReason.CLOSED_BY_US, "closed");
    }

    /**
     * For tests and for an orderly exit: waits until all threads of this connection ended.
     *
     * @return true if they did within the time
     */
    public boolean awaitTermination(long millis) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        for (Thread thread : new Thread[]{reader, writer, dispatcher, watchdog}) {
            if (thread == null) {
                continue;
            }
            long left = TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime());
            if (left <= 0) {
                return false;
            }
            thread.join(left);
            if (thread.isAlive()) {
                return false;
            }
        }
        return true;
    }

}
