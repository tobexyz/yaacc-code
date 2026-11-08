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

import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.HttpResponseInterceptor;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.HttpVersion;
import org.apache.hc.core5.http.ProtocolVersion;
import org.apache.hc.core5.http.impl.HttpProcessors;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http.protocol.HttpCoreContext;
import org.apache.hc.core5.http.protocol.HttpProcessor;
import org.apache.hc.core5.http2.impl.H2Processors;

import java.io.IOException;

/**
 * The processor of the HTTP server of the app: the default chains of httpcore5 with a {@code Date} header that is added before
 * the library's own interceptor would add one (see {@link HttpDates}).
 * <p>
 * {@code H2ServerBootstrap.setHttpProcessor} gives ONE processor to both protocols, while the default is one chain for
 * HTTP/1.1 ({@code HttpProcessors.server()}) and another for HTTP/2 ({@code H2Processors.server()}); the HTTP/2 chain on an HTTP/1.1
 * connection ends in an empty answer. So this processor picks the chain by the protocol version that the stream handler has put
 * into the context of the exchange (HTTP/2 only if it says so).
 */
public final class ServerHttpProcessor implements HttpProcessor {

    private final HttpProcessor http1 = HttpProcessors.customServer(null).addFirst(new DateInterceptor()).build();
    private final HttpProcessor http2 = H2Processors.customServer(null).addFirst(new DateInterceptor()).build();

    public static HttpProcessor create() {
        return new ServerHttpProcessor();
    }

    private ServerHttpProcessor() {
    }

    private HttpProcessor chainFor(HttpContext context) {
        ProtocolVersion version = context == null ? null : HttpCoreContext.cast(context).getProtocolVersion();
        return version != null && version.greaterEquals(HttpVersion.HTTP_2) ? http2 : http1;
    }

    @Override
    public void process(HttpRequest request, EntityDetails entity, HttpContext context) throws HttpException, IOException {
        chainFor(context).process(request, entity, context);
    }

    @Override
    public void process(HttpResponse response, EntityDetails entity, HttpContext context) throws HttpException, IOException {
        chainFor(context).process(response, entity, context);
    }

    /** Adds the {@code Date} header (RFC 9110 format) to final responses that have none: the same rule as the library's interceptor. */
    static final class DateInterceptor implements HttpResponseInterceptor {
        @Override
        public void process(HttpResponse response, EntityDetails entity, HttpContext context) {
            if (response.getCode() >= HttpStatus.SC_OK && !response.containsHeader(HttpHeaders.DATE)) {
                response.setHeader(HttpHeaders.DATE, HttpDates.now());
            }
        }
    }
}
