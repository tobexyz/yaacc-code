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

import org.json.JSONObject;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The control port of the built-in server (issue #330, task 3.2), by default 1705: the listener, the filter of addresses (rule S2,
 * nothing is read before it passed), at most {@code maxConnections} at a time (S3), and the hub that sends notifications to the open
 * connections. Connections that do not come over a socket (the app's own, in process) join through {@link #open}.
 */
public final class ControlServer implements ControlSession.Hub, AutoCloseable {

    private final SnapcastServer server;
    private final RpcDispatcher dispatcher;
    private final Predicate<InetAddress> accept;
    private final int maxConnections;
    private final List<ControlSession> sessions = new ArrayList<>();
    private final Object lock = new Object();

    private volatile ServerSocket serverSocket;
    private int sockets;

    public ControlServer(SnapcastServer server, Predicate<InetAddress> accept, int maxConnections) {
        this.server = server;
        this.dispatcher = new RpcDispatcher(server);
        this.accept = accept;
        this.maxConnections = maxConnections;
        server.setNotifier(list -> broadcast(list, null));
    }

    public synchronized void start(int port) throws IOException {
        if (serverSocket != null) {
            return;
        }
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(port), 4);
        serverSocket = socket;
        Thread t = new Thread(() -> acceptLoop(socket), "snapcast-server-control-accept");
        t.setDaemon(true);
        t.start();
    }

    public int port() {
        ServerSocket s = serverSocket;
        return s == null || s.isClosed() ? -1 : s.getLocalPort();
    }

    private void acceptLoop(ServerSocket socket) {
        while (!socket.isClosed()) {
            Socket client;
            try {
                client = socket.accept();
            } catch (SocketException closed) {
                return;
            } catch (IOException e) {
                continue;
            }
            boolean room;
            synchronized (lock) {
                room = sockets < maxConnections && accept.test(client.getInetAddress());
                if (room) {
                    sockets++;
                }
            }
            if (!room) {
                closeQuietly(client);
                continue;
            }
            try {
                client.setSoTimeout(2_000);
                client.setTcpNoDelay(true);
                ControlSession session = new ControlSession(client.getInputStream(), client.getOutputStream(), client, dispatcher,
                        new ControlSession.Hub() {
                            @Override
                            public void broadcast(List<JSONObject> notifications, ControlSession origin) {
                                ControlServer.this.broadcast(notifications, origin);
                            }

                            @Override
                            public void ended(ControlSession ended) {
                                synchronized (lock) {
                                    sockets = Math.max(0, sockets - 1);
                                }
                                ControlServer.this.ended(ended);
                            }
                        });
                add(session);
                Thread t = new Thread(session, "snapcast-server-control");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                synchronized (lock) {
                    sockets--;
                }
                closeQuietly(client);
            }
        }
    }

    /** Adds a connection that is not a socket (in process): its streams and what closes it. Starts its thread. */
    public ControlSession open(java.io.InputStream in, java.io.OutputStream out, java.io.Closeable closer) {
        ControlSession session = new ControlSession(in, out, closer, dispatcher, this);
        add(session);
        Thread t = new Thread(session, "snapcast-server-control-local");
        t.setDaemon(true);
        t.start();
        return session;
    }

    private void add(ControlSession session) {
        synchronized (lock) {
            sessions.add(session);
        }
    }

    @Override
    public void broadcast(List<JSONObject> notifications, ControlSession origin) {
        if (notifications.isEmpty()) {
            return;
        }
        List<ControlSession> all;
        synchronized (lock) {
            all = new ArrayList<>(sessions);
        }
        for (ControlSession s : all) {
            if (s != origin) {
                for (JSONObject n : notifications) {
                    s.send(n);
                }
            }
        }
    }

    @Override
    public void ended(ControlSession session) {
        synchronized (lock) {
            sessions.remove(session);
        }
    }

    @Override
    public void close() {
        ServerSocket s = serverSocket;
        serverSocket = null;
        if (s != null) {
            closeQuietly(s);
        }
        List<ControlSession> all;
        synchronized (lock) {
            all = new ArrayList<>(sessions);
        }
        for (ControlSession session : all) {
            session.close();
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        try {
            c.close();
        } catch (IOException ignored) {
            // closed
        }
    }
}
