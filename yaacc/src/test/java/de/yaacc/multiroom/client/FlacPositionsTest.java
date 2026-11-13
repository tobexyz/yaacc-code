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
package de.yaacc.multiroom.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import de.yaacc.multiroom.protocol.WireChunkMessage;
import org.junit.Test;

/**
 * Where the engine takes the position of a flac chunk from (Group 8k): the FLAC frame header at the start of the chunk
 * (exact, whatever the decoder does) or, when that can not be read, the count of decoded frames (the way of Group 8j).
 */
public class FlacPositionsTest {

    private static DeviceModel phone() {
        DeviceModel m = new DeviceModel();
        m.startThresholdFrames = 1920;
        m.thresholdSettable = true;
        m.timestampPeriodMicros = 20_000;
        m.outputLatencyMicros = 35_000;
        return m;
    }

    private static DeviceRig blocks() {
        return DeviceStallTest.rig(DeviceStallTest.Source.REAL_1152_LAG1);
    }

    private static WireChunkMessage withPayload(WireChunkMessage chunk, byte[] payload) {
        long micros = chunk.timestampMicros();
        return new WireChunkMessage((int) Math.floorDiv(micros, 1_000_000L), (int) Math.floorMod(micros, 1_000_000L), payload);
    }

    @Test
    public void theEngineReadsThePositionsFromTheFrameHeaders() {
        DeviceRig rig = blocks();
        rig.connect();
        rig.run(10_000);
        EngineStatus s = rig.status();
        assertEquals(1, s.flacPositionSource);
        assertTrue("chunks read " + s.flacPositionChunks, s.flacPositionChunks > 300);
        assertEquals(0, s.flacPositionRefusals);
        String text = SnapcastClientStatusText.of(DeviceRestartTest.viewOf(s), 0);
        assertTrue(text, text.contains("flac positions: from frame headers; " + s.flacPositionChunks + " chunks read, 0 refused"));
        assertTrue(rig.engine.trace().text(), rig.engine.trace().text().contains("flac chunk positions  source (1 frame headers, 0 frame count)=1"));
    }

    @Test
    public void aPayloadWithoutFrameHeadersFallsBackToCountingFramesAndSaysSo() {
        DeviceRig rig = new DeviceRig(phone());
        rig.flac = true; // raw samples, no headers
        rig.connect();
        rig.run(10_000);
        EngineStatus s = rig.status();
        assertEquals(2, s.flacPositionSource);
        assertEquals(3, s.flacPositionRefusals);
        assertEquals(0, s.flacPositionChunks);
        String text = SnapcastClientStatusText.of(DeviceRestartTest.viewOf(s), 0);
        assertTrue(text, text.contains("flac positions: from frame count; 0 chunks read, 3 refused"));
        assertTrue(rig.report(), s.aligned && Math.abs(rig.syncErrorMs()) < 3);
    }

    /** A chunk with a damaged header is not used (counted); the stream goes on and the guard decides nothing from it. */
    @Test
    public void aDamagedHeaderNowAndThenIsCountedAndIgnored() {
        DeviceRig rig = blocks();
        int[] calls = new int[1];
        rig.chunkFilter = chunk -> {
            if (++calls[0] > 200 && calls[0] % 50 < 2) { // two chunks in a row, every 50th, after the guard has learned: a bit of the CRC flipped
                byte[] payload = chunk.getPayload().clone();
                payload[5] ^= 0x10;
                return withPayload(chunk, payload);
            }
            return chunk;
        };
        rig.connect();
        rig.run(120_000);
        EngineStatus s = rig.status();
        assertEquals("positions still from the headers", 1, s.flacPositionSource);
        assertTrue("refused " + s.flacPositionRefusals, s.flacPositionRefusals >= 80);
        assertEquals(0, s.flacShiftFills);
        assertEquals(0, s.flacReanchors);
        // the chunk itself is still decoded and played (its samples are fine)
        assertTrue(rig.report(), Math.abs(rig.syncErrorMs()) < 3 && rig.sink.counters().playedSilentFramesAfterAudio <= 480);
    }

    @Test
    public void threeDamagedHeadersInARowSendTheEngineBackToCountingFrames() {
        DeviceRig rig = blocks();
        int[] calls = new int[1];
        rig.chunkFilter = chunk -> {
            int n = ++calls[0];
            if (n >= 200 && n < 203) {
                byte[] payload = chunk.getPayload().clone();
                payload[0] = 0; // not a frame at all
                return withPayload(chunk, payload);
            }
            return chunk;
        };
        rig.connect();
        rig.run(20_000);
        EngineStatus s = rig.status();
        assertEquals(2, s.flacPositionSource);
        assertEquals(3, s.flacPositionRefusals);
        // later chunks have good headers but the session counts frames from now on (said in the trace)
        assertTrue(rig.engine.trace().text(), rig.engine.trace().text().contains("flac chunk positions  source (1 frame headers, 0 frame count)=0"));
        assertTrue(rig.report(), s.aligned);
    }

    @Test
    public void aFrameHeaderThatNamesAnotherSampleRateIsRefused() {
        DeviceRig rig = blocks();
        rig.chunkFilter = chunk -> {
            // a header of 44.1 kHz in a 48 kHz stream: the position can not be turned into time
            byte[] header = de.yaacc.multiroom.protocol.FlacFrameBuilder.fixedHeaderAt(1152L * 100, 1152, 44100, 2, 16);
            byte[] payload = chunk.getPayload().clone();
            System.arraycopy(header, 0, payload, 0, Math.min(header.length, payload.length));
            return withPayload(chunk, payload);
        };
        rig.connect();
        rig.run(5_000);
        assertEquals(2, rig.status().flacPositionSource);
    }

    /**
     * The review's S31: stamps that jump back left the stream late for good (flacGapMs went from 300 ms to 3 s). Jumps of the
     * size of the server buffer (1 s) and more make every later chunk late for every client: nothing to place.
     */
    @Test
    public void stampsThatJumpBackAreFollowedByThePlace() {
        for (long back : new long[]{300_000L, 600_000L}) { // beyond the server buffer the chunks are late for every client
            DeviceRig rig = blocks();
            rig.connect();
            rig.run(10_000);
            long resyncsBefore = rig.status().timelineResyncs;
            rig.stampShiftMicros = -back;
            rig.run(8_000);
            EngineStatus s = rig.status();
            // placed anew once, at the chunk that showed it: a re-anchor that left the chunk without an anchor would be
            // followed by a second one at the next chunk
            assertEquals("back by " + back + ": one re-anchor, one resync; " + rig.report(), 1, s.flacReanchors);
            assertEquals("back by " + back + ": " + rig.report(), resyncsBefore + 1, s.timelineResyncs);
            assertTrue("back by " + back + ": re-anchors " + s.flacReanchors + "; " + rig.report(), s.flacReanchors >= 1);
            assertTrue("back by " + back + ": " + rig.report(), s.aligned && Math.abs(rig.syncErrorMs()) < 5);
            DeviceSimSink.Counters c = rig.sink.counters();
            long audible = c.playedAudibleFrames;
            rig.run(3_000);
            assertTrue("back by " + back + ": the sound goes on", c.playedAudibleFrames - audible > 2_800L * 48);
        }
    }

    @Test
    public void stampsThatJumpForwardAreFilledAndNeverReanchored() {
        DeviceRig rig = blocks();
        rig.connect();
        rig.run(10_000);
        rig.stampShiftMicros = 200_000L;
        rig.run(6_000);
        EngineStatus s = rig.status();
        assertEquals(0, s.flacReanchors);
        assertTrue(s.flacShiftFills >= 1 && s.flacShiftFilledMs == 200);
        assertTrue(rig.report(), Math.abs(rig.syncErrorMs()) < 3);
    }

    /**
     * Two jumps of the stamps of 2.4 s in a row (each under the 3 s that the spacing rule takes) add up to more than the
     * 3 s that is filled with silence: the stream is placed anew by its stamps, not filled with seconds of silence.
     */
    @Test
    public void aFillBeyondTheLimitOfAGapPlacesTheStreamAnew() {
        DeviceRig rig = blocks();
        rig.connect();
        rig.run(10_000);
        rig.stampShiftMicros = 2_400_000L;
        rig.run(25); // one chunk
        rig.stampShiftMicros = 4_800_000L;
        rig.run(8_000);
        EngineStatus s = rig.status();
        assertTrue("silence inserted " + s.flacShiftFilledMs + " ms; " + rig.report(), s.flacShiftFilledMs <= 3_000);
        assertTrue("placed anew: resyncs " + s.timelineResyncs + "; " + rig.report(), s.timelineResyncs >= 1 && s.streamResumes >= 1);
        assertTrue(rig.report(), s.aligned);
    }

    /** A frame number so large that its position can not be turned into microseconds is refused, not multiplied. */
    @Test
    public void aPositionThatWouldOverflowIsRefused() {
        DeviceRig rig = blocks();
        rig.chunkFilter = chunk -> {
            byte[] payload = chunk.getPayload();
            int old = de.yaacc.multiroom.protocol.FlacFrameHeader.parse(payload, 0, payload.length).getLength();
            // frame number 2^31 - 1 of blocks of 65536 frames: 1.4e14 frames
            byte[] header = de.yaacc.multiroom.protocol.FlacFrameBuilder.header(false, 0x7fffffffL, 65536, 48000, 2, 16);
            byte[] changed = new byte[header.length + payload.length - old];
            System.arraycopy(header, 0, changed, 0, header.length);
            System.arraycopy(payload, old, changed, header.length, payload.length - old);
            return withPayload(chunk, changed);
        };
        rig.connect();
        rig.run(3_000);
        assertEquals(2, rig.status().flacPositionSource);
    }

    /**
     * A decoder that holds back random chunks and a stream that is placed anew (a stall of 3 s and more) at the phase of
     * the holds: the anchor is the stamp of the first frame that comes out, so the sound is where it was.
     */
    @Test
    public void aStreamPlacedAnewWhileTheDecoderHoldsBlocksBackIsOnItsStamp() {
        for (int k = 0; k < 12; k++) {
            DeviceRig rig = DeviceStallTest.rig(DeviceStallTest.Source.REAL_1152);
            rig.decoderHoldPermille = 300;
            rig.decoderHoldMaxCalls = 2;
            rig.connect();
            rig.run(10_000);
            rig.stallSource(3_000 + k * 17L);
            rig.run(6_000);
            assertTrue("run " + k + ": " + rig.report(), Math.abs(rig.syncErrorMs()) < 3 && rig.status().aligned);
        }
    }

    /**
     * The first chunk after the stall is held by a decoder that already holds the last block before it: the stream is placed
     * anew (3.1 s) while the decoder gives nothing back, and the block that comes out later is that of before the stall.
     * Its stamp is the one of this chunk less what the decoder holds.
     */
    @Test
    public void aStreamPlacedAnewWhileTheFirstChunkIsHeldIsOnItsStamp() {
        for (int calls = 1; calls <= 2; calls++) {
            DeviceRig rig = DeviceStallTest.rig(DeviceStallTest.Source.REAL_1152_LAG1);
            rig.connect();
            rig.run(10_000);
            rig.sending = false;
            rig.run(3_100);
            rig.decoderForcedHoldCalls = calls;
            rig.sending = true;
            rig.run(8_000);
            assertTrue("held " + calls + " calls: " + rig.report(), Math.abs(rig.syncErrorMs()) < 3 && rig.status().aligned);
        }
    }

    /**
     * Stalls of almost 3 s: the timestamps spacing rule (3 s) does not see them, the stamp jump of the first chunk is the stall
     * plus the frames the encoder had collected (up to a block). Whatever takes it, no more than 3 s of silence is inserted
     * and the stream ends on its stamps.
     */
    @Test
    public void stallsOfAlmostThreeSecondsAreNeverFilledBeyondTheLimit() {
        for (long stall = 2_970; stall <= 3_000; stall += 3) {
            DeviceRig rig = DeviceStallTest.rig(DeviceStallTest.Source.REAL_1152);
            rig.connect();
            rig.run(10_000);
            rig.stallSource(stall);
            rig.run(8_000);
            EngineStatus s = rig.status();
            assertTrue("stall " + stall + ": silence inserted " + s.flacShiftFilledMs + " ms; " + rig.report(), s.flacShiftFilledMs <= 3_000);
            assertTrue("stall " + stall + ": " + rig.report(), Math.abs(rig.syncErrorMs()) < 3 && s.aligned);
        }
    }

    /**
     * After the engine went back to counting frames (headers that could not be read) a new codec header starts the stream
     * anew: the position source is looked at again, and with readable headers the guard works again.
     */
    @Test
    public void aNewCodecHeaderBringsTheFrameHeaderPositionsBackAfterAFallback() {
        DeviceRig rig = blocks();
        rig.flacHeaders = false; // raw samples first: three refused chunks, the session counts frames, the guard is off
        rig.connect();
        rig.run(8_000);
        assertEquals(2, rig.status().flacPositionSource);
        assertEquals(2, rig.status().flacGuardState);
        rig.flacHeaders = true; // the server restarted its encoder: frame headers, and a new codec header (another block size)
        rig.engine.onCodecHeader(ClientFixtures.flacHeaderMessage(rig.rate, rig.channels, rig.bits, 1152, 1152));
        rig.run(8_000);
        EngineStatus s = rig.status();
        assertEquals(rig.report(), 1, s.flacPositionSource);
        assertEquals("the guard is on again", 1, s.flacGuardState);
        rig.stallSource(240);
        rig.run(4_000);
        assertTrue("the guard fills the stall again: " + rig.report(), rig.status().flacShiftFills >= 1);
    }
}
