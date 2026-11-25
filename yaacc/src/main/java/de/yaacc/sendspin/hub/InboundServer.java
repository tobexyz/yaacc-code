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
package de.yaacc.sendspin.hub;

import de.yaacc.sendspin.session.WsConnection;
import de.yaacc.sendspin.session.WsHandshake;
import de.yaacc.util.YaaccLogger;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * The listening side of the client-initiated mode (spec, "Client Initiated Connections"): a client that finds this server by mDNS
 * ({@code _sendspin-server._tcp}) opens a WebSocket to it and speaks first. Opt-in (the user's switch, off by default) and as small
 * as it can be: only addresses the filter accepts are read at all, a request with an {@code Origin} header or for another path is
 * refused, a client that does not finish its upgrade within the timeout is dropped, and at most {@code maxConnections} are held at
 * once (a closed link makes room). The handshake and everything after it belong to the hub connection. Closing the server stops
 * new connections only; established ones go on. Nothing is read from a connection before it passed the filter.
 */
public final class InboundServer {

    /** Receives a connection whose WebSocket upgrade is done. */
    public interface Listener {
        void onConnection(Connector.Link link, InetAddress remote);

        /** As above, with the port the client connected from (several clients behind one address); by default it is the same call. */
        default void onConnection(Connector.Link link, InetAddress remote, int remotePort) {
            onConnection(link, remote);
        }
    }

    private static final int MAX_MESSAGE_BYTES = 1 << 20;
    private static final int UPGRADE_THREADS = 4;

    private final int requestedPort;
    private final String path;
    private final Predicate<InetAddress> accept;
    private final Listener listener;
    private final int maxConnections;
    private final int timeoutMillis;
    private final SecureRandom random = new SecureRandom();
    private final AtomicInteger held = new AtomicInteger();

    private volatile ServerSocket serverSocket;
    private ExecutorService upgrades;
    private Thread acceptor;

    /**
     * @param port           the port to listen on, 0 for any free one
     * @param path           the only request path that is served
     * @param accept         which remote addresses are read at all (the local network)
     * @param timeoutMillis  how long the upgrade of one client may take
     */
    public InboundServer(int port, String path, Predicate<InetAddress> accept, Listener listener, int maxConnections, int timeoutMillis) {
        this.requestedPort = port;
        this.path = path;
        this.accept = accept;
        this.listener = listener;
        this.maxConnections = maxConnections;
        this.timeoutMillis = timeoutMillis;
    }

    /** Binds the port and starts accepting. @throws IOException if the port can not be bound */
    public synchronized void start() throws IOException {
        if (serverSocket != null) {
            return;
        }
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(requestedPort), 8);
        serverSocket = socket;
        // no queue: when every upgrade thread is busy a further client is refused at once
        upgrades = new ThreadPoolExecutor(UPGRADE_THREADS, UPGRADE_THREADS, 30, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(), r -> {
            Thread t = new Thread(r, "sendspin-inbound-upgrade");
            t.setDaemon(true);
            return t;
        });
        acceptor = new Thread(() -> acceptLoop(socket), "sendspin-inbound-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /** The port that is bound (the real one when 0 was asked for), or -1 if the server is not running. */
    public int port() {
        ServerSocket s = serverSocket;
        return s == null || s.isClosed() ? -1 : s.getLocalPort();
    }

    public boolean isRunning() {
        ServerSocket s = serverSocket;
        return s != null && !s.isClosed();
    }

    /** Stops accepting. Connections that are established are not touched. */
    public synchronized void close() {
        ServerSocket s = serverSocket;
        serverSocket = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closed
            }
        }
        if (upgrades != null) {
            upgrades.shutdown();
            upgrades = null;
        }
    }

    private void acceptLoop(ServerSocket socket) {
        while (!socket.isClosed()) {
            Socket client;
            try {
                client = socket.accept();
            } catch (SocketException closed) {
                return;
            } catch (IOException e) {
                YaaccLogger.d(getClass().getName(), "accept failed: " + e.getClass().getSimpleName());
                continue;
            }
            if (!accept.test(client.getInetAddress()) || held.get() >= maxConnections) {
                closeQuietly(client);
                continue;
            }
            final ExecutorService executor;
            synchronized (this) {
                executor = upgrades;
            }
            if (executor == null) {
                closeQuietly(client);
                continue;
            }
            held.incrementAndGet(); // a slot is taken from the accept on, so that a flood is counted before its upgrades finish
            try {
                executor.execute(() -> upgrade(client));
            } catch (RuntimeException rejected) {
                held.decrementAndGet();
                closeQuietly(client);
            }
        }
    }

    private void upgrade(final Socket client) {
        boolean delivered = false;
        final AtomicInteger closed = new AtomicInteger(); // the slot is given back once, whoever closes first
        try {
            client.setSoTimeout(timeoutMillis);
            client.setTcpNoDelay(true);
            BufferedInputStream in = new BufferedInputStream(client.getInputStream());
            WsHandshake.Request request = WsHandshake.accept(in, client.getOutputStream(), true);
            if (!path.equals(request.path())) {
                throw new IOException("another path");
            }
            final WsConnection ws = new WsConnection(in, client.getOutputStream(), false, MAX_MESSAGE_BYTES, random);
            Connector.Link link = new Connector.Link() {
                @Override
                public WsConnection ws() {
                    return ws;
                }

                @Override
                public void close() throws IOException {
                    if (closed.getAndSet(1) == 0) {
                        held.decrementAndGet();
                    }
                    client.close();
                }
            };
            listener.onConnection(link, client.getInetAddress(), client.getPort());
            delivered = true; // only a link that the listener took is its to close; after an exception the socket and the slot are freed below
        } catch (IOException | RuntimeException e) {
            YaaccLogger.d(getClass().getName(), "inbound upgrade failed: " + e.getClass().getSimpleName());
        } finally {
            if (!delivered) {
                if (closed.getAndSet(1) == 0) {
                    held.decrementAndGet();
                }
                closeQuietly(client);
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // closed
        }
    }
}
