/*
 *
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
package de.yaacc.fcast.protocol.v4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Task 7.3: the hand-written writer makes buffers that its reader understands (the reader is pinned to the reference implementation's
 * buffers in {@link V4ReaderTest}) and, once, that the reference's own reader accepts: this test writes {@code build/v4-java-buffers.txt}
 * which the scratch program of docs/tech.md ("Checking the writer against the reference") reads with the generated code of the reference.
 */
public class V4WriterTest {

    private static V4In read(byte[] bytes) throws Exception {
        return V4Reader.read(bytes);
    }

    @Test
    public void aLoadWithAllFieldsReadsBack() throws Exception {
        V4In in = read(V4Writer.load("video/mp4", "http://192.168.0.5:8080/a b.mp4", 12.5, 0.25, 1.5, "Titel äöü", "http://h/a.jpg"));
        assertEquals(V4In.Kind.LOAD, in.kind);
        assertEquals("video/mp4", in.item.container);
        assertEquals("http://192.168.0.5:8080/a b.mp4", in.item.url);
        assertEquals("Titel äöü", in.item.title);
        assertEquals("http://h/a.jpg", in.item.thumbnailUrl);
        assertEquals(12.5, in.item.startSeconds, 1e-9);
        assertEquals(0.25, in.item.volume, 1e-6);
        assertEquals(1.5, in.item.speed, 1e-6);
    }

    @Test
    public void aMinimalLoadHasNoOptionalFieldsAndAZeroVolumeIsKept() throws Exception {
        V4In minimal = read(V4Writer.load("audio/mpeg", "http://h/a.mp3", null, null, null, null, null));
        assertNull(minimal.item.startSeconds);
        assertNull(minimal.item.volume);
        assertNull(minimal.item.speed);
        assertNull(minimal.item.title);
        V4In zero = read(V4Writer.load("audio/mpeg", "http://h/a.mp3", 0.0, 0.0, null, "", ""));
        assertEquals("a start at 0 and a volume of 0 are values, not absence", 0.0, zero.item.startSeconds, 0.0);
        assertEquals(0.0, zero.item.volume, 0.0);
        assertNull("an empty title is left out", zero.item.title);
    }

    @Test
    public void progressVolumeSpeedAndState() throws Exception {
        V4In progress = read(V4Writer.progress(90.25, 0));
        assertEquals(V4In.Kind.PROGRESS, progress.kind);
        assertEquals(90.25, progress.positionSeconds, 1e-9);
        assertEquals(0.0, progress.durationSeconds, 0.0);
        assertEquals(0.75, read(V4Writer.volume(0.75)).number, 1e-6);
        assertEquals(0.0, read(V4Writer.volume(0.0)).number, 0.0);
        assertEquals(2.0, read(V4Writer.speed(2.0)).number, 1e-6);
        assertEquals(Integer.valueOf(V4Schema.STATE_PAUSED), read(V4Writer.state(V4Schema.STATE_PAUSED)).code);
        assertEquals(Integer.valueOf(V4Schema.STATE_PLAYING), read(V4Writer.state(V4Schema.STATE_PLAYING)).code);
        assertEquals(V4In.Kind.STOP, read(V4Writer.stop()).kind);
    }

    @Test
    public void timesThatAreNotUsableBecomeZeroAndHugeOnesDoNotOverflow() throws Exception {
        assertEquals(0.0, read(V4Writer.progress(-5, Double.NaN)).positionSeconds, 0.0);
        assertEquals(0.0, read(V4Writer.progress(-5, Double.NaN)).durationSeconds, 0.0);
        Double huge = read(V4Writer.progress(Double.POSITIVE_INFINITY, 0)).positionSeconds;
        assertTrue(huge == null || huge > 1e12);
    }

    @Test
    public void theSenderIntroductionIsAPacketThatTheReaderTakesForAMessageItDoesNotUse() throws Exception {
        byte[] bytes = V4Writer.senderIntroduction("YAACC", "YAACC app", "1.2.3");
        assertNotNull(bytes);
        assertEquals(V4In.Kind.OTHER, read(bytes).kind);
    }

    @Test
    public void everyBufferIsAValidFlatBuffersBufferWithTheRootAtTheStart() throws Exception {
        for (byte[] bytes : buffers().values()) {
            assertTrue(bytes.length >= 16);
            int root = (bytes[0] & 0xff) | (bytes[1] & 0xff) << 8 | (bytes[2] & 0xff) << 16 | (bytes[3] & 0xff) << 24;
            assertTrue(root >= 4 && root < bytes.length);
        }
    }

    /** The buffers that the reference reader checks (see the class comment). */
    private static Map<String, byte[]> buffers() {
        Map<String, byte[]> map = new LinkedHashMap<>();
        map.put("senderIntroduction", V4Writer.senderIntroduction("YAACC", "YAACC app", "1.2.3"));
        map.put("loadFull", V4Writer.load("video/mp4", "http://192.168.0.5:8080/a.mp4", 12.5, 0.25, 1.5, "Song", "http://h/a.jpg"));
        map.put("loadMinimal", V4Writer.load("audio/mpeg", "http://h/a.mp3", null, null, null, null, null));
        map.put("loadZeros", V4Writer.load("audio/mpeg", "http://h/a.mp3", 0.0, 0.0, 0.0, null, null));
        map.put("progress", V4Writer.progress(1.5, 61));
        map.put("progressSeek", V4Writer.progress(90.25, 0));
        map.put("volume", V4Writer.volume(0.5));
        map.put("volumeZero", V4Writer.volume(0.0));
        map.put("speed", V4Writer.speed(1.25));
        map.put("statePlaying", V4Writer.state(V4Schema.STATE_PLAYING));
        map.put("statePaused", V4Writer.state(V4Schema.STATE_PAUSED));
        map.put("stop", V4Writer.stop());
        map.put("changeTrack", V4Writer.changeTrack(3L, V4Schema.TRACK_AUDIO));
        map.put("changeTrackOff", V4Writer.changeTrack(null, V4Schema.TRACK_SUBTITLE));
        map.put("addSubtitle", V4Writer.addSubtitleSource("http://h/a.srt", true, "English (external)"));
        map.put("addSubtitleNoName", V4Writer.addSubtitleSource("http://h/b.vtt", false, null));
        map.put("progressInterval", V4Writer.progressInterval(1.0));
        return map;
    }

    @Test
    public void writesTheBuffersForTheReferenceReader() throws Exception {
        File out = new File("build/v4-java-buffers.txt");
        out.getParentFile().mkdirs();
        try (PrintWriter writer = new PrintWriter(out, "UTF-8")) {
            for (Map.Entry<String, byte[]> entry : buffers().entrySet()) {
                StringBuilder hex = new StringBuilder();
                for (byte b : entry.getValue()) {
                    hex.append(String.format("%02x", b));
                }
                writer.println(entry.getKey() + "=" + hex);
            }
        }
        assertTrue(out.length() > 0);
    }
}
