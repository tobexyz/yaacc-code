/*
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
package de.yaacc.upnp.server.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.fourthline.cling.model.message.StreamRequestMessage;
import org.fourthline.cling.model.message.StreamResponseMessage;
import org.fourthline.cling.model.message.UpnpRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;

/**
 * The sender against a real HTTP server: a body that never ends, or drips in, ends the call at the limit or the deadline. The
 * connection is dropped, not read to the end (closing a stream of the client reads the rest of the body: that would take as
 * long as the server sends).
 */
public class HttpRequestSenderRealServerTest {
    private ServerSocket server;
    private int port;

    @Before
    public void startServer() throws IOException {
        server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        port = server.getLocalPort();
        Thread acceptor = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    Thread serving = new Thread(() -> serve(socket));
                    serving.setDaemon(true);
                    serving.start();
                } catch (IOException e) {
                    return;
                }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /** /slow: a chunked body of 1 KiB every 50 ms for 8 seconds; /ok: a short body. */
    private static void serve(Socket socket) {
        try (Socket s = socket) {
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
            String requestLine = in.readLine();
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                // headers
            }
            OutputStream out = s.getOutputStream();
            if (requestLine != null && requestLine.contains("/slow")) {
                out.write("HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nTransfer-Encoding: chunked\r\n\r\n".getBytes());
                byte[] chunk = new byte[1024];
                for (int i = 0; i < 160; i++) {
                    out.write("400\r\n".getBytes());
                    out.write(chunk);
                    out.write("\r\n".getBytes());
                    out.flush();
                    Thread.sleep(50);
                }
                out.write("0\r\n\r\n".getBytes());
            } else {
                byte[] body = "<ok/>".getBytes();
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: " + body.length
                        + "\r\nConnection: close\r\n\r\n").getBytes());
                out.write(body);
            }
            out.flush();
        } catch (IOException | InterruptedException e) {
            // the client went away
        }
    }

    @After
    public void stopServer() throws IOException {
        server.close();
    }

    private StreamRequestMessage get(String path) {
        return new StreamRequestMessage(UpnpRequest.Method.GET, URI.create("http://127.0.0.1:" + port + path));
    }

    @Test
    public void aBodyThatKeepsComingIsCutAtTheDeadlineWithoutWaitingForItsEnd() throws Exception {
        HttpRequestSender sender = new HttpRequestSender(10 * 1024 * 1024, 500);
        long start = System.currentTimeMillis();

        try {
            sender.send(get("/slow"));
            fail();
        } catch (IOException expected) {
            // too slow
        }

        long took = System.currentTimeMillis() - start;
        assertTrue("took " + took + " ms, the server sends for 8000 ms", took < 4000);
    }

    @Test
    public void aBodyOverTheLimitIsCutAtTheLimitWithoutWaitingForItsEnd() throws Exception {
        HttpRequestSender sender = new HttpRequestSender(4 * 1024, 60_000);
        long start = System.currentTimeMillis();

        try {
            sender.send(get("/slow"));
            fail();
        } catch (IOException expected) {
            // too long
        }

        long took = System.currentTimeMillis() - start;
        assertTrue("took " + took + " ms, the server sends for 8000 ms", took < 4000);
    }

    @Test
    public void anAnswerWithinTheLimitsArrivesAndTheSenderStaysUsableAfterACutOffAnswer() throws Exception {
        HttpRequestSender sender = new HttpRequestSender(4 * 1024, 60_000);
        try {
            sender.send(get("/slow"));
        } catch (IOException expected) {
            // cut off
        }

        StreamResponseMessage response = sender.send(get("/ok"));

        assertEquals("<ok/>", response.getBodyString());
    }
}
