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
package de.yaacc.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/** {@link LinkContentType}: the HEAD that replaces "a failed read is an image". Plain JVM, local sockets. */
public class LinkContentTypeTest {

    private ServerSocket server;
    private Thread acceptor;
    private final List<String> requestLines = Collections.synchronizedList(new ArrayList<>());
    private volatile Function<String, String> responder;
    private int port;

    @Before
    public void setUp() throws IOException {
        server = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"));
        port = server.getLocalPort();
        acceptor = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(2_000);
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
                    String first = reader.readLine();
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        // skip the headers
                    }
                    requestLines.add(first);
                    String path = first == null ? "" : first.split(" ")[1];
                    String response = responder.apply(path);
                    OutputStream out = socket.getOutputStream();
                    out.write(response.getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                } catch (IOException | RuntimeException e) {
                    // closed by the test
                }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @After
    public void tearDown() throws IOException {
        server.close();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private static String ok(String contentType) {
        return "HTTP/1.1 200 OK\r\nContent-Type: " + contentType + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
    }

    private static String redirect(String location) {
        return "HTTP/1.1 302 Found\r\nLocation: " + location + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
    }

    @Test
    public void usesAHeadRequestAndReturnsAConcreteMediaType() {
        responder = path -> ok("audio/mpeg");

        assertEquals("audio/mpeg", LinkContentType.find(url("/a.mp3"), u -> true, 2_000));
        assertTrue(requestLines.get(0).startsWith("HEAD /a.mp3 "));
    }

    @Test
    public void parametersAreDroppedAndTheTypeIsLowerCase() {
        responder = path -> ok("Video/MP4; codecs=avc1");

        assertEquals("video/mp4", LinkContentType.find(url("/v"), u -> true, 2_000));
    }

    @Test
    public void aTypeThatIsNotMediaIsNull() {
        responder = path -> ok("text/html");

        assertNull(LinkContentType.find(url("/page"), u -> true, 2_000));
    }

    @Test
    public void aWildcardOrUnknownTypeIsNull() {
        responder = path -> ok("application/octet-stream");
        assertNull(LinkContentType.find(url("/x"), u -> true, 2_000));
        responder = path -> ok("image/*");
        assertNull(LinkContentType.find(url("/y"), u -> true, 2_000));
    }

    @Test
    public void anErrorStatusIsNull() {
        responder = path -> "HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";

        assertNull(LinkContentType.find(url("/x"), u -> true, 2_000));
    }

    @Test
    public void aRedirectIsFollowedByHand() {
        responder = path -> path.equals("/start") ? redirect("/final.png") : ok("image/png");

        assertEquals("image/png", LinkContentType.find(url("/start"), u -> true, 2_000));
        assertEquals(2, requestLines.size());
    }

    @Test
    public void everyHopMustBeAllowed() {
        responder = path -> path.equals("/start") ? redirect("/blocked") : ok("image/png");

        assertNull(LinkContentType.find(url("/start"), u -> !u.endsWith("/blocked"), 2_000));
        assertEquals("the blocked hop is not requested", 1, requestLines.size());
    }

    @Test
    public void aRedirectToAnotherSchemeIsNotFollowed() {
        responder = path -> redirect("file:///etc/passwd");
        assertNull(LinkContentType.find(url("/start"), u -> true, 2_000));
        responder = path -> redirect("ftp://example.org/a.mp3");
        assertNull(LinkContentType.find(url("/start"), u -> true, 2_000));
        assertEquals(2, requestLines.size());
    }

    @Test
    public void aRedirectLoopEndsAfterAFewHops() {
        responder = path -> redirect("/again");

        assertNull(LinkContentType.find(url("/start"), u -> true, 2_000));
        assertEquals(LinkContentType.MAX_REDIRECTS + 1, requestLines.size());
    }

    @Test
    public void aRedirectWithoutLocationIsNull() {
        responder = path -> "HTTP/1.1 302 Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";

        assertNull(LinkContentType.find(url("/start"), u -> true, 2_000));
    }

    @Test
    public void onlyHttpAndHttpsAreRequested() {
        assertNull(LinkContentType.find("file:///etc/passwd", u -> true, 2_000));
        assertNull(LinkContentType.find("content://media/x", u -> true, 2_000));
        assertNull(LinkContentType.find("ftp://example.org/a", u -> true, 2_000));
        assertNull(LinkContentType.find(null, u -> true, 2_000));
        assertEquals(0, requestLines.size());
    }

    @Test
    public void aServerThatNeverAnswersTimesOut() throws Exception {
        responder = path -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return ok("audio/mpeg");
        };
        long begin = System.nanoTime();

        assertNull(LinkContentType.find(url("/slow"), u -> true, 300));

        assertTrue((System.nanoTime() - begin) / 1_000_000L < 2_500L);
    }

    @Test
    public void aClosedPortIsNull() throws IOException {
        server.close();

        assertNull(LinkContentType.find(url("/x"), u -> true, 500));
    }

    @Test
    public void concreteMediaTypeTable() {
        assertEquals("audio/mpeg", LinkContentType.concreteMediaType(" audio/mpeg "));
        assertEquals("image/jpeg", LinkContentType.concreteMediaType("IMAGE/JPEG;q=1"));
        assertNull(LinkContentType.concreteMediaType(null));
        assertNull(LinkContentType.concreteMediaType(""));
        assertNull(LinkContentType.concreteMediaType("audio/"));
        assertNull(LinkContentType.concreteMediaType("text/plain"));
        assertNull(LinkContentType.concreteMediaType("audio/x y"));
    }
}
