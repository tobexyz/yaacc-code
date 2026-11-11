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

import de.yaacc.multiroom.control.SnapcastException;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Serves a {@link SimulatedSnapcastServer} as newline-delimited JSON-RPC on the loopback address (the literal
 * 127.0.0.1), so that a real {@code SnapcastRpc} can talk to it.
 */
final class LoopbackControlServer implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final SimulatedSnapcastServer simulation;
    private volatile boolean closed;

    LoopbackControlServer(SimulatedSnapcastServer simulation) throws IOException {
        this.simulation = simulation;
        serverSocket = new ServerSocket(0, 5, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
        Thread accept = new Thread(() -> {
            while (!closed) {
                try {
                    Socket socket = serverSocket.accept();
                    Thread handler = new Thread(() -> serve(socket), "loopback-control-connection");
                    handler.setDaemon(true);
                    handler.start();
                } catch (IOException e) {
                    return;
                }
            }
        }, "loopback-control-accept");
        accept.setDaemon(true);
        accept.start();
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    private void serve(Socket socket) {
        try (Socket owned = socket) {
            InputStream in = owned.getInputStream();
            OutputStream out = owned.getOutputStream();
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) >= 0) {
                if (b != '\n') {
                    line.write(b);
                    continue;
                }
                JSONObject request = new JSONObject(new String(line.toByteArray(), StandardCharsets.UTF_8));
                line.reset();
                JSONObject answer = new JSONObject().put("jsonrpc", "2.0").put("id", request.get("id"));
                try {
                    answer.put("result", simulation.call(request.getString("method"), request.optJSONObject("params")));
                } catch (SnapcastException failure) {
                    answer.put("error", new JSONObject().put("code", failure.getCode())
                            .put("message", failure.getMessage()).put("data", failure.getData()));
                }
                synchronized (out) {
                    out.write((answer + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
        } catch (Exception | Error ignored) {
            // the client went away
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
    }
}
