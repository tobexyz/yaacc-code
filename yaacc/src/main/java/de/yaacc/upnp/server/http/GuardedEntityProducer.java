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

import org.apache.hc.core5.http.nio.AsyncEntityProducer;
import org.apache.hc.core5.http.nio.DataStreamChannel;

import java.io.IOException;
import java.util.Set;
import java.util.function.BooleanSupplier;

import de.yaacc.share.ShareConcurrencyLimiter;

/**
 * Wraps the producer of an upstream (HTTP_LINK) response: aborts the response when the share is
 * no longer live and gives the concurrency lease back when the response ends. Everything else is
 * delegated.
 */
final class GuardedEntityProducer implements AsyncEntityProducer {

    private final AsyncEntityProducer delegate;
    private final BooleanSupplier shareLive;
    private final ShareConcurrencyLimiter.Lease lease;

    GuardedEntityProducer(AsyncEntityProducer delegate, BooleanSupplier shareLive,
                          ShareConcurrencyLimiter.Lease lease) {
        this.delegate = delegate;
        this.shareLive = shareLive;
        this.lease = lease;
    }

    @Override
    public boolean isRepeatable() {
        return false;
    }

    @Override
    public void failed(Exception cause) {
        try {
            delegate.failed(cause);
        } finally {
            lease.release();
        }
    }

    @Override
    public long getContentLength() {
        return delegate.getContentLength();
    }

    @Override
    public String getContentType() {
        return delegate.getContentType();
    }

    @Override
    public String getContentEncoding() {
        return delegate.getContentEncoding();
    }

    @Override
    public boolean isChunked() {
        return delegate.isChunked();
    }

    @Override
    public Set<String> getTrailerNames() {
        return delegate.getTrailerNames();
    }

    @Override
    public int available() {
        return delegate.available();
    }

    @Override
    public void produce(DataStreamChannel channel) throws IOException {
        if (!shareLive.getAsBoolean()) {
            throw new IOException("share ended");
        }
        delegate.produce(channel);
    }

    @Override
    public void releaseResources() {
        try {
            delegate.releaseResources();
        } finally {
            lease.release();
        }
    }
}
