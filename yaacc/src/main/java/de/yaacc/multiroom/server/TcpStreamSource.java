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

import de.yaacc.multiroom.protocol.MicroClock;
import de.yaacc.util.YaaccLogger;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.function.Predicate;

/**
 * A stream source of the kind {@code tcp} in server mode (issue #330, task 2.4): the server listens on a port and one producer connects
 * and pushes raw PCM (no header). The server reads in real time like snapserver: after each chunk it waits until the chunk is over, so a
 * producer that is ahead is slowed by TCP and nothing piles up; the receive buffer is small for the same reason. Only addresses the filter
 * allows are served (rule S2), one producer at a time (a second is closed at once), and a producer that sends nothing for 30 seconds
 * is let go (rule S3).
 */
public final class TcpStreamSource implements Closeable {

    private static final int RECEIVE_BUFFER_BYTES = 64 * 1024;
    private static final int PRODUCER_IDLE_MILLIS = 30_000;
    private static final int READ_SLICE_MILLIS = 1_000;

    private final StreamPipeline pipeline;
    private final int requestedPort;
    private final Predicate<InetAddress> accept;
    private final MicroClock clock;
    private final Object lock = new Object();

    private volatile ServerSocket serverSocket;
    private Socket producer;
    private boolean closed;

    public TcpStreamSource(StreamPipeline pipeline, int port, Predicate<InetAddress> accept, MicroClock clock) {
        this.pipeline = pipeline;
        this.requestedPort = port;
        this.accept = accept;
        this.clock = clock;
    }

    /** Binds the port now, so that a taken port fails at once (as snapserver does when a stream is added). */
    public void start() throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.setReceiveBufferSize(RECEIVE_BUFFER_BYTES);
        socket.bind(new InetSocketAddress(requestedPort), 2);
        serverSocket = socket;
        Thread t = new Thread(() -> acceptLoop(socket), "snapcast-server-source-" + pipeline.id());
        t.setDaemon(true);
        t.start();
    }

    /** The port that is bound, or -1. */
    public int port() {
        ServerSocket s = serverSocket;
        return s == null || s.isClosed() ? -1 : s.getLocalPort();
    }

    private void acceptLoop(ServerSocket socket) {
        while (!socket.isClosed()) {
            Socket client;
            try {
                client = socket.accept();
            } catch (SocketException closedSocket) {
                return;
            } catch (IOException e) {
                continue;
            }
            boolean take;
            synchronized (lock) {
                take = !closed && producer == null && accept.test(client.getInetAddress());
                if (take) {
                    producer = client;
                }
            }
            if (!take) {
                closeQuietly(client);
                continue;
            }
            Thread t = new Thread(() -> read(client), "snapcast-server-producer-" + pipeline.id());
            t.setDaemon(true);
            t.start();
        }
    }

    private void read(Socket client) {
        try {
            client.setSoTimeout(READ_SLICE_MILLIS);
            InputStream in = client.getInputStream();
            byte[] buffer = new byte[pipeline.chunkBytes()];
            long lastData = clock.nowMicros();
            int have = 0;
            while (true) {
                int n;
                try {
                    n = in.read(buffer, have, buffer.length - have);
                } catch (SocketTimeoutException slice) {
                    if ((clock.nowMicros() - lastData) / 1000L > PRODUCER_IDLE_MILLIS) {
                        break;
                    }
                    continue;
                }
                if (n < 0) {
                    break;
                }
                long now = clock.nowMicros();
                lastData = now;
                have += n;
                if (have == buffer.length) {
                    pipeline.write(buffer, 0, have, now);
                    have = 0;
                    long wait = pipeline.waitMicros(clock.nowMicros());
                    if (wait > 0) {
                        Thread.sleep(wait / 1000L, (int) (wait % 1000L) * 1000);
                    }
                }
            }
        } catch (IOException e) {
            YaaccLogger.d(getClass().getName(), "a producer ended: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeQuietly(client);
            pipeline.producerGone();
            synchronized (lock) {
                producer = null;
            }
        }
    }

    @Override
    public void close() {
        Socket active;
        synchronized (lock) {
            closed = true;
            active = producer;
        }
        ServerSocket s = serverSocket;
        serverSocket = null;
        if (s != null) {
            closeQuietly(s);
        }
        if (active != null) {
            closeQuietly(active);
        }
    }

    private static void closeQuietly(Closeable c) {
        try {
            c.close();
        } catch (IOException ignored) {
            // closed
        }
    }
}
