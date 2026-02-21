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
package de.yaacc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class SAFMetadataTest {

    @Test
    public void testCreation() {
        SAFMetadata metadata = new SAFMetadata(
            "03:45",
            "audio/mpeg",
            "1",
            5432100L
        );
        
        assertEquals("03:45", metadata.duration);
        assertEquals("audio/mpeg", metadata.mimeType);
        assertEquals("1", metadata.shortId);
        assertEquals(5432100L, metadata.fileSize);
        assertNotEquals(0L, metadata.timestamp);
    }

    @Test
    public void testSerialize() {
        SAFMetadata metadata = new SAFMetadata(
            "03:45",
            "audio/mpeg",
            "1",
            1234567L
        );
        
        String serialized = metadata.serialize();
        // Check format: duration|mimeType|shortId|fileSize|timestamp
        String[] parts = serialized.split("\\|", 5);
        assertEquals("03:45", parts[0]);
        assertEquals("audio/mpeg", parts[1]);
        assertEquals("1", parts[2]);
        assertEquals("1234567", parts[3]);
        assertNotNull(parts[4]); // timestamp
    }

    @Test
    public void testDeserialize() {
        String data = "03:45|audio/mpeg|1|1234567|1708257600000";
        
        SAFMetadata metadata = SAFMetadata.deserialize(data);
        
        assertNotNull(metadata);
        assertEquals("03:45", metadata.duration);
        assertEquals("audio/mpeg", metadata.mimeType);
        assertEquals("1", metadata.shortId);
        assertEquals(1234567L, metadata.fileSize);
    }

    @Test
    public void testDeserializeNull() {
        assertNull(SAFMetadata.deserialize(null));
    }

    @Test
    public void testDeserializeInvalid() {
        assertNull(SAFMetadata.deserialize(""));
        assertNull(SAFMetadata.deserialize("only_one_part"));
    }

    @Test
    public void testSerializeDeserializeRoundTrip() {
        SAFMetadata original = new SAFMetadata(
            "01:30:00",
            "video/mp4",
            "2",
            9876543L
        );
        
        String serialized = original.serialize();
        SAFMetadata deserialized = SAFMetadata.deserialize(serialized);
        
        assertNotNull(deserialized);
        assertEquals(original.duration, deserialized.duration);
        assertEquals(original.mimeType, deserialized.mimeType);
        assertEquals(original.shortId, deserialized.shortId);
        assertEquals(original.fileSize, deserialized.fileSize);
    }

    @Test
    public void testNullValues() {
        SAFMetadata metadata = new SAFMetadata(null, null, null, 0L);
        
        assertNull(metadata.duration);
        assertNull(metadata.mimeType);
        assertNull(metadata.shortId);
        assertEquals(0L, metadata.fileSize);
    }

    @Test
    public void testVideoMimeType() {
        SAFMetadata metadata = new SAFMetadata(
            "02:15:30",
            "video/mp4",
            "3",
            10000000L
        );
        
        assertEquals("video/mp4", metadata.mimeType);
    }

    @Test
    public void testImageMimeType() {
        SAFMetadata metadata = new SAFMetadata(
            null,
            "image/jpeg",
            "4",
            500000L
        );
        
        assertEquals("image/jpeg", metadata.mimeType);
        assertNull(metadata.duration);
    }
}
