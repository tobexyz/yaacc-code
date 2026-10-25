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

import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.nio.AsyncEntityProducer;
import org.apache.hc.core5.http.nio.DataStreamChannel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import de.yaacc.share.ShareConcurrencyLimiter;

/**
 * Wraps the producer of an upstream (HTTP_LINK) response: aborts the response when the share is
 * no longer live or its idle-watchdog lease cannot be re-acquired (the share is full), reports the bytes the client
 * accepts to the lease and gives the lease back when the response ends. Everything else is
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
        if (!lease.tryReacquire()) {
            // the watchdog took the slot away while this reader was idle and the share is full now
            throw new IOException("write idle");
        }
        delegate.produce(new ProgressChannel(channel, lease));
    }

    /** Reports the bytes the client accepted to the lease; everything else is delegated. */
    private static final class ProgressChannel implements DataStreamChannel {
        private final DataStreamChannel channel;
        private final ShareConcurrencyLimiter.Lease lease;

        ProgressChannel(DataStreamChannel channel, ShareConcurrencyLimiter.Lease lease) {
            this.channel = channel;
            this.lease = lease;
        }

        @Override
        public void requestOutput() {
            channel.requestOutput();
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            int written = channel.write(src);
            lease.noteProgress(written);
            return written;
        }

        @Override
        public void endStream() throws IOException {
            channel.endStream();
        }

        @Override
        public void endStream(List<? extends Header> trailers) throws IOException {
            channel.endStream(trailers);
        }
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
