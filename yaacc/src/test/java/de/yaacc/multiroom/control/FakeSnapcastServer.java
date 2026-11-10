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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A control server on the loopback address: every accepted connection runs the script of the test on its own thread.
 * The address is the literal 127.0.0.1, so no name lookup is involved (the HTTP fake servers of the other tests set the
 * canonical host name to localhost for the same reason).
 */
final class FakeSnapcastServer implements AutoCloseable {

    interface Script {
        void run(Connection connection) throws Exception;
    }

    /** Answers one request; returns the lines to send back (may be empty), or null for no answer. */
    interface Responder {
        List<String> respond(JSONObject request) throws Exception;
    }

    /** One accepted connection as seen by the script. */
    static final class Connection {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        Connection(Socket socket) throws IOException {
            this.socket = socket;
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
        }

        /** The next line the client sent, or null when it closed the connection. */
        String readLine() throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            while (true) {
                int b = in.read();
                if (b < 0) {
                    return null;
                }
                if (b == '\n') {
                    return new String(line.toByteArray(), StandardCharsets.UTF_8);
                }
                line.write(b);
            }
        }

        JSONObject readRequest() throws Exception {
            String line = readLine();
            return line == null ? null : new JSONObject(line);
        }

        void send(String line) throws IOException {
            sendRaw((line + "\n").getBytes(StandardCharsets.UTF_8));
        }

        void sendRaw(byte[] bytes) throws IOException {
            out.write(bytes);
            out.flush();
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing
            }
        }
    }

    private final ServerSocket serverSocket;
    private final AtomicInteger accepted = new AtomicInteger();
    private final List<Connection> connections = Collections.synchronizedList(new ArrayList<Connection>());
    private final Thread acceptThread;
    private volatile Script script;
    private volatile boolean closed;

    FakeSnapcastServer(Script script) throws IOException {
        this.script = script;
        serverSocket = new ServerSocket(0, 50, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
        acceptThread = new Thread(() -> {
            while (!closed) {
                try {
                    Socket socket = serverSocket.accept();
                    final Connection connection = new Connection(socket);
                    connections.add(connection);
                    accepted.incrementAndGet();
                    final Script current = this.script;
                    Thread handler = new Thread(() -> {
                        try {
                            current.run(connection);
                        } catch (Exception | Error ignored) {
                            // the client went away or the test ended: nothing to report
                        } finally {
                            // leave the socket open: the test decides when the server closes it
                        }
                    }, "fake-snapcast-connection");
                    handler.setDaemon(true);
                    handler.start();
                } catch (IOException e) {
                    return;
                }
            }
        }, "fake-snapcast-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    /** A server that answers every request with the lines the responder gives. */
    static FakeSnapcastServer responding(final Responder responder) throws IOException {
        return new FakeSnapcastServer(connection -> {
            while (true) {
                JSONObject request = connection.readRequest();
                if (request == null) {
                    return;
                }
                List<String> lines = responder.respond(request);
                if (lines != null) {
                    for (String line : lines) {
                        connection.send(line);
                    }
                }
            }
        });
    }

    /** {"jsonrpc":"2.0","id":..,"result":..} */
    static String result(Object id, String resultJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + resultJson + "}";
    }

    /** A fixture response with the id of the request it answers. */
    static String fixtureWithId(String fixture, Object id) throws Exception {
        JSONObject json = new JSONObject(SnapcastFixtures.read(fixture));
        json.put("id", id);
        return json.toString();
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    int acceptedCount() {
        return accepted.get();
    }

    /** Closes the n-th accepted connection from the server side. */
    void closeConnection(int index) throws InterruptedException {
        // the client may report "connected" before the accept thread has listed the connection
        long end = System.nanoTime() + 2_000_000_000L;
        while (connections.size() <= index && System.nanoTime() < end) {
            Thread.sleep(5);
        }
        connections.get(index).close();
    }

    void setScript(Script next) {
        this.script = next;
    }

    @Override
    public void close() {
        closed = true;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // closing
        }
        synchronized (connections) {
            for (Connection connection : connections) {
                connection.close();
            }
        }
    }
}
