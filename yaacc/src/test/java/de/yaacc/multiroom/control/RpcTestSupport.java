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
package de.yaacc.multiroom.control;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Small fakes for the RPC tests: scheduler, listener, waiting. */
final class RpcTestSupport {

    private RpcTestSupport() {
    }

    /** Collects the retry tasks; the test runs them when it wants (no sleeping for backoff). */
    static final class ManualScheduler implements RetryScheduler {
        private final List<Runnable> tasks = Collections.synchronizedList(new ArrayList<Runnable>());
        private final List<Long> delays = Collections.synchronizedList(new ArrayList<Long>());

        @Override
        public void schedule(Runnable task, long delayMillis) {
            delays.add(delayMillis);
            tasks.add(task);
        }

        int pending() {
            return tasks.size();
        }

        List<Long> delays() {
            synchronized (delays) {
                return new ArrayList<>(delays);
            }
        }

        long lastDelay() {
            synchronized (delays) {
                return delays.get(delays.size() - 1);
            }
        }

        /** Runs (and removes) the oldest waiting task on the calling thread. */
        void runNext() {
            Runnable task;
            synchronized (tasks) {
                task = tasks.remove(0);
            }
            task.run();
        }

        void clear() {
            tasks.clear();
        }
    }

    /** A clock the test moves by hand. */
    static final class ManualClock implements MonotonicClock {
        final AtomicLong now = new AtomicLong();

        @Override
        public long nowMillis() {
            return now.get();
        }

        void advance(long millis) {
            now.addAndGet(millis);
        }
    }

    /** Records everything the RPC tells, with the thread it was told on. */
    static final class RecordingListener implements SnapcastRpc.Listener {
        private final List<String> log = Collections.synchronizedList(new ArrayList<String>());
        final List<JSONObject> notifications = Collections.synchronizedList(new ArrayList<JSONObject>());
        final List<String> threads = Collections.synchronizedList(new ArrayList<String>());
        volatile SnapcastException lastCause;

        @Override
        public void onConnected() {
            log.add("connected");
        }

        @Override
        public void onNotification(String method, JSONObject params) {
            threads.add(Thread.currentThread().getName());
            notifications.add(params);
            log.add("notification:" + method);
        }

        @Override
        public void onNotificationsLost() {
            log.add("lost");
        }

        @Override
        public void onDisconnected(SnapcastException cause) {
            lastCause = cause;
            log.add("disconnected:" + cause.getKind());
        }

        @Override
        public void onGaveUp(SnapcastException cause) {
            lastCause = cause;
            log.add("gaveup");
        }

        int count(String prefix) {
            int count = 0;
            synchronized (log) {
                for (String entry : log) {
                    if (entry.startsWith(prefix)) {
                        count++;
                    }
                }
            }
            return count;
        }

        boolean awaitCount(final String prefix, final int atLeast, long timeoutMillis) {
            return await(() -> count(prefix) >= atLeast, timeoutMillis);
        }

        List<String> log() {
            synchronized (log) {
                return new ArrayList<>(log);
            }
        }
    }

    static boolean await(BooleanSupplier condition, long timeoutMillis) {
        long end = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    /** A transport whose connection fails, so that the reconnect logic can be driven without sockets. */
    static final class FailingTransport implements SnapcastTransport {
        volatile int attempts;

        @Override
        public Connection open(int connectTimeoutMillis) throws IOException {
            attempts++;
            throw new IOException("refused");
        }
    }

    /** A connection whose input throws what the test wants (an Error, for example). */
    static final class ThrowingConnection implements SnapcastTransport.Connection {
        private final Throwable toThrow;

        ThrowingConnection(Throwable toThrow) {
            this.toThrow = toThrow;
        }

        @Override
        public InputStream getInputStream() {
            return new InputStream() {
                @Override
                public int read() {
                    throwIt();
                    return -1;
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    throwIt();
                    return -1;
                }
            };
        }

        private void throwIt() {
            if (toThrow instanceof Error) {
                throw (Error) toThrow;
            }
            throw (RuntimeException) toThrow;
        }

        @Override
        public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override
                public void write(int b) {
                    // nothing
                }
            };
        }

        @Override
        public void setReadTimeout(int millis) {
            // nothing
        }

        @Override
        public InetAddress getPeerAddress() {
            return null;
        }

        @Override
        public void close() {
            // nothing
        }
    }

    /** A listener that blocks inside the first notification until the test lets it go. */
    static final class BlockingListener implements SnapcastRpc.Listener {
        final AtomicInteger delivered = new AtomicInteger();
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void onNotification(String method, JSONObject params) {
            delivered.incrementAndGet();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        void release() {
            release.countDown();
        }
    }

    /**
     * A connection to a server that has stopped reading: a write never returns (until the connection is closed) and
     * nothing is ever received. This does not depend on the size of the socket buffers of the machine.
     */
    static final class StuckWriteConnection implements SnapcastTransport.Connection {
        private final CountDownLatch closed = new CountDownLatch(1);

        boolean isClosed() {
            return closed.getCount() == 0;
        }

        private void blockUntilClosed() {
            try {
                closed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public InputStream getInputStream() {
            return new InputStream() {
                @Override
                public int read() {
                    blockUntilClosed();
                    return -1;
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    blockUntilClosed();
                    return -1;
                }
            };
        }

        @Override
        public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    blockUntilClosed();
                    throw new IOException("closed");
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    blockUntilClosed();
                    throw new IOException("closed");
                }
            };
        }

        @Override
        public void setReadTimeout(int millis) {
            // nothing
        }

        @Override
        public InetAddress getPeerAddress() {
            return null;
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }
}
