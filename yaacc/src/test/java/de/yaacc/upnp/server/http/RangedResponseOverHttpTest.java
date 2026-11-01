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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;

import android.content.Context;

import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.Message;
import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.http.impl.bootstrap.HttpAsyncServer;
import org.apache.hc.core5.http.nio.AsyncEntityConsumer;
import org.apache.hc.core5.http.nio.AsyncRequestConsumer;
import org.apache.hc.core5.http.nio.AsyncServerRequestHandler;
import org.apache.hc.core5.http.nio.support.AsyncResponseBuilder;
import org.apache.hc.core5.http.nio.support.BasicRequestConsumer;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http2.impl.nio.bootstrap.H2ServerBootstrap;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.reactor.ListenerEndpoint;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.seamless.util.MimeType;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import de.yaacc.upnp.server.http.YaaccUpnpServerContentHttpHandler.ContentHolder;
import de.yaacc.util.HttpRange;

/**
 * The status line, headers and body of ranged requests of a local file, over a real HttpCore 5.3.6
 * server on the loopback interface (built like the one of {@code YaaccUpnpServerService}) and a raw
 * socket client (issue #294). The handler of the app needs Android classes that a JVM test does not
 * have (path parsing, MediaStore), so the test handler here repeats only its tail: the Range header
 * through {@link RangeResponsePlan}, the headers, the entity of the real {@link ContentHolder}.
 * Every request is answered with {@code Connection: close}, like the app does, so a body that does
 * not end shows up as a read timeout. A file below and a file above the old 50 MB threshold must
 * answer alike.
 */
public class RangedResponseOverHttpTest {

    private static final long SMALL = 300_000;
    private static final long BIG = 120L * 1024 * 1024;
    private static final int PATTERN_LENGTH = 70_000;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private HttpAsyncServer server;
    private int port;
    private volatile File served;

    @Before
    public void startServer() throws Exception {
        server = H2ServerBootstrap.bootstrap()
                .setIOReactorConfig(IOReactorConfig.custom().setSoTimeout(Timeout.ofSeconds(20)).build())
                .register("*", new TestHandler())
                .create();
        server.start();
        ListenerEndpoint endpoint = server.listen(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                URIScheme.HTTP).get();
        port = ((InetSocketAddress) endpoint.getAddress()).getPort();
    }

    @After
    public void stopServer() {
        server.close(CloseMode.IMMEDIATE);
    }

    /** What the handler of the app does after the lookup of the resource. */
    private final class TestHandler implements AsyncServerRequestHandler<Message<HttpRequest, byte[]>> {
        @Override
        public AsyncRequestConsumer<Message<HttpRequest, byte[]>> prepare(HttpRequest request,
                                                                          EntityDetails entityDetails,
                                                                          HttpContext context) {
            return new BasicRequestConsumer<>((AsyncEntityConsumer<byte[]>) null);
        }

        @Override
        public void handle(Message<HttpRequest, byte[]> request, ResponseTrigger responseTrigger,
                           HttpContext context) throws HttpException, IOException {
            Header rangeHeader = request.getHead().getHeader(HttpHeaders.RANGE);
            List<HttpRange> ranges = RangeResponsePlan.parseHeader(rangeHeader == null ? null : rangeHeader.getValue());
            ContentHolder holder = new ContentHolder(MimeType.valueOf("video/mp4"), served.getAbsolutePath(), ranges,
                    mock(Context.class));
            AsyncResponseBuilder builder = AsyncResponseBuilder.create(200);
            RangeResponsePlan plan = holder.getRangePlan();
            plan.applyTo(builder);
            builder.setHeader(HttpHeaders.CONNECTION, "close");
            builder.setHeader("transferMode.dlna.org", "Streaming");
            if (plan.hasBody()) {
                builder.setEntity(holder.getEntityProducer());
            }
            builder.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
            responseTrigger.submitResponse(builder.build(), context);
        }
    }

    // ---- files and the client

    /** The byte at {@code position} of the test files: a pattern in the first 70000 and the last 70000 bytes, else 0. */
    private static byte at(long position, long size) {
        boolean patterned = position < PATTERN_LENGTH || position >= size - PATTERN_LENGTH;
        return patterned ? (byte) ((position * 31 + (position >> 8) * 7 + 1) & 0xFF) : 0;
    }

    private static byte[] expected(long from, long length, long size) {
        byte[] data = new byte[(int) length];
        for (int i = 0; i < length; i++) {
            data[i] = at(from + i, size);
        }
        return data;
    }

    private File fileOfSize(long size) throws IOException {
        File file = tmp.newFile();
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.setLength(size); // sparse for the big one
            int head = (int) Math.min(PATTERN_LENGTH, size);
            raf.seek(0);
            raf.write(expected(0, head, size));
            long tailFrom = Math.max(head, size - PATTERN_LENGTH);
            raf.seek(tailFrom);
            raf.write(expected(tailFrom, size - tailFrom, size));
        }
        served = file;
        return file;
    }

    private static final class Response {
        String statusLine;
        final Map<String, String> headers = new LinkedHashMap<>();
        byte[] body; // null if the body was only counted
        long bodyLength;

        int status() {
            return Integer.parseInt(statusLine.split(" ")[1]);
        }

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    /** One request on its own connection; reads to the end of the connection (the server closes it). */
    private Response request(String method, String rangeHeader) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(15_000);
            String head = method + " /res/1 HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
                    + (rangeHeader == null ? "" : "Range: " + rangeHeader + "\r\n") + "\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = socket.getInputStream();

            StringBuilder headText = new StringBuilder();
            while (!headText.toString().endsWith("\r\n\r\n")) {
                int b = in.read();
                if (b < 0) {
                    throw new IOException("connection ended inside the response head: " + headText);
                }
                headText.append((char) b);
            }
            Response response = new Response();
            String[] lines = headText.toString().split("\r\n");
            response.statusLine = lines[0];
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) {
                    response.headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                            lines[i].substring(colon + 1).trim());
                }
            }
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            boolean keep = true;
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                response.bodyLength += n;
                if (keep && response.bodyLength > 8L * 1024 * 1024) {
                    keep = false;
                    body = null;
                }
                if (keep) {
                    body.write(buffer, 0, n);
                }
            }
            response.body = body == null ? null : body.toByteArray();
            return response;
        }
    }

    private void assertPartial(File file, String rangeHeader, long from, long length) throws IOException {
        long size = file.length();
        Response response = request("GET", rangeHeader);

        assertEquals(rangeHeader, "206", String.valueOf(response.status()));
        assertEquals(rangeHeader, "bytes " + from + "-" + (from + length - 1) + "/" + size, response.header("Content-Range"));
        assertEquals(rangeHeader, String.valueOf(length), response.header("Content-Length"));
        assertEquals(rangeHeader, "bytes", response.header("Accept-Ranges"));
        assertEquals(rangeHeader, length, response.bodyLength);
        assertArrayEquals("body of " + rangeHeader, expected(from, length, size), response.body);
    }

    private void assertNotSatisfiable(File file, String rangeHeader) throws IOException {
        long size = file.length();
        for (String method : new String[]{"GET", "HEAD"}) {
            Response response = request(method, rangeHeader);

            assertEquals(method + " " + rangeHeader, 416, response.status());
            assertEquals(method + " " + rangeHeader, "bytes */" + size, response.header("Content-Range"));
            assertEquals(method + " " + rangeHeader, "bytes", response.header("Accept-Ranges"));
            assertEquals(method + " " + rangeHeader, 0, response.bodyLength);
            assertEquals(method + " " + rangeHeader, "0", response.header("Content-Length"));
        }
    }

    private void assertIgnored(File file, String rangeHeader) throws IOException {
        Response response = request("GET", rangeHeader);

        assertEquals(rangeHeader, 200, response.status());
        assertNull(rangeHeader, response.header("Content-Range"));
        assertEquals(rangeHeader, String.valueOf(file.length()), response.header("Content-Length"));
        assertEquals(rangeHeader, file.length(), response.bodyLength);
    }

    /** Requests that every file has to answer alike, whatever its size. */
    private void assertRangeBehavior(File file) throws IOException {
        long size = file.length();
        assertPartial(file, "bytes=0-0", 0, 1);
        assertPartial(file, "bytes=0-99", 0, 100);
        assertPartial(file, "bytes=100-199", 100, 100);
        assertPartial(file, "bytes=" + (size - 1) + "-" + (size - 1), size - 1, 1);
        assertPartial(file, "bytes=" + (size - 5000) + "-", size - 5000, 5000);
        assertPartial(file, "bytes=-777", size - 777, 777);
        assertPartial(file, "bytes=" + (size - 100) + "-" + (size + 999_999), size - 100, 100);
        assertPartial(file, "bytes=0-9,20-29", 0, 10);
        assertPartial(file, "bytes=8192-24575", 8192, 16384);

        assertNotSatisfiable(file, "bytes=" + size + "-");
        assertNotSatisfiable(file, "bytes=" + (size + 1000) + "-" + (size + 2000));
        assertNotSatisfiable(file, "bytes=-0");
        assertNotSatisfiable(file, "bytes=10-5");

        assertIgnored(file, "bytes=abc");
        assertIgnored(file, "items=0-5");
        assertIgnored(file, "bytes=0-5,x");
        assertIgnored(file, "bytes=99999999999999999999-");
        assertIgnored(file, "garbage");

        Response head = request("HEAD", "bytes=100-199");
        assertEquals(206, head.status());
        assertEquals("bytes 100-199/" + size, head.header("Content-Range"));
        assertEquals("100", head.header("Content-Length"));
        assertEquals("a HEAD answer has no body", 0, head.bodyLength);
        Response headWhole = request("HEAD", null);
        assertEquals(200, headWhole.status());
        assertEquals(String.valueOf(size), headWhole.header("Content-Length"));
        assertEquals(0, headWhole.bodyLength);
        assertNull(headWhole.header("Content-Range"));
    }

    @Test
    public void aFileBelowFiftyMegabytesAnswersRanges() throws IOException {
        assertRangeBehavior(fileOfSize(SMALL));
    }

    @Test
    public void aFileAboveFiftyMegabytesAnswersTheSameRanges() throws IOException {
        assertRangeBehavior(fileOfSize(BIG));
    }

    @Test
    public void theWholeSmallFileWithoutRangeIs200() throws IOException {
        File file = fileOfSize(SMALL);

        Response response = request("GET", null);

        assertEquals(200, response.status());
        assertNull(response.header("Content-Range"));
        assertEquals("bytes", response.header("Accept-Ranges"));
        assertArrayEquals(expected(0, SMALL, SMALL), response.body);
    }

    @Test
    public void aSuffixLongerThanTheFileIsTheWholeFileAs206() throws IOException {
        File file = fileOfSize(SMALL);

        assertPartial(file, "bytes=-9999999", 0, SMALL);
        assertPartial(file, "bytes=0-", 0, SMALL);
    }

    @Test
    public void reportersCaseInABigFileIsCompleteAndEnded() throws IOException {
        File file = fileOfSize(BIG);
        long from = BIG - PATTERN_LENGTH;

        // the reporter's case: a range inside the last 70000 bytes of a big file, not a multiple of 8192
        assertPartial(file, "bytes=" + from + "-" + (from + 49_999), from, 50_000);
    }

    @Test
    public void anEmptyFileAnswers416ToEveryRangeAnd200ToNone() throws IOException {
        File file = fileOfSize(0);

        assertNotSatisfiable(file, "bytes=0-0");
        assertNotSatisfiable(file, "bytes=0-");
        assertNotSatisfiable(file, "bytes=-5");
        Response whole = request("GET", null);
        assertEquals(200, whole.status());
        assertEquals("0", whole.header("Content-Length"));
        assertFalse(whole.headers.containsKey("content-range"));
        assertEquals(0, whole.bodyLength);
    }
}
