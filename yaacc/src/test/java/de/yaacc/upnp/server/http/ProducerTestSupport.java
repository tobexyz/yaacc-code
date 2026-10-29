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
import org.apache.hc.core5.http.nio.DataStreamChannel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

import de.yaacc.share.ByteRangeResolver;
import de.yaacc.share.PositionalSource;

/** Test doubles shared by the entity producer tests. */
final class ProducerTestSupport {

    static final String KEY = "AAAAAAAAAAAAAAAAAAAAAA";

    private ProducerTestSupport() {
    }

    /** A deterministic body whose every position differs from its neighbours. */
    static byte[] body(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) ((i * 31 + (i >> 8) * 7) & 0xFF);
        }
        return data;
    }

    /** A {@link PositionalSource} over a byte array that counts its closes. */
    static final class ArraySource extends PositionalSource {
        private final byte[] data;
        int closes;

        ArraySource(byte[] data) {
            super(data.length);
            this.data = data;
        }

        @Override
        protected int readAtInternal(long position, byte[] buf, int off, int len) {
            int n = (int) Math.min(len, data.length - position);
            System.arraycopy(data, (int) position, buf, off, n);
            return n;
        }

        @Override
        protected void closeInternal() {
            closes++;
        }
    }

    static ByteRangeResolver.Result whole(PositionalSource source) {
        return ByteRangeResolver.resolve(ByteRangeResolver.parseHeader(null), source.size());
    }

    /**
     * The client side of a response: accepts at most {@code maxPerWrite} bytes per call (0 = a
     * client that is not reading right now), records what it took.
     */
    static final class FakeChannel implements DataStreamChannel {
        final ByteArrayOutputStream received = new ByteArrayOutputStream();
        int maxPerWrite = Integer.MAX_VALUE;
        boolean ended;
        int writes;
        int outputRequests;
        IOException failOnWrite;

        @Override
        public void requestOutput() {
            outputRequests++;
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            if (failOnWrite != null) {
                throw failOnWrite;
            }
            writes++;
            int n = Math.min(src.remaining(), maxPerWrite);
            byte[] chunk = new byte[n];
            src.get(chunk);
            received.write(chunk, 0, n);
            return n;
        }

        @Override
        public void endStream() {
            ended = true;
        }

        @Override
        public void endStream(List<? extends Header> trailers) {
            ended = true;
        }

        byte[] bytes() {
            return received.toByteArray();
        }
    }
}
