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
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;
import java.util.Random;

import de.yaacc.fcast.protocol.FCastProtocolException;
import de.yaacc.fcast.protocol.V4Golden;

/** Task 7.3: the hand-written reader understands what the reference implementation writes, and survives what it does not. */
public class V4ReaderTest {

    private static V4In read(String golden) throws Exception {
        return V4Reader.read(V4Golden.get(golden));
    }

    @Test
    public void aSingleItemLoadWithAllFields() throws Exception {
        V4In in = read("loadFull");
        assertEquals(V4In.Kind.LOAD, in.kind);
        assertEquals("video/mp4", in.item.container);
        assertEquals("http://192.168.0.5:8080/a.mp4", in.item.url);
        assertEquals("Song", in.item.title);
        assertEquals("http://h/a.jpg", in.item.thumbnailUrl);
        assertEquals(12.5, in.item.startSeconds, 1e-9);
        assertEquals(0.25, in.item.volume, 1e-6);
        assertEquals(1.5, in.item.speed, 1e-6);
        assertNull(in.queueIndex);
        assertEquals(1, in.queueSize);
    }

    @Test
    public void aMinimalLoadHasNoOptionalFields() throws Exception {
        V4In in = read("loadMinimal");
        assertEquals("audio/mpeg", in.item.container);
        assertEquals("http://h/a.mp3", in.item.url);
        assertNull(in.item.title);
        assertNull(in.item.thumbnailUrl);
        assertNull(in.item.startSeconds);
        assertNull(in.item.volume);
        assertNull(in.item.speed);
    }

    @Test
    public void aQueueLoadGivesTheItemItStartsAt() throws Exception {
        V4In in = read("loadQueue");
        assertEquals(V4In.Kind.LOAD, in.kind);
        assertEquals("http://h/1.mp4", in.item.url);
        assertEquals(Integer.valueOf(1), in.queueIndex);
        assertEquals(3, in.queueSize);
    }

    @Test
    public void progressIsReadInSeconds() throws Exception {
        V4In in = read("progress");
        assertEquals(V4In.Kind.PROGRESS, in.kind);
        assertEquals(1.5, in.positionSeconds, 1e-9);
        assertEquals(61.0, in.durationSeconds, 1e-9);
    }

    @Test
    public void aProgressOfZeroIsZeroNotAbsent() throws Exception {
        V4In in = read("progressZero");
        assertEquals(0.0, in.positionSeconds, 0.0);
        assertEquals(0.0, in.durationSeconds, 0.0);
    }

    @Test
    public void volumeAndSpeedAreNumbersAndAZeroVolumeIsZero() throws Exception {
        assertEquals(0.5, read("volume").number, 1e-6);
        assertEquals(0.0, read("volumeZero").number, 0.0);
        assertEquals(1.25, read("speed").number, 1e-6);
        assertEquals(V4In.Kind.VOLUME, read("volume").kind);
        assertEquals(V4In.Kind.SPEED, read("speed").kind);
    }

    @Test
    public void everyPlaybackStateIsRead() throws Exception {
        assertEquals(Integer.valueOf(V4Schema.STATE_IDLE), read("stateIdle").code);
        assertEquals(Integer.valueOf(V4Schema.STATE_BUFFERING), read("stateBuffering").code);
        assertEquals(Integer.valueOf(V4Schema.STATE_PLAYING), read("statePlaying").code);
        assertEquals(Integer.valueOf(V4Schema.STATE_PAUSED), read("statePaused").code);
        assertEquals(Integer.valueOf(V4Schema.STATE_ENDED), read("stateEnded").code);
        assertEquals(V4In.Kind.STATE, read("statePlaying").kind);
    }

    @Test
    public void stopAndError() throws Exception {
        assertEquals(V4In.Kind.STOP, read("stop").kind);
        V4In error = read("error");
        assertEquals(V4In.Kind.ERROR, error.kind);
        assertEquals(Integer.valueOf(5), error.code); // UnsupportedFormat
        assertEquals("UnsupportedFormat", V4Schema.ERROR_KINDS[error.code]);
        assertEquals("Internal", V4Schema.ERROR_KINDS[read("errorNoNumber").code]);
    }

    @Test
    public void theReceiversIntroduction() throws Exception {
        V4In in = read("receiverIntroduction");
        assertEquals(V4In.Kind.RECEIVER_INTRODUCTION, in.kind);
        assertEquals("FCast-pc", in.displayName);
        assertEquals("FCast Receiver Desktop", in.appName);
        assertEquals("3.0.4", in.appVersion);
    }

    @Test
    public void theCapabilitiesOfTheReceiver() throws Exception {
        V4In.Caps caps = read("receiverIntroduction").caps;
        assertNotNull(caps);
        assertEquals(Arrays.asList("http", "https"), caps.protocols);
        assertEquals(Arrays.asList("mp4", "mkv"), caps.containers);
        assertEquals(Arrays.asList("h264"), caps.videoFormats);
        assertEquals(Arrays.asList("aac", "opus"), caps.audioFormats);
        assertEquals(Arrays.asList("srt", "vtt"), caps.subtitleFormats);
        assertTrue(caps.hdrFormats.isEmpty());
        assertEquals(Arrays.asList("png", "jpeg"), caps.imageFormats);
        assertTrue(caps.externalSubtitles);
        assertEquals(false, caps.mirroring);
        assertEquals(0.01, caps.volumeStep, 1e-6);
        assertEquals(0, caps.displayWidth);
    }

    @Test
    public void aTrackChangeNamesTheTypeAndTheTrackOrNone() throws Exception {
        V4In audio = read("changeTrack");
        assertEquals(V4In.Kind.TRACK_SELECTED, audio.kind);
        assertEquals(Long.valueOf(3), audio.trackId);
        assertEquals(Integer.valueOf(V4Schema.TRACK_AUDIO), audio.trackType);
        V4In off = read("changeTrackOff");
        assertNull(off.trackId);
        assertEquals(Integer.valueOf(V4Schema.TRACK_SUBTITLE), off.trackType);
    }

    @Test
    public void theTracksOfTheItem() throws Exception {
        V4In in = read("tracks");
        assertEquals(V4In.Kind.TRACKS, in.kind);
        assertEquals(4, in.tracks.size());
        assertEquals(1, in.tracks.get(0).id);
        assertEquals("eng", in.tracks.get(0).language);
        assertEquals("English", in.tracks.get(0).title);
        assertEquals(V4Schema.TRACK_AUDIO, in.tracks.get(0).type);
        assertEquals(2, in.tracks.get(1).id);
        assertNull(in.tracks.get(1).title);
        assertEquals(V4Schema.TRACK_SUBTITLE, in.tracks.get(1).type);
        assertEquals(0, in.tracks.get(2).id);
        assertEquals(V4Schema.TRACK_VIDEO, in.tracks.get(2).type);
        assertEquals(-1, in.tracks.get(3).type);
    }

    @Test
    public void messagesThatASenderSendsAreOtherWhenTheyComeFromAReceiver() throws Exception {
        assertEquals(V4In.Kind.OTHER, read("addSubtitle").kind);
        assertEquals(V4In.Kind.OTHER, read("progressInterval").kind);
    }

    // ---- hostile input

    @Test
    public void emptyShortAndNullBuffersAreRefused() throws Exception {
        for (byte[] bytes : new byte[][]{null, new byte[0], new byte[3], new byte[7]}) {
            try {
                V4Reader.read(bytes);
                fail("a buffer of " + (bytes == null ? "null" : bytes.length) + " bytes");
            } catch (FCastProtocolException expected) {
                assertEquals(FCastProtocolException.Kind.BAD_BODY, expected.getKind());
            }
        }
    }

    @Test
    public void everyCutOfEveryGoldenBufferEndsInAnErrorOrAMessageNeverInAnotherException() throws Exception {
        for (String name : new String[]{"loadFull", "loadQueue", "progress", "receiverIntroduction", "tracks", "error", "stateEnded"}) {
            byte[] whole = V4Golden.get(name);
            for (int length = 0; length < whole.length; length++) {
                try {
                    V4Reader.read(Arrays.copyOf(whole, length));
                } catch (FCastProtocolException ok) {
                    assertEquals(FCastProtocolException.Kind.BAD_BODY, ok.getKind());
                }
            }
        }
    }

    @Test
    public void randomBytesAndMutatedGoldenBuffersNeverThrowAnythingElse() throws Exception {
        Random random = new Random(7);
        String[] names = {"loadFull", "loadQueue", "tracks", "receiverIntroduction", "progress", "error"};
        for (int round = 0; round < 3000; round++) {
            byte[] bytes;
            if (round % 2 == 0) {
                bytes = new byte[random.nextInt(200)];
                random.nextBytes(bytes);
            } else {
                bytes = V4Golden.get(names[random.nextInt(names.length)]);
                for (int i = 0; i < 1 + random.nextInt(4); i++) {
                    bytes[random.nextInt(bytes.length)] = (byte) random.nextInt(256);
                }
            }
            try {
                assertNotNull(V4Reader.read(bytes));
            } catch (FCastProtocolException ok) {
                assertEquals(FCastProtocolException.Kind.BAD_BODY, ok.getKind());
            }
        }
    }

    @Test
    public void manyFieldsPointingAtOneHugeStringAreRefusedNotDecodedOverAndOver() throws Exception {
        // a buffer of about 40 KB whose 256 track titles are all the same text of 30 KB: decoded 256 times it would be 7.7 MB
        com.google.flatbuffers.FlatBufferBuilder b = new com.google.flatbuffers.FlatBufferBuilder(64 * 1024);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 30_000; i++) {
            big.append('x');
        }
        int title = b.createString(big);
        int lang = b.createString("eng");
        int[] tracks = new int[256];
        for (int i = 0; i < tracks.length; i++) {
            b.startTable(5);
            b.addInt(0, i + 1, 0);
            b.addOffset(1, lang, 0);
            b.addOffset(2, title, 0);
            tracks[i] = b.endTable();
        }
        int vector = b.createVectorOfTables(tracks);
        b.startTable(1);
        b.addOffset(0, vector, 0);
        int payload = b.endTable();
        b.startTable(2);
        b.addByte(0, (byte) V4Schema.MSG_TRACKS_AVAILABLE, 0);
        b.addOffset(1, payload, 0);
        b.finish(b.endTable());
        try {
            V4Reader.read(b.sizedByteArray());
            fail("a title of 30 KB is over the limit of a name");
        } catch (FCastProtocolException expected) {
            assertEquals(FCastProtocolException.Kind.BAD_BODY, expected.getKind());
        }
    }

    @Test
    public void manyAllowedStringsThatTogetherExceedTheBudgetAreRefused() throws Exception {
        com.google.flatbuffers.FlatBufferBuilder b = new com.google.flatbuffers.FlatBufferBuilder(4096);
        StringBuilder medium = new StringBuilder();
        for (int i = 0; i < 1024; i++) {
            medium.append('y');
        }
        int title = b.createString(medium); // each title alone is allowed (1024 bytes), 256 of them are 262,912 bytes: over the budget of one buffer
        int lang = b.createString("eng");
        int[] tracks = new int[256];
        for (int i = 0; i < tracks.length; i++) {
            b.startTable(5);
            b.addInt(0, i + 1, 0);
            b.addOffset(1, lang, 0);
            b.addOffset(2, title, 0);
            tracks[i] = b.endTable();
        }
        int vector = b.createVectorOfTables(tracks);
        b.startTable(1);
        b.addOffset(0, vector, 0);
        int payload = b.endTable();
        b.startTable(2);
        b.addByte(0, (byte) V4Schema.MSG_TRACKS_AVAILABLE, 0);
        b.addOffset(1, payload, 0);
        b.finish(b.endTable());
        try {
            V4Reader.read(b.sizedByteArray());
            fail("the budget of one buffer is " + FbTable.BUDGET_BYTES + " bytes of text");
        } catch (FCastProtocolException expected) {
            assertEquals(FCastProtocolException.Kind.BAD_BODY, expected.getKind());
        }
    }

    @Test
    public void aHostileVectorLengthIsRefusedNotLooped() throws Exception {
        byte[] bytes = V4Golden.get("tracks");
        // the length word of the tracks vector: find it (4 tracks) and make it enormous
        boolean patched = false;
        for (int i = 0; i + 4 <= bytes.length && !patched; i++) {
            if (bytes[i] == 4 && bytes[i + 1] == 0 && bytes[i + 2] == 0 && bytes[i + 3] == 0 && i > 12) {
                bytes[i + 3] = 0x7f;
                bytes[i + 2] = (byte) 0xff;
                patched = true;
            }
        }
        assertTrue(patched);
        try {
            V4Reader.read(bytes);
        } catch (FCastProtocolException expected) {
            assertEquals(FCastProtocolException.Kind.BAD_BODY, expected.getKind());
        }
    }
}
