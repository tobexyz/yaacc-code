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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.Message;
import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.http.impl.bootstrap.HttpAsyncServer;
import org.apache.hc.core5.http.nio.AsyncEntityConsumer;
import org.apache.hc.core5.http.nio.AsyncRequestConsumer;
import org.apache.hc.core5.http.nio.AsyncServerRequestHandler;
import org.apache.hc.core5.http.nio.entity.AsyncEntityProducers;
import org.apache.hc.core5.http.nio.support.AsyncResponseBuilder;
import org.apache.hc.core5.http.nio.support.BasicRequestConsumer;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http2.impl.nio.bootstrap.H2ServerBootstrap;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.reactor.ListenerEndpoint;
import org.apache.hc.core5.util.Timeout;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * A real httpcore5 server with the processor of the app: the Date header of a response is an English HTTP date also on a device
 * with another language (the bug: the library formatted it with the default locale), and a Date that the handler set is kept.
 */
public class ServerDateHeaderTest {

    private static final String HTTP_DATE = "[A-Z][a-z]{2}, \\d{2} [A-Z][a-z]{2} \\d{4} \\d{2}:\\d{2}:\\d{2} GMT";

    private HttpAsyncServer server;
    private int port;
    private Locale locale;

    @Before
    public void startServer() throws Exception {
        locale = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
        server = H2ServerBootstrap.bootstrap()
                // the test sends "Host: localhost": the server answers 421 to any other host than its canonical name, which is the
                // host name of the machine (the CI runner is not called localhost)
                .setCanonicalHostName("localhost")
                .setIOReactorConfig(IOReactorConfig.custom().setSoTimeout(Timeout.ofSeconds(20)).build())
                .setHttpProcessor(ServerHttpProcessor.create())
                .register("/plain", new Handler(null))
                .register("/own", new Handler("Thu, 01 Jan 1970 00:00:00 GMT"))
                .create();
        server.start();
        ListenerEndpoint endpoint = server.listen(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                URIScheme.HTTP).get();
        port = ((InetSocketAddress) endpoint.getAddress()).getPort();
    }

    @After
    public void stopServer() {
        server.close(CloseMode.IMMEDIATE);
        Locale.setDefault(locale);
    }

    private static final class Handler implements AsyncServerRequestHandler<Message<HttpRequest, byte[]>> {
        private final String ownDate;

        Handler(String ownDate) {
            this.ownDate = ownDate;
        }

        @Override
        public AsyncRequestConsumer<Message<HttpRequest, byte[]>> prepare(HttpRequest request, EntityDetails entityDetails,
                                                                          HttpContext context) {
            return new BasicRequestConsumer<>((AsyncEntityConsumer<byte[]>) null);
        }

        @Override
        public void handle(Message<HttpRequest, byte[]> request, ResponseTrigger responseTrigger, HttpContext context)
                throws HttpException, IOException {
            AsyncResponseBuilder builder = AsyncResponseBuilder.create(200);
            if (ownDate != null) {
                builder.setHeader("Date", ownDate);
            }
            builder.setEntity(AsyncEntityProducers.create("ok"));
            responseTrigger.submitResponse(builder.build(), context);
        }
    }

    private String dateHeaderOf(String path) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(15_000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = socket.getInputStream();
            StringBuilder text = new StringBuilder();
            int b;
            while ((b = in.read()) >= 0) {
                text.append((char) b);
            }
            for (String line : text.toString().split("\r\n")) {
                if (line.toLowerCase(Locale.ROOT).startsWith("date:")) {
                    return line.substring(5).trim();
                }
            }
            return null;
        }
    }

    @Test
    public void theDateOfAResponseIsAnEnglishHttpDateOnAGermanDevice() throws Exception {
        String date = dateHeaderOf("/plain");
        assertNotNull("a Date header", date);
        assertTrue(date, date.matches(HTTP_DATE));
    }

    @Test
    public void aDateTheHandlerSetIsKept() throws Exception {
        assertEquals("Thu, 01 Jan 1970 00:00:00 GMT", dateHeaderOf("/own"));
    }

    @Test
    public void anHttp2ExchangeGetsTheEnglishDateToo() throws Exception {
        org.apache.hc.core5.http.protocol.HttpProcessor processor = ServerHttpProcessor.create();
        org.apache.hc.core5.http.protocol.HttpCoreContext context = org.apache.hc.core5.http.protocol.HttpCoreContext.create();
        context.setProtocolVersion(org.apache.hc.core5.http.HttpVersion.HTTP_2);
        org.apache.hc.core5.http.message.BasicHttpResponse response = new org.apache.hc.core5.http.message.BasicHttpResponse(200);

        processor.process(response, null, context);

        assertTrue(response.getFirstHeader("Date").getValue(), response.getFirstHeader("Date").getValue().matches(HTTP_DATE));
    }

    @Test
    public void aContextWithoutAVersionUsesTheHttp1Chain() throws Exception {
        org.apache.hc.core5.http.protocol.HttpProcessor processor = ServerHttpProcessor.create();
        org.apache.hc.core5.http.message.BasicHttpResponse response = new org.apache.hc.core5.http.message.BasicHttpResponse(200);

        processor.process(response, null, org.apache.hc.core5.http.protocol.HttpCoreContext.create());

        assertTrue(response.getFirstHeader("Date").getValue().matches(HTTP_DATE));
    }

    @Test
    public void anInformationalResponseGetsNoDate() throws Exception {
        org.apache.hc.core5.http.message.BasicHttpResponse response = new org.apache.hc.core5.http.message.BasicHttpResponse(100);

        ServerHttpProcessor.create().process(response, null, org.apache.hc.core5.http.protocol.HttpCoreContext.create());

        assertEquals(null, response.getFirstHeader("Date"));
    }
}
