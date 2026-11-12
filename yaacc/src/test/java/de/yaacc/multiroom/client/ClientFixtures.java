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

import de.yaacc.multiroom.protocol.Codec;
import de.yaacc.multiroom.protocol.CodecHeaderMessage;
import de.yaacc.multiroom.protocol.OffsetSource;
import de.yaacc.multiroom.protocol.WireChunkMessage;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Headers, chunks with a marker in every frame, and a reader for the markers in what the simulated sink received. */
final class ClientFixtures {

    private ClientFixtures() {
    }

    static byte[] wav(int rate, int channels, int bits) {
        ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) channels).putInt(rate)
                .putInt(rate * channels * bits / 8).putShort((short) (channels * bits / 8)).putShort((short) bits);
        b.put("data".getBytes()).putInt(0);
        return b.array();
    }

    static CodecHeaderMessage pcmHeader(int rate, int channels, int bits) {
        return CodecHeaderMessage.of(Codec.PCM, wav(rate, channels, bits));
    }

    static byte[] flacHeader(int rate, int channels, int bits, int minBlock, int maxBlock) {
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 34);
        b.put("fLaC".getBytes());
        b.put((byte) 0x80).put((byte) 0).put((byte) 0).put((byte) 34);
        b.putShort((short) minBlock).putShort((short) maxBlock);
        b.put(new byte[6]); // min/max frame size
        long packed = ((long) rate << 44) | ((long) (channels - 1) << 41) | ((long) (bits - 1) << 36);
        b.putLong(packed);
        b.put(new byte[16]); // md5
        return b.array();
    }

    static CodecHeaderMessage flacHeaderMessage(int rate, int channels, int bits) {
        return CodecHeaderMessage.of(Codec.FLAC, flacHeader(rate, channels, bits, 4608, 4608));
    }

    /**
     * A pcm chunk whose frames carry the media frame number: the left sample is {@code frame % 20000 + 1}, the right
     * sample {@code frame / 20000 + 1} (never 0, so silence can be told from audio). Mono has the left sample only.
     * 24 and 32 bit samples carry the same values in their top 16 bits.
     */
    static WireChunkMessage chunk(long serverMicros, long firstMediaFrame, int frames, int channels, int bits) {
        int bytes = bits / 8;
        ByteBuffer b = ByteBuffer.allocate(frames * channels * bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames; i++) {
            long f = firstMediaFrame + i;
            for (int c = 0; c < channels; c++) {
                int marker = c == 0 ? (int) (f % 20000) + 1 : (int) (f / 20000) % 30000 + 1;
                switch (bits) {
                    case 16:
                        b.putShort((short) marker);
                        break;
                    case 24:
                        b.put((byte) 0).put((byte) marker).put((byte) (marker >> 8));
                        break;
                    default:
                        b.putShort((short) 0).putShort((short) marker);
                        break;
                }
            }
        }
        return new WireChunkMessage((int) Math.floorDiv(serverMicros, 1_000_000L), (int) Math.floorMod(serverMicros, 1_000_000L),
                b.array());
    }

    static WireChunkMessage rawChunk(long serverMicros, byte[] payload) {
        return new WireChunkMessage((int) Math.floorDiv(serverMicros, 1_000_000L), (int) Math.floorMod(serverMicros, 1_000_000L),
                payload);
    }

    /** What the sink received in one generation (between two flushes) as a list of (sink frame, media frame) pairs. */
    static final class Trace {
        final List<long[]> audible = new ArrayList<>();
        final long totalFrames;

        Trace(SimulatedSink sink, int generation) {
            long total = 0;
            int channels = sink.format.getChannels();
            for (SimulatedSink.Write write : sink.writes) {
                if (write.generation != generation) {
                    continue;
                }
                ByteBuffer b = ByteBuffer.wrap(write.data).order(ByteOrder.LITTLE_ENDIAN);
                int frames = write.data.length / (channels * 2);
                for (int i = 0; i < frames; i++) {
                    int left = b.getShort((i * channels) * 2);
                    int high = channels > 1 ? b.getShort((i * channels + 1) * 2) : 1;
                    if (left != 0) {
                        long media = (long) (high - 1) * 20000L + (left - 1);
                        audible.add(new long[]{write.sinkFrameStart + i, media});
                    }
                }
                total = Math.max(total, write.sinkFrameStart + frames);
            }
            totalFrames = total;
        }

        int size() {
            return audible.size();
        }

        long sinkFrame(int index) {
            return audible.get(index)[0];
        }

        long mediaFrame(int index) {
            return audible.get(index)[1];
        }
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }

    /** The offset source of the tests: whatever the test sets. */
    static final class FakeOffsets implements OffsetSource {
        volatile boolean synced = true;
        volatile long offset;
        volatile int epoch;

        FakeOffsets(long offset) {
            this.offset = offset;
        }

        @Override
        public boolean isSynchronized() {
            return synced;
        }

        @Override
        public long offsetMicros() {
            return offset;
        }

        @Override
        public int epoch() {
            return epoch;
        }
    }
}
