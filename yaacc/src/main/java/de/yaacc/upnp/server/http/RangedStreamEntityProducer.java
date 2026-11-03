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
import java.io.InputStream;
import java.nio.ByteBuffer;

import de.yaacc.util.YaaccLogger;

/**
 * The body of a ranged answer read from a stream in 8 KB steps, so a large resource is never held
 * in memory (issue #294): {@code length} bytes starting at byte {@code start} of the stream the
 * {@link StreamOpener} opens.
 * <p>
 * How HttpCore 5.3.6 drives an {@link AbstractBinAsyncEntityProducer}: {@code produce()} calls
 * {@code produceData()} once per output event, and the event is only kept while
 * {@code available()} is above 0. So the stream has to be ended in the {@code produceData()} call
 * that writes the last byte: once {@code availableData()} is 0 nobody calls again, the tail
 * (a partial last chunk waits in the 8 KB buffer until the stream is ended) never goes out and the
 * client waits for the end of the body. Also {@code write()} takes a chunk whole or not at all (0
 * when the buffer cannot make room for it), so a chunk that was not taken is kept and offered again
 * instead of being dropped. {@code availableData()} does not ask the stream
 * ({@link InputStream#available()} is 0 at the end of a file and for slow providers): the producer
 * knows how many bytes it still owes.
 * <p>
 * The stream is opened by the first {@code produceData()} and closed at the end of the body, when
 * a read or a write fails, and by {@link #failed(Exception)} and {@link #releaseResources()}. A
 * stream that ends before {@code length} bytes fail the response instead of ending it quietly
 * with less than the announced Content-Length.
 */
class RangedStreamEntityProducer extends AbstractBinAsyncEntityProducer {

    /** Opens the stream the body is read from. */
    interface StreamOpener {
        InputStream open() throws IOException;
    }

    private static final int CHUNK_SIZE = 8192;

    private final StreamOpener opener;
    private final long start;
    private final long length;
    private long bytesRead = 0;
    private InputStream input;
    /** A chunk the channel did not take yet. */
    private ByteBuffer pending;
    private boolean ended = false;

    RangedStreamEntityProducer(ContentType contentType, StreamOpener opener, long start, long length) {
        super(CHUNK_SIZE, contentType);
        this.opener = opener;
        this.start = start;
        this.length = length;
    }

    @Override
    public long getContentLength() {
        return length;
    }

    @Override
    protected int availableData() {
        return ended ? 0 : CHUNK_SIZE;
    }

    @Override
    protected void produceData(final StreamChannel<ByteBuffer> channel) throws IOException {
        try {
            if (pending != null) {
                channel.write(pending);
                if (pending.hasRemaining()) {
                    return;
                }
                pending = null;
            }
            if (bytesRead < length) {
                if (input == null) {
                    input = opener.open();
                    skipFully(input, start);
                    YaaccLogger.d(getClass().getName(), "Opened stream for range " + start + "-" + (start + length - 1));
                }
                byte[] buffer = new byte[(int) Math.min(CHUNK_SIZE, length - bytesRead)];
                int read = input.read(buffer, 0, buffer.length);
                if (read <= 0) {
                    throw new IOException("Stream ended after " + bytesRead + " of " + length + " bytes");
                }
                bytesRead += read;
                ByteBuffer chunk = ByteBuffer.wrap(buffer, 0, read);
                channel.write(chunk);
                if (chunk.hasRemaining()) {
                    pending = chunk;
                    return;
                }
            }
            if (bytesRead >= length) {
                // the end of the stream goes with the last write: no call follows once nothing is available
                close();
                ended = true;
                channel.endStream();
            }
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    /** {@link InputStream#skip(long)} may skip less than asked (even 0) before the end. */
    private static void skipFully(InputStream in, long count) throws IOException {
        long left = count;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped > 0) {
                left -= skipped;
            } else if (in.read() < 0) {
                throw new IOException("Stream ended " + left + " bytes before the start of the range");
            } else {
                left--;
            }
        }
    }

    private void close() {
        if (input != null) {
            try {
                input.close();
            } catch (IOException ignored) {
                // nothing left to do for a stream that is only read
            }
            input = null;
        }
    }

    boolean isOpen() {
        return input != null;
    }

    @Override
    public void failed(final Exception cause) {
        YaaccLogger.e(getClass().getName(), "Streaming failed", cause);
        close();
    }

    @Override
    public void releaseResources() {
        close();
        super.releaseResources();
    }

    @Override
    public boolean isRepeatable() {
        return false; // a stream is read once
    }
}
