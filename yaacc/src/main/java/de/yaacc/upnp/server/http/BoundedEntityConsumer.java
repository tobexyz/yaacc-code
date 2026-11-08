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
package de.yaacc.upnp.server.http;

import org.apache.hc.core5.http.nio.entity.BasicAsyncEntityConsumer;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Collects the body of a request like {@link BasicAsyncEntityConsumer}, but only up to a limit: the server is reachable by every
 * host of the network, and a body that never ends must not fill the memory of the phone. A longer body ends the request with an
 * exception.
 */
final class BoundedEntityConsumer extends BasicAsyncEntityConsumer {
    /** What a control request (SOAP, subscription) may carry: far more than any real one. */
    static final int MAX_REQUEST_BYTES = 2 * 1024 * 1024;

    private final int limit;
    private long received;

    BoundedEntityConsumer() {
        this(MAX_REQUEST_BYTES);
    }

    BoundedEntityConsumer(int limit) {
        this.limit = limit;
    }

    @Override
    protected void data(ByteBuffer src, boolean endOfStream) throws IOException {
        received += src.remaining();
        if (received > limit) {
            throw new IOException("the request body is larger than " + limit + " bytes");
        }
        super.data(src, endOfStream);
    }
}
