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
import static org.junit.Assert.fail;

import de.yaacc.multiroom.control.RetryScheduler;
import de.yaacc.multiroom.control.SnapcastTransport;
import de.yaacc.multiroom.control.SocketTransport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** The loopback server, the recording listener, the manual retry timer and a counting transport for the connection tests. */
final class ConnectionFixtures {

    static final long WAIT_SECONDS = 20;

    private ConnectionFixtures() {
    }

    /** The literal loopback address: no name lookup is involved. */
    static InetAddress loopback() {
        try {
            return InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        } catch (java.net.UnknownHostException e) {
            throw new AssertionError(e);
        }
    }

    // ---- the server

    interface Script {
        void run(Conn connection) throws Exception;
    }

    /** One accepted connection as the script sees it. */
    static final class Conn {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private final FrameReader reader;

        Conn(Socket socket) throws IOException {
            this.socket = socket;
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
            this.reader = new FrameReader(in, MicroClock.monotonic(), 60_000);
        }

        /** The next frame the client sent. */
        Frame readFrame() throws Exception {
            return reader.read();
        }

        /** Reads frames until one with this message class arrives. */
        <T extends SnapcastBinaryMessage> T readMessage(Class<T> type) throws Exception {
            while (true) {
                Frame frame = readFrame();
                if (type.isInstance(frame.getMessage())) {
                    return type.cast(frame.getMessage());
                }
            }
        }

        /** Reads frames until a Time request arrives and returns it. */
        Frame readTimeRequest() throws Exception {
            while (true) {
                Frame frame = readFrame();
                if (frame.getMessage() instanceof TimeMessage) {
                    return frame;
                }
            }
        }

        void send(byte[] bytes) throws IOException {
            out.write(bytes);
            out.flush();
        }

        void send(SnapcastBinaryMessage message) throws IOException {
            send(message, 0, 0, 0);
        }

        void send(SnapcastBinaryMessage message, int id, int refersTo, long sentMicros) throws IOException {
            send(SnapcastMessageCodec.encode(message, id, refersTo, sentMicros));
        }

        /** Blocks until the client closes its end (or the connection breaks). */
        void awaitClientClose() {
            try {
                byte[] sink = new byte[4096];
                while (in.read(sink) >= 0) {
                    // the client may still send time requests
                }
            } catch (IOException ignored) {
                // closed
            }
        }

        /** Sends the FIN and keeps the connection open, so that the client reads everything before it sees the end. */
        void shutdownOutput() {
            try {
                socket.shutdownOutput();
            } catch (IOException ignored) {
                // closing
            }
        }

        /** The FIN, then reading what the client still sends until it closes or a short wait has passed. */
        void finish() {
            try {
                socket.shutdownOutput();
                socket.setSoTimeout(2000);
                byte[] sink = new byte[4096];
                while (in.read(sink) >= 0) {
                    // dropped
                }
            } catch (IOException ignored) {
                // closed or timed out
            }
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing
            }
        }
    }

    static final class FakeServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final Script script;
        private final AtomicInteger accepted = new AtomicInteger();
        private final List<Conn> connections = new CopyOnWriteArrayList<>();
        private final Thread acceptor;
        private volatile boolean closed;

        FakeServer(Script script) throws IOException {
            this.script = script;
            this.serverSocket = new ServerSocket(0, 50, loopback());
            this.acceptor = new Thread(this::acceptLoop, "fake-snapcast-acceptor");
            this.acceptor.setDaemon(true);
            this.acceptor.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        int accepted() {
            return accepted.get();
        }

        private void acceptLoop() {
            while (!closed) {
                final Socket socket;
                try {
                    socket = serverSocket.accept();
                } catch (IOException e) {
                    return;
                }
                accepted.incrementAndGet();
                Thread worker = new Thread(() -> {
                    Conn connection = null;
                    try {
                        connection = new Conn(socket);
                        connections.add(connection);
                        script.run(connection);
                    } catch (Exception ignored) {
                        // the client went away or the test is over
                    } finally {
                        if (connection != null) {
                            // a graceful end: the FIN first, then the client's unread bytes (its time requests) are read
                            // and dropped. A plain close with unread input would send a reset that can wipe the data the
                            // client has not read yet.
                            connection.finish();
                            connection.close();
                        } else {
                            try {
                                socket.close();
                            } catch (IOException ignored) {
                                // closing
                            }
                        }
                    }
                }, "fake-snapcast-connection");
                worker.setDaemon(true);
                worker.start();
            }
        }

        @Override
        public void close() {
            closed = true;
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // closing
            }
            for (Conn connection : connections) {
                connection.close();
            }
        }
    }

    // ---- what the connection reports

    static final class OffsetUpdate {
        final long offsetMicros;
        final long jitterMicros;

        OffsetUpdate(long offsetMicros, long jitterMicros) {
            this.offsetMicros = offsetMicros;
            this.jitterMicros = jitterMicros;
        }
    }

    static final class GaveUp {
        final SnapcastConnection.Loss loss;

        GaveUp(SnapcastConnection.Loss loss) {
            this.loss = loss;
        }
    }

    /** Records every callback in order, with the name of the thread that made it. */
    static class Recorder implements SnapcastConnection.Listener {
        final BlockingQueue<Object> events = new LinkedBlockingQueue<>();
        final List<String> threads = Collections.synchronizedList(new ArrayList<String>());

        private void record(Object event) {
            threads.add(Thread.currentThread().getName());
            events.add(event);
        }

        @Override
        public void onServerSettings(ServerSettingsMessage settings) {
            record(settings);
        }

        @Override
        public void onCodecHeader(CodecHeaderMessage header) {
            record(header);
        }

        @Override
        public void onChunk(WireChunkMessage chunk) {
            record(chunk);
        }

        @Override
        public void onOffsetUpdate(long offsetMicros, long jitterMicros) {
            record(new OffsetUpdate(offsetMicros, jitterMicros));
        }

        @Override
        public void onLost(SnapcastConnection.Loss loss) {
            record(loss);
        }

        @Override
        public void onGaveUp(SnapcastConnection.Loss loss) {
            record(new GaveUp(loss));
        }

        /** The next event, which must be of this class. */
        <T> T next(Class<T> type) throws InterruptedException {
            Object event = events.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            assertNotNull("no " + type.getSimpleName() + " within " + WAIT_SECONDS + " s", event);
            if (!type.isInstance(event)) {
                fail("expected " + type.getSimpleName() + " but got " + event.getClass().getSimpleName());
            }
            return type.cast(event);
        }

        /** Skips events until one of this class arrives (for tests that do not care what comes before). */
        <T> T nextOf(Class<T> type) throws InterruptedException {
            while (true) {
                Object event = events.poll(WAIT_SECONDS, TimeUnit.SECONDS);
                assertNotNull("no " + type.getSimpleName() + " within " + WAIT_SECONDS + " s", event);
                if (type.isInstance(event)) {
                    return type.cast(event);
                }
            }
        }

        List<Object> drain() {
            List<Object> out = new ArrayList<>();
            events.drainTo(out);
            return out;
        }
    }

    // ---- retry timer and transport

    /** Collects the retry tasks; the test runs them when it wants, so no backoff is waited for. */
    static final class ManualScheduler implements RetryScheduler {
        private final BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
        final List<Long> delays = Collections.synchronizedList(new ArrayList<Long>());

        @Override
        public void schedule(Runnable task, long delayMillis) {
            delays.add(delayMillis);
            tasks.add(task);
        }

        int pending() {
            return tasks.size();
        }

        /** Waits for the next scheduled task and returns it without running it. */
        Runnable take() throws InterruptedException {
            Runnable task = tasks.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            assertNotNull("no retry was scheduled", task);
            return task;
        }

        /** Waits for the next scheduled task and runs it on the calling thread. */
        void runNext() throws InterruptedException {
            Runnable task = tasks.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            assertNotNull("no retry was scheduled", task);
            task.run();
        }
    }

    /** Opens the loopback server; the first {@code failures} attempts fail with an IOException. */
    static final class CountingTransport implements SnapcastTransport {
        private final int port;
        private final AtomicInteger opens = new AtomicInteger();
        private volatile int failures;

        CountingTransport(int port, int failures) {
            this.port = port;
            this.failures = failures;
        }

        int opens() {
            return opens.get();
        }

        void failFromNow(int count) {
            failures = count;
        }

        @Override
        public Connection open(int connectTimeoutMillis) throws IOException {
            opens.incrementAndGet();
            if (failures > 0) {
                failures--;
                throw new IOException("scripted failure");
            }
            return new SocketTransport(loopback(), port).open(connectTimeoutMillis);
        }
    }
}
