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
import static org.junit.Assert.fail;

import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.impl.BasicEntityDetails;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;

/** The server collects the body of a request only up to a limit. */
public class BoundedEntityConsumerTest {

    private static BoundedEntityConsumer started(int limit, byte[][] result) throws Exception {
        BoundedEntityConsumer consumer = new BoundedEntityConsumer(limit);
        consumer.streamStart(new BasicEntityDetails(-1, ContentType.TEXT_XML), new FutureCallback<byte[]>() {
            @Override
            public void completed(byte[] body) {
                result[0] = body;
            }

            @Override
            public void failed(Exception ex) {
            }

            @Override
            public void cancelled() {
            }
        });
        return consumer;
    }

    @Test
    public void aBodyWithinTheLimitIsCollected() throws Exception {
        byte[][] result = new byte[1][];
        BoundedEntityConsumer consumer = started(10, result);

        consumer.consume(ByteBuffer.wrap(new byte[]{1, 2, 3, 4, 5}));
        consumer.consume(ByteBuffer.wrap(new byte[]{6, 7, 8, 9, 10}));
        consumer.streamEnd(null);

        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, result[0]);
    }

    @Test
    public void aBodyOverTheLimitEndsTheRequest() throws Exception {
        BoundedEntityConsumer consumer = started(10, new byte[1][]);
        consumer.consume(ByteBuffer.wrap(new byte[6]));

        try {
            consumer.consume(ByteBuffer.wrap(new byte[5]));
            fail();
        } catch (IOException expected) {
            // too long
        }
    }
}
