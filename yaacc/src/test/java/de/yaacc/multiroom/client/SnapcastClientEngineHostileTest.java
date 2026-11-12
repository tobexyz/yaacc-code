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

import de.yaacc.multiroom.protocol.CodecHeaderMessage;
import de.yaacc.multiroom.protocol.ServerSettingsMessage;

import org.junit.Test;

import java.util.Random;

/**
 * A server that does everything wrong, in random order: the engine must not throw, must keep its bounds and must end
 * in a state that is one of its own. The sequences are seeded, so a failure repeats.
 */
public class SnapcastClientEngineHostileTest {

    private static CodecHeaderMessage[] headers() {
        return new CodecHeaderMessage[]{
                ClientFixtures.pcmHeader(48000, 2, 16), ClientFixtures.pcmHeader(44100, 2, 24), ClientFixtures.pcmHeader(8000, 1, 32),
                ClientFixtures.pcmHeader(192000, 2, 16), ClientFixtures.pcmHeader(48000, 6, 16),
                ClientFixtures.flacHeaderMessage(48000, 2, 16)};
    }

    private static void oneSequence(long seed) {
        Random random = new Random(seed);
        EngineRig rig = EngineRig.typical();
        CodecHeaderMessage[] headers = headers();
        for (int i = 0; i < 400; i++) {
            switch (random.nextInt(12)) {
                case 0:
                    rig.engine.onCodecHeader(headers[random.nextInt(headers.length)]);
                    break;
                case 1:
                    rig.engine.onServerSettings(new ServerSettingsMessage(random.nextInt(), random.nextInt(), random.nextBoolean(),
                            random.nextInt()));
                    break;
                case 2:
                    rig.offsets.epoch++;
                    rig.offsets.offset += random.nextInt(2_000_000) - 1_000_000;
                    break;
                case 3:
                    rig.engine.onConnectionLost();
                    break;
                case 4:
                    rig.offsets.synced = random.nextBoolean();
                    break;
                case 5:
                    rig.engine.setManualLatencyMs(random.nextInt());
                    break;
                case 6: {
                    byte[] payload = new byte[random.nextInt(8000)];
                    random.nextBytes(payload);
                    long ts = random.nextBoolean() ? rig.serverNow() + random.nextInt(2_000_000) - 500_000 : random.nextLong();
                    ts = Math.max(-2_147_483_648L * 1_000_000L, Math.min(2_147_483_647L * 1_000_000L, ts));
                    rig.engine.onChunk(ClientFixtures.rawChunk(ts, payload));
                    break;
                }
                case 7:
                    rig.sink.forceUnderrun();
                    break;
                case 8:
                    rig.engine.onOffsetUpdate(random.nextLong(), random.nextLong());
                    break;
                default:
                    rig.engine.onCodecHeader(headers[0]);
                    rig.run(random.nextInt(60));
                    break;
            }
            EngineStatus s = rig.engine.status();
            assertTrue("seed " + seed, s.decodedWaitingMs <= 3000 + 100);
            assertTrue("seed " + seed, s.chunksWaiting <= 512);
            assertTrue("seed " + seed, rig.sink.framesWritten() >= 0);
            assertTrue("seed " + seed, s.state != null);
            assertTrue("seed " + seed, s.failure == null || s.state == EngineState.FAILED);
        }
        rig.engine.stop();
        assertEquals("the output is freed at the end, seed " + seed, rig.sink.opens, rig.sink.releases);
    }

    @Test
    public void twoHundredRandomHostileSequencesNeverThrowAndKeepTheirBounds() {
        for (long seed = 1; seed <= 200; seed++) {
            oneSequence(seed);
        }
    }

    @Test
    public void aThousandHeadersASecondNeverReopensTheOutputMoreThanTheLimitAllows() {
        EngineRig rig = EngineRig.typical();
        rig.connect();
        CodecHeaderMessage[] headers = {ClientFixtures.pcmHeader(48000, 2, 16), ClientFixtures.pcmHeader(44100, 2, 16)};
        for (int i = 0; i < 1000; i++) {
            rig.engine.onCodecHeader(headers[i % 2]);
            rig.clock.advanceMicros(1000);
        }
        assertTrue("opens " + rig.sink.opens, rig.sink.opens <= 14);
        assertEquals(EngineFailure.HEADER_FLOOD, rig.engine.status().failure);
    }

    @Test
    public void chunksWithATimestampThatMovesEveryChunkAreDroppedNotPlayed() {
        EngineRig rig = EngineRig.typical();
        rig.connect();
        rig.sending = false;
        Random random = new Random(7);
        for (int i = 0; i < 2000; i++) {
            rig.engine.onChunk(ClientFixtures.chunk(rig.serverNow() + random.nextInt(4_000_000) - 2_000_000, 0, 960, 2, 16));
            if (i % 10 == 0) {
                rig.run(10);
            }
        }
        rig.run(3000);
        EngineStatus s = rig.engine.status();
        assertTrue(s.state != EngineState.FAILED);
        assertTrue(s.droppedFifoFull + s.droppedLate + s.droppedQueueFull + s.droppedTooFarAhead + s.droppedOverlap > 0);
        assertTrue("the decoded audio waiting stays bounded: " + s.decodedWaitingMs, s.decodedWaitingMs <= 3100);
    }
}
