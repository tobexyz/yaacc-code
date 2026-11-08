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

import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.nio.StreamChannel;
import org.apache.hc.core5.http.nio.entity.AbstractBinAsyncEntityProducer;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Entity for a response to {@code HEAD}: it only carries the content length and type for the
 * headers and produces no data (HttpCore ends the stream after the headers of a HEAD response),
 * so nothing is opened or read.
 */
final class LengthOnlyEntityProducer extends AbstractBinAsyncEntityProducer {

    private final long contentLength;

    LengthOnlyEntityProducer(long contentLength, ContentType contentType) {
        super(0, contentType);
        this.contentLength = contentLength;
    }

    @Override
    public long getContentLength() {
        return contentLength;
    }

    @Override
    protected int availableData() {
        return 0;
    }

    @Override
    protected void produceData(StreamChannel<ByteBuffer> channel) throws IOException {
        channel.endStream();
    }

    @Override
    public boolean isRepeatable() {
        return false;
    }

    @Override
    public void failed(Exception cause) {
    }
}
