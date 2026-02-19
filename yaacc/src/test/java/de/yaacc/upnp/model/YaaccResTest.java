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
package de.yaacc.upnp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.fourthline.cling.support.model.Res;
import org.fourthline.cling.support.model.Protocol;
import org.fourthline.cling.support.model.ProtocolInfo;
import org.junit.Test;

public class YaaccResTest {

    private ProtocolInfo createProtocolInfo(String mimeType) {
        return new ProtocolInfo(Protocol.HTTP_GET, ProtocolInfo.WILDCARD, mimeType, ProtocolInfo.WILDCARD);
    }

    @Test
    public void testBasicCreation() {
        YaaccRes res = new YaaccRes(
            createProtocolInfo("audio/mpeg"),
            12345L,
            "03:45",
            256000L,
            "http://example.com/file.mp3"
        );
        
        assertEquals("http://example.com/file.mp3", res.getValue());
        assertEquals(Long.valueOf(12345L), res.getSize());
        assertEquals("03:45", res.getDuration());
        assertEquals(Long.valueOf(256000L), res.getBitrate());
    }

    @Test
    public void testAudioProperties() {
        YaaccRes res = new YaaccRes(
            createProtocolInfo("audio/mpeg"),
            12345L,
            "03:45",
            256000L,
            44100L,
            2L,
            16L,
            "http://example.com/file.mp3"
        );
        
        assertEquals(Long.valueOf(44100L), res.getSampleFrequency());
        assertEquals(Long.valueOf(2L), res.getNrAudioChannels());
        assertEquals(Long.valueOf(16L), res.getBitsPerSample());
        assertEquals(Long.valueOf(256000L), res.getBitrate());
    }

    @Test
    public void testVideoProperties() {
        YaaccRes res = new YaaccRes(
            createProtocolInfo("video/mp4"),
            1234567L,
            "1920x1080",
            "http://example.com/video.mp4"
        );
        
        assertEquals("1920x1080", res.getResolution());
        assertEquals("http://example.com/video.mp4", res.getValue());
    }

    @Test
    public void testToClingRes() {
        YaaccRes yaaccRes = new YaaccRes(
            createProtocolInfo("audio/mpeg"),
            12345L,
            "03:45",
            256000L,
            44100L,
            2L,
            16L,
            "http://example.com/file.mp3"
        );
        
        Res clingRes = yaaccRes.toClingRes();
        
        assertNotNull(clingRes);
        assertEquals("http://example.com/file.mp3", clingRes.getValue());
        assertEquals(Long.valueOf(12345L), clingRes.getSize());
        assertEquals("03:45", clingRes.getDuration());
        assertEquals(Long.valueOf(44100L), clingRes.getSampleFrequency());
        assertEquals(Long.valueOf(2L), clingRes.getNrAudioChannels());
    }

    @Test
    public void testMimeTypeVariants() {
        // Audio types
        YaaccRes mp3 = new YaaccRes(createProtocolInfo("audio/mpeg"), null, null, null, "uri");
        assertEquals("audio/mpeg", mp3.getProtocolInfo().getContentFormat());
        
        YaaccRes ogg = new YaaccRes(createProtocolInfo("audio/ogg"), null, null, null, "uri");
        assertEquals("audio/ogg", ogg.getProtocolInfo().getContentFormat());
        
        // Video types
        YaaccRes mp4 = new YaaccRes(createProtocolInfo("video/mp4"), null, null, null, "uri");
        assertEquals("video/mp4", mp4.getProtocolInfo().getContentFormat());
        
        // Image types
        YaaccRes jpeg = new YaaccRes(createProtocolInfo("image/jpeg"), null, null, null, "uri");
        assertEquals("image/jpeg", jpeg.getProtocolInfo().getContentFormat());
    }

    @Test
    public void testNullValues() {
        YaaccRes res = new YaaccRes(
            createProtocolInfo("audio/mpeg"),
            null,
            null,
            null,
            "http://example.com/file.mp3"
        );
        
        assertEquals(null, res.getSize());
        assertEquals(null, res.getDuration());
        assertEquals(null, res.getBitrate());
    }
}
