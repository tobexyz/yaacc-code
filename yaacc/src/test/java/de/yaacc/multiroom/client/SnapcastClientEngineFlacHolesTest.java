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

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * Several holes in a flac stream (review W4 of Group 8): every lost chunk must be found, however close the next one is,
 * because a hole that is not found leaves the stream one chunk early for good. The flac path has no frame counting that
 * could see a hole, so the engine remembers the position of every hole until the chunk after it is processed (up to
 * {@code bufferMs} later).
 */
public class SnapcastClientEngineFlacHolesTest {

    private static EngineRig flac() {
        EngineRig rig = EngineRig.flac48();
        rig.connect();
        return rig;
    }

    /** The drift of the last audible frame against the time it should be presented: what a shifted stream shows. */
    private static long lastFrameDrift(EngineRig rig) {
        ClientFixtures.Trace trace = rig.trace();
        int last = trace.size() - 1;
        return rig.sink.presentationTimeOf(trace.sinkFrame(last)) - rig.targetOf(trace.mediaFrame(last));
    }

    private static void finish(EngineRig rig) {
        rig.sending = false;
        rig.run(5000); // everything that waited in the scheduler is processed
    }

    private static void assertAligned(String what, EngineRig rig) {
        ClientFixtures.Trace trace = rig.trace();
        long lastMedia = trace.mediaFrame(trace.size() - 1);
        long lastChunkEnd = rig.nextChunk * 960L;
        // a stream that is shifted by seconds is skipped as late and plays nothing at the end: its last frame is old
        assertTrue(what + ": the end of the stream was played (last media frame " + lastMedia + " of " + lastChunkEnd + ")",
                lastMedia >= lastChunkEnd - 3 * 960L);
        long drift = lastFrameDrift(rig);
        assertTrue(what + ": the last frame is " + drift + " us from its time", Math.abs(drift) <= 1500);
        assertTrue(what + ": the status agrees (the engine's own drift " + rig.engine.status().driftMicros + ")",
                Math.abs(rig.engine.status().driftMicros) <= 3000);
    }

    @Test
    public void twoLossesFiveChunksApartAreBothFound() {
        EngineRig rig = flac();
        rig.lose(500, 505);
        rig.run(12_000);
        finish(rig);
        assertEquals(2, rig.engine.status().streamHolesRecovered);
        assertAligned("two losses", rig);
    }

    @Test
    public void twoLossesOneChunkApartAreBothFound() {
        EngineRig rig = flac();
        rig.lose(500, 502);
        rig.run(12_000);
        finish(rig);
        assertEquals(2, rig.engine.status().streamHolesRecovered);
        assertAligned("losses 500 and 502", rig);
    }

    @Test
    public void adjacentLossesAreOneHole() {
        EngineRig rig = flac();
        rig.lose(500, 501, 502);
        rig.run(12_000);
        finish(rig);
        assertEquals(1, rig.engine.status().streamHolesRecovered);
        assertAligned("adjacent losses", rig);
    }

    @Test
    public void aDecodeFailureAndALossRightAfterItAreBothFound() {
        final int[] calls = {0};
        DecoderFactory flaky = (h, m) -> new SnapcastClientEngineTest.PassThroughDecoder() {
            @Override
            public byte[] decode(byte[] payload) throws DecoderException {
                if (++calls[0] == 500) { // chunk 499
                    throw new DecoderException(DecoderException.Reason.DECODE_FAILED, "bad");
                }
                return super.decode(payload);
            }
        };
        EngineRig rig = new EngineRig(SimulatedSink::typical, new ClientEngineConfig(), flaky, 48000, 2, 16);
        rig.flac = true;
        rig.connect();
        rig.lose(502);
        rig.run(12_000);
        finish(rig);
        EngineStatus s = rig.engine.status();
        assertEquals(1, s.decodeFailures);
        assertEquals(2, s.streamHolesRecovered);
        assertAligned("decode failure and loss", rig);
    }

    @Test
    public void aLateChunkAndALostChunkNearEachOtherAreBothFound() {
        EngineRig rig = flac();
        rig.late(500);
        rig.lose(504);
        rig.run(12_000);
        finish(rig);
        assertEquals(2, rig.engine.status().streamHolesRecovered);
        assertAligned("late and lost", rig);
    }

    @Test
    public void lossesMoreThanTheSchedulerWindowApartStillWork() {
        EngineRig rig = flac();
        rig.lose(500, 560, 700);
        rig.run(18_000);
        finish(rig);
        assertEquals(3, rig.engine.status().streamHolesRecovered);
        assertAligned("far apart", rig);
    }

    private void everyNth(int every) {
        EngineRig rig = flac();
        Set<Long> lostSet = new HashSet<>();
        for (long chunk = 300; chunk < 1700; chunk += every) {
            lostSet.add(chunk);
            rig.lose(chunk);
        }
        while (rig.nextChunk < 1800) {
            rig.step();
        }
        long last = rig.nextChunk;
        finish(rig);
        long holes = 0;
        for (long chunk : lostSet) {
            if (!lostSet.contains(chunk + 1) && chunk + 1 < last) {
                holes++; // a run of lost chunks followed by one that arrived
            }
        }
        assertEquals("every " + every + ". chunk lost", holes, rig.engine.status().streamHolesRecovered);
        assertAligned("every " + every + ". chunk lost", rig);
        assertTrue(rig.engine.status().failure == null);
    }

    @Test
    public void everyTwentyFifthChunkLost() {
        everyNth(25);
    }

    @Test
    public void everyFifthChunkLost() {
        everyNth(5);
    }

    @Test
    public void everyThirdChunkLost() {
        everyNth(3);
    }

    @Test
    public void everySecondChunkLost() {
        everyNth(2);
    }

    @Test
    public void moreHolesThanCanBeRememberedAreStillAllPlacedByTheirTimestamps() {
        EngineRig rig = flac();
        rig.bufferMs = 8000; // the scheduler holds about 390 chunks: far more holes than the 64 that are remembered one by one
        rig.engine.onServerSettings(new de.yaacc.multiroom.protocol.ServerSettingsMessage(8000, 0, false, 100));
        for (long chunk = 200; chunk < 600; chunk += 2) {
            rig.lose(chunk);
        }
        while (rig.nextChunk < 1500) {
            rig.step();
        }
        finish(rig);
        rig.run(10_000);
        EngineStatus s = rig.engine.status();
        assertTrue("more than 64 holes were pending at once: " + s.holeOverflows, s.holeOverflows > 0);
        assertTrue(s.failure == null);
        assertEquals("every one of the 200 holes was placed by its timestamp, remembered or not", 200, s.streamHolesRecovered);
        assertAligned("overflow", rig);
    }

    @Test
    public void aHoleOfTheOldStreamMeansNothingAfterTheServerRestartedWithAnEarlierClock() {
        EngineRig rig = flac();
        rig.lose(500);
        rig.run(10_300); // the loss is known, the chunk after it has not been processed yet
        rig.clearLosses();
        // a new epoch whose timestamps start 3 s below the old hole and cross it after 3 s
        rig.restartServer(rig.chunkTimestamp(499) - 3_000_000L - rig.clock.nowMicros());
        rig.run(25_000);
        assertEquals("the old hole must not fire against the new stream", 0, rig.engine.status().streamHolesRecovered);
        assertEquals(1, rig.engine.status().epochChanges);
    }

    @Test
    public void aHoleOfTheOldStreamMeansNothingAfterAConnectionLossAndANewHeader() {
        EngineRig rig = flac();
        rig.lose(500);
        rig.run(10_300);
        rig.engine.onConnectionLost();
        rig.engine.onServerSettings(new de.yaacc.multiroom.protocol.ServerSettingsMessage(1000, 0, false, 100));
        rig.engine.onCodecHeader(rig.header());
        // the stream starts over with timestamps below the old hole, without a new epoch (a hostile or odd source)
        rig.clearLosses();
        rig.restartServer(rig.chunkTimestamp(499) - 3_000_000L - rig.clock.nowMicros());
        rig.offsets.epoch--; // the filter reports no new epoch: only the loss and the new header tell the engine
        rig.run(25_000);
        assertEquals(0, rig.engine.status().streamHolesRecovered);
    }

    @Test
    public void aHoleOfTheOldStreamMeansNothingAfterANewCodecHeader() {
        EngineRig rig = flac();
        rig.lose(500);
        rig.run(10_300);
        rig.engine.onCodecHeader(ClientFixtures.flacHeaderMessage(48000, 2, 24)); // another stream on the same connection
        rig.clearLosses();
        rig.restartServer(rig.chunkTimestamp(499) - 3_000_000L - rig.clock.nowMicros());
        rig.offsets.epoch--;
        rig.run(25_000);
        assertEquals(0, rig.engine.status().streamHolesRecovered);
    }

    @Test
    public void pcmIsUnchangedByAllThis() {
        EngineRig rig = EngineRig.typical();
        rig.connect();
        rig.lose(500, 505);
        rig.run(12_000);
        finish(rig);
        EngineStatus s = rig.engine.status();
        assertEquals(0, s.streamHolesRecovered);
        assertEquals(2, s.gapsFilled);
        assertAligned("pcm", rig);
    }
}
