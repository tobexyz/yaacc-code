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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.apache.hc.core5.http.ContentType;
import org.junit.Test;

import java.io.IOException;

import de.yaacc.upnp.server.http.ProducerTestSupport.FakeChannel;

/** {@link LengthOnlyEntityProducer}: the entity of a HEAD answer carries no data and opens nothing. */
public class LengthOnlyEntityProducerTest {

    @Test
    public void carriesTheLengthAndTheTypeForTheHeaders() {
        LengthOnlyEntityProducer producer = new LengthOnlyEntityProducer(123_456L, ContentType.parse("video/mp4"));

        assertEquals(123_456L, producer.getContentLength());
        assertEquals("video/mp4", producer.getContentType());
    }

    @Test
    public void anUnknownLengthIsMinusOne() {
        assertEquals(-1L, new LengthOnlyEntityProducer(-1L, ContentType.APPLICATION_OCTET_STREAM).getContentLength());
    }

    @Test
    public void producesNoDataAndOnlyEndsTheStream() throws IOException {
        LengthOnlyEntityProducer producer = new LengthOnlyEntityProducer(1_000L, ContentType.APPLICATION_OCTET_STREAM);
        FakeChannel channel = new FakeChannel();

        assertEquals(0, producer.available());
        producer.produce(channel);

        assertEquals(0, channel.writes);
        assertEquals(0, channel.bytes().length);
        assertTrue(channel.ended);
    }

    @Test
    public void isNotRepeatableAndSurvivesFailureAndRelease() {
        LengthOnlyEntityProducer producer = new LengthOnlyEntityProducer(1L, null);

        producer.failed(new IOException("connection reset"));
        producer.releaseResources();
        producer.releaseResources();

        assertFalse(producer.isRepeatable());
        assertNull("no content type given, none announced", producer.getContentType());
    }
}
