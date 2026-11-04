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
package de.yaacc.yxc;

import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.impl.bootstrap.HttpServer;
import org.apache.hc.core5.http.impl.bootstrap.ServerBootstrap;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.io.CloseMode;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A real HttpCore server on the loopback address that plays the part of a device: records every request and lets the
 * test decide the answer. The canonical host name is set to localhost so that nothing depends on the machine's name.
 */
final class LoopbackYxcServer implements AutoCloseable {

    static final class Received {
        final String method;
        /** Path and query as sent. */
        final String path;
        final Map<String, String> headers;
        final String body;

        Received(String method, String path, Map<String, String> headers, String body) {
            this.method = method;
            this.path = path;
            this.headers = headers;
            this.body = body;
        }

        /** @return the value of the header (case-insensitive name), {@code null} if the request had none */
        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    interface Responder {
        void respond(Received request, ClassicHttpResponse response) throws Exception;
    }

    private final HttpServer server;
    private final List<Received> received = Collections.synchronizedList(new ArrayList<Received>());

    LoopbackYxcServer(Responder responder) throws IOException {
        server = ServerBootstrap.bootstrap()
                .setLocalAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}))
                .setListenerPort(0)
                .setCanonicalHostName("localhost")
                .register("*", (ClassicHttpRequest request, ClassicHttpResponse response, org.apache.hc.core5.http.protocol.HttpContext context) -> {
                    Map<String, String> headers = new HashMap<>();
                    for (Header header : request.getHeaders()) {
                        headers.put(header.getName().toLowerCase(Locale.ROOT), header.getValue());
                    }
                    String body = request.getEntity() == null ? null
                            : new String(request.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
                    Received call = new Received(request.getMethod(), request.getRequestUri(), headers, body);
                    received.add(call);
                    try {
                        responder.respond(call, response);
                    } catch (RuntimeException | IOException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IOException(e);
                    }
                })
                .create();
        server.start();
    }

    /** A responder that answers every request with 200 and the given JSON text. */
    static Responder json(String body) {
        return (request, response) -> respondJson(response, 200, body);
    }

    static void respondJson(ClassicHttpResponse response, int status, String body) {
        response.setCode(status);
        response.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
    }

    int port() {
        return server.getLocalPort();
    }

    List<Received> received() {
        synchronized (received) {
            return new ArrayList<>(received);
        }
    }

    @Override
    public void close() {
        server.close(CloseMode.IMMEDIATE);
    }
}
