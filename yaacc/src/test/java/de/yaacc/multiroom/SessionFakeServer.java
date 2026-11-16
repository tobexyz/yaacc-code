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
package de.yaacc.multiroom;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A control server on the loopback address for the tests of the session and the receivers: it answers
 * {@code Server.GetStatus} with a status fixture (or with the failure a test queued), answers every other call with a
 * volume answer, and pushes notifications on request. The address is the literal 127.0.0.1.
 */
final class SessionFakeServer implements AutoCloseable {

    /** What the next {@code Server.GetStatus} does. */
    enum Mode { OK, MALFORMED, UNAUTHORIZED, SILENT }

    private final ServerSocket serverSocket;
    private final List<Socket> sockets = Collections.synchronizedList(new ArrayList<Socket>());
    private final Deque<Mode> statusModes = new ArrayDeque<>();
    private final AtomicInteger statusRequests = new AtomicInteger();
    private final AtomicInteger accepted = new AtomicInteger();
    private final List<String> methods = Collections.synchronizedList(new ArrayList<String>());
    private volatile String statusFixture;
    private volatile Mode defaultMode = Mode.OK;
    private volatile boolean closed;

    SessionFakeServer(String statusFixture) throws IOException {
        this.statusFixture = statusFixture;
        serverSocket = new ServerSocket(0, 50, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
        Thread accept = new Thread(() -> {
            while (!closed) {
                try {
                    final Socket socket = serverSocket.accept();
                    sockets.add(socket);
                    accepted.incrementAndGet();
                    Thread handler = new Thread(() -> serve(socket), "session-fake-connection");
                    handler.setDaemon(true);
                    handler.start();
                } catch (IOException e) {
                    return;
                }
            }
        }, "session-fake-accept");
        accept.setDaemon(true);
        accept.start();
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    int statusRequests() {
        return statusRequests.get();
    }

    int accepted() {
        return accepted.get();
    }

    List<String> methods() {
        synchronized (methods) {
            return new ArrayList<>(methods);
        }
    }

    /** The next status requests behave like this, one mode each; after that the default mode applies. */
    void queueModes(Mode... modes) {
        synchronized (statusModes) {
            for (Mode mode : modes) {
                statusModes.addLast(mode);
            }
        }
    }

    void setDefaultMode(Mode mode) {
        defaultMode = mode;
    }

    void setStatusFixture(String fixture) {
        statusFixture = fixture;
    }

    /** Sends a line to every connection. */
    void push(String line) {
        synchronized (sockets) {
            for (Socket socket : sockets) {
                try {
                    OutputStream out = socket.getOutputStream();
                    synchronized (out) {
                        out.write((line.replace("\n", " ") + "\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                } catch (IOException ignored) {
                    // that connection is gone
                }
            }
        }
    }

    void dropConnections() {
        synchronized (sockets) {
            for (Socket socket : sockets) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // closing
                }
            }
            sockets.clear();
        }
    }

    private Mode nextMode() {
        synchronized (statusModes) {
            Mode mode = statusModes.pollFirst();
            return mode == null ? defaultMode : mode;
        }
    }

    private void serve(Socket socket) {
        try {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) >= 0) {
                if (b != '\n') {
                    line.write(b);
                    continue;
                }
                JSONObject request = new JSONObject(new String(line.toByteArray(), StandardCharsets.UTF_8));
                line.reset();
                String answer = answer(request);
                if (answer != null) {
                    synchronized (out) {
                        out.write((answer + "\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                }
            }
        } catch (Exception | Error ignored) {
            // the client went away
        }
    }

    private String answer(JSONObject request) throws Exception {
        String method = request.getString("method");
        methods.add(method);
        Object id = request.get("id");
        if (!"Server.GetStatus".equals(method)) {
            return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"volume\":{\"muted\":false,\"percent\":42}}}";
        }
        statusRequests.incrementAndGet();
        switch (nextMode()) {
            case SILENT:
                return null;
            case MALFORMED:
                return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"server\":5}}";
            case UNAUTHORIZED:
                return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":401,\"message\":\"Unauthorized\"}}";
            default:
                JSONObject status = new JSONObject(Fixtures.read(statusFixture));
                status.put("id", id);
                return status.toString();
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
        dropConnections();
    }
}
