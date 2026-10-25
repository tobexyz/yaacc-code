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
import java.util.function.BooleanSupplier;

import de.yaacc.share.RangeStreamer;
import de.yaacc.share.ShareConcurrencyLimiter;
import de.yaacc.share.ShareLimits;

/**
 * Streams the body of a LOCAL_FILE share, one chunk per call, from a shared positional source.
 * It only reads; it never closes the source (the registry does). The response is aborted (an
 * {@link IOException} from {@link #produceData}) as soon as the share is no longer live, the
 * source is closing or it ends early, so an expired or removed share stops its running
 * downloads. The concurrency lease is given back when the response ends, whichever way, and the
 * bytes the client accepts are reported to it: a response that stalls or trickles is cut off by
 * the lease watchdog (see {@link ShareConcurrencyLimiter#expireIdle}) and aborts here at its next
 * callback.
 */
final class ShareEntityProducer extends AbstractBinAsyncEntityProducer {

    private final RangeStreamer streamer;
    private final long contentLength;
    private final BooleanSupplier shareLive;
    private final ShareConcurrencyLimiter.Lease lease;
    private final byte[] buffer = new byte[ShareLimits.CHUNK_BYTES];
    /** What the channel did not take yet; the next chunk is read only after it is written. */
    private ByteBuffer pending;
    private boolean ended;

    ShareEntityProducer(RangeStreamer streamer, long contentLength, ContentType contentType,
                        BooleanSupplier shareLive, ShareConcurrencyLimiter.Lease lease) {
        super(ShareLimits.CHUNK_BYTES, contentType);
        this.streamer = streamer;
        this.contentLength = contentLength;
        this.shareLive = shareLive;
        this.lease = lease;
    }

    @Override
    public long getContentLength() {
        return contentLength;
    }

    @Override
    protected int availableData() {
        if (ended) {
            return 0;
        }
        // always positive until the end was signalled, so that produceData runs once more to end the stream
        return pending != null && pending.hasRemaining() ? pending.remaining() : buffer.length;
    }

    @Override
    protected void produceData(StreamChannel<ByteBuffer> channel) throws IOException {
        if (!shareLive.getAsBoolean()) {
            throw new IOException("share ended");
        }
        if (!lease.isLive()) {
            // the watchdog took the slot away: this reader made no progress for the idle time
            throw new IOException("write idle");
        }
        if (pending != null && pending.hasRemaining()) {
            lease.noteProgress(channel.write(pending));
            if (pending.hasRemaining()) {
                return;
            }
        }
        int read = streamer.read(buffer, 0, buffer.length);
        if (read == -1) {
            ended = true;
            channel.endStream();
            return;
        }
        pending = ByteBuffer.wrap(buffer, 0, read);
        lease.noteProgress(channel.write(pending));
    }

    @Override
    public boolean isRepeatable() {
        return false;
    }

    @Override
    public void failed(Exception cause) {
        lease.release();
    }

    @Override
    public void releaseResources() {
        try {
            super.releaseResources();
        } finally {
            lease.release();
        }
    }
}
