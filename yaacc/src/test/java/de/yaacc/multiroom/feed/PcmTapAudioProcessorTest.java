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
package de.yaacc.multiroom.feed;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.AudioProcessor.AudioFormat;
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * The draft of the Media3 tap (task 4.1) with synthetic buffers, as far as it runs on the JVM: the processor logic
 * and the chain of rate, bit depth and channel conversion in front of the sink. What needs a device (the audio sink
 * of ExoPlayer, gapless track changes, timing) is in "Snapcast feed tap (draft)" in docs/tech.md.
 */
public class PcmTapAudioProcessorTest {

    private static final PcmFormat TARGET = PcmFormat.SNAPCAST_DEFAULT;

    /** Collects what the tap hands over. */
    private static final class RecordingSink implements PcmSink {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int writes;
        int discards;
        boolean accept = true;

        @Override
        public boolean write(byte[] data, int offset, int length) {
            writes++;
            bytes.write(data, offset, length);
            return accept;
        }

        @Override
        public void discard() {
            discards++;
        }

        byte[] data() {
            return bytes.toByteArray();
        }
    }

    private static ByteBuffer direct(byte[] data) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(data.length).order(ByteOrder.nativeOrder());
        buffer.put(data);
        buffer.flip();
        return buffer;
    }

    private static byte[] s16(int... samples) {
        byte[] out = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            out[2 * i] = (byte) samples[i];
            out[2 * i + 1] = (byte) (samples[i] >> 8);
        }
        return out;
    }

    private static byte[] drain(AudioProcessor processor) {
        ByteBuffer output = processor.getOutput();
        byte[] bytes = new byte[output.remaining()];
        output.get(bytes);
        return bytes;
    }

    // ---- the processor

    @Test
    public void sixteenBitStereoAtTheStreamRateIsHandedOverAsItIsAndTheLocalOutputIsSilent() throws Exception {
        RecordingSink sink = new RecordingSink();
        PcmTapAudioProcessor tap = new PcmTapAudioProcessor(TARGET, sink, false);
        AudioFormat format = new AudioFormat(48000, 2, C.ENCODING_PCM_16BIT);

        assertEquals(format, tap.configure(format));
        tap.flush(AudioProcessor.StreamMetadata.DEFAULT);
        assertTrue(tap.isActive());
        byte[] input = s16(1, -1, 1000, -1000, 32767, -32768);
        ByteBuffer buffer = direct(input);
        tap.queueInput(buffer);

        assertArrayEquals(input, sink.data());
        assertEquals("the input is consumed", 0, buffer.remaining());
        assertArrayEquals("the local speaker gets silence of the same length", new byte[input.length], drain(tap));
    }

    @Test
    public void whenTheLocalOutputIsWantedTheAudioIsPassedOnUnchanged() throws Exception {
        RecordingSink sink = new RecordingSink();
        PcmTapAudioProcessor tap = new PcmTapAudioProcessor(TARGET, sink, true);
        tap.configure(new AudioFormat(48000, 2, C.ENCODING_PCM_16BIT));
        tap.flush(AudioProcessor.StreamMetadata.DEFAULT);
        byte[] input = s16(5, 6, 7, 8);

        tap.queueInput(direct(input));

        assertArrayEquals(input, sink.data());
        assertArrayEquals(input, drain(tap));
    }

    @Test
    public void aSixChannelFloatSourceIsFoldedToSixteenBitStereoForTheSinkButKeepsItsLocalFormat() throws Exception {
        RecordingSink sink = new RecordingSink();
        PcmTapAudioProcessor tap = new PcmTapAudioProcessor(TARGET, sink, false);
        AudioFormat format = new AudioFormat(48000, 6, C.ENCODING_PCM_FLOAT);
        assertEquals("the output keeps the input format", format, tap.configure(format));
        tap.flush(AudioProcessor.StreamMetadata.DEFAULT);
        ByteBuffer input = ByteBuffer.allocateDirect(2 * 6 * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int frame = 0; frame < 2; frame++) {
            input.putFloat(0.5f).putFloat(-0.5f).putFloat(0.25f).putFloat(0f).putFloat(0f).putFloat(0f);
        }
        input.flip();
        byte[] inputBytes = new byte[input.remaining()];
        input.duplicate().get(inputBytes);
        PcmFormatConverter reference = new PcmFormatConverter(PcmEncoding.FLOAT_32, 6);
        byte[] expected = new byte[reference.outputBytesFor(inputBytes.length)];
        reference.convert(inputBytes, 0, inputBytes.length, expected, 0);

        tap.queueInput(input);

        assertEquals("two frames of 16 bit stereo", 8, sink.data().length);
        assertArrayEquals(expected, sink.data());
        assertEquals("the local output keeps the length of the input", 2 * 6 * 4, drain(tap).length);
    }

    @Test
    public void aFormatThatCanNotBeTappedIsRefused() {
        PcmTapAudioProcessor tap = new PcmTapAudioProcessor(TARGET, new RecordingSink(), false);
        AudioFormat[] refused = {
                new AudioFormat(44100, 2, C.ENCODING_PCM_16BIT),              // the rate is converted in front of the tap
                new AudioFormat(48000, 2, C.ENCODING_PCM_16BIT_BIG_ENDIAN),
                new AudioFormat(48000, 2, C.ENCODING_PCM_FLOAT_BIG_ENDIAN),
                new AudioFormat(48000, 2, C.ENCODING_AC3),
                new AudioFormat(48000, 9, C.ENCODING_PCM_16BIT)};
        for (AudioFormat format : refused) {
            try {
                tap.configure(format);
                fail("must be refused: " + format);
            } catch (UnhandledAudioFormatException expected) {
                assertEquals(format, expected.inputAudioFormat);
            }
        }
    }

    @Test
    public void aFlushTellsTheSinkToDropWhatIsStale() throws Exception {
        RecordingSink sink = new RecordingSink();
        PcmTapAudioProcessor tap = new PcmTapAudioProcessor(TARGET, sink, false);
        tap.configure(new AudioFormat(48000, 2, C.ENCODING_PCM_16BIT));

        tap.flush(AudioProcessor.StreamMetadata.DEFAULT);
        tap.queueInput(direct(s16(1, 2)));
        tap.flush(AudioProcessor.StreamMetadata.DEFAULT);

        assertEquals(2, sink.discards);
    }

    @Test
    public void aSinkThatDropsDataIsCountedAndPlaybackGoesOn() throws Exception {
        RecordingSink sink = new RecordingSink();
        sink.accept = false;
        PcmTapAudioProcessor tap = new PcmTapAudioProcessor(TARGET, sink, false);
        tap.configure(new AudioFormat(48000, 2, C.ENCODING_PCM_16BIT));
        tap.flush(AudioProcessor.StreamMetadata.DEFAULT);

        tap.queueInput(direct(s16(1, 2, 3, 4)));
        tap.queueInput(direct(s16(1, 2, 3, 4)));

        assertEquals(2, tap.getDroppedBuffers());
        assertEquals(8, drain(tap).length);
    }

    @Test
    public void anEmptyBufferChangesNothing() throws Exception {
        RecordingSink sink = new RecordingSink();
        PcmTapAudioProcessor tap = new PcmTapAudioProcessor(TARGET, sink, false);
        tap.configure(new AudioFormat(48000, 2, C.ENCODING_PCM_16BIT));
        tap.flush(AudioProcessor.StreamMetadata.DEFAULT);

        tap.queueInput(direct(new byte[0]));

        assertEquals(0, sink.writes);
        assertEquals(0, drain(tap).length);
    }

    @Test
    public void endOfStreamEndsTheProcessorOnceTheOutputIsTaken() throws Exception {
        PcmTapAudioProcessor tap = new PcmTapAudioProcessor(TARGET, new RecordingSink(), false);
        tap.configure(new AudioFormat(48000, 2, C.ENCODING_PCM_16BIT));
        tap.flush(AudioProcessor.StreamMetadata.DEFAULT);
        tap.queueInput(direct(s16(1, 2)));

        tap.queueEndOfStream();
        assertFalse(tap.isEnded());
        drain(tap);

        assertTrue(tap.isEnded());
    }

    @Test
    public void aHalfFrameAtTheEndIsNotPassedToTheSink() throws Exception {
        RecordingSink sink = new RecordingSink();
        PcmTapAudioProcessor tap = new PcmTapAudioProcessor(TARGET, sink, false);
        tap.configure(new AudioFormat(48000, 2, C.ENCODING_PCM_16BIT));
        tap.flush(AudioProcessor.StreamMetadata.DEFAULT);

        tap.queueInput(direct(new byte[]{1, 0, 2, 0, 3, 0}));

        assertArrayEquals(new byte[]{1, 0, 2, 0}, sink.data());
        assertEquals("the local output is complete", 6, drain(tap).length);
    }

    @Test
    public void onlyTheStreamFormatIsAcceptedAsATarget() {
        try {
            new PcmTapAudioProcessor(new PcmFormat(48000, 24, 2), new RecordingSink(), false);
            fail("24 bit target");
        } catch (IllegalArgumentException expected) {
            // refused
        }
        try {
            new PcmTapAudioProcessor(new PcmFormat(48000, 16, 1), new RecordingSink(), false);
            fail("mono target");
        } catch (IllegalArgumentException expected) {
            // refused
        }
    }

    // ---- the chain: bit depth, rate, then the tap

    /** Pushes the buffers through the processors in order (configure, flush, queue, end of stream, drain). */
    private static void run(AudioProcessor[] chain, AudioFormat inputFormat, byte[] input, int bufferBytes)
            throws UnhandledAudioFormatException {
        AudioFormat format = inputFormat;
        for (AudioProcessor processor : chain) {
            AudioFormat output = processor.configure(format);
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT);
            if (processor.isActive()) {
                format = output; // an inactive processor leaves the format as it is
            }
        }
        for (int offset = 0; offset < input.length; offset += bufferBytes) {
            feed(chain, 0, direct(Arrays.copyOfRange(input, offset, Math.min(input.length, offset + bufferBytes))));
        }
        for (int i = 0; i < chain.length; i++) {
            if (!chain[i].isActive()) {
                continue;
            }
            chain[i].queueEndOfStream();
            ByteBuffer rest = chain[i].getOutput();
            if (rest.hasRemaining()) {
                feed(chain, i + 1, rest);
            }
        }
    }

    /** Queues the buffer into processor {@code index} (or passes it on if that one is inactive) and forwards its output. */
    private static void feed(AudioProcessor[] chain, int index, ByteBuffer buffer) {
        if (index == chain.length) {
            return;
        }
        AudioProcessor processor = chain[index];
        if (!processor.isActive()) {
            feed(chain, index + 1, buffer);
            return;
        }
        processor.queueInput(buffer);
        ByteBuffer output = processor.getOutput();
        if (output.hasRemaining()) {
            feed(chain, index + 1, output);
        }
    }

    @Test
    public void theChainAtTheStreamFormatPassesTheBytesThroughBitExact() throws Exception {
        RecordingSink sink = new RecordingSink();
        byte[] input = new byte[4 * 4800];
        new java.util.Random(11).nextBytes(input);

        run(FeedTapChain.create(TARGET, sink, false), new AudioFormat(48000, 2, C.ENCODING_PCM_16BIT), input, 4096);

        assertArrayEquals(input, sink.data());
    }

    @Test
    public void theChainConvertsTheSampleRateAndKeepsTheLength() throws Exception {
        RecordingSink sink = new RecordingSink();
        // one second of 44.1 kHz stereo
        byte[] input = new byte[44100 * 4];
        for (int i = 0; i < 44100; i++) {
            int sample = (int) (Math.sin(i * 2 * Math.PI * 440 / 44100) * 8000);
            input[4 * i] = (byte) sample;
            input[4 * i + 1] = (byte) (sample >> 8);
            input[4 * i + 2] = (byte) sample;
            input[4 * i + 3] = (byte) (sample >> 8);
        }

        run(FeedTapChain.create(TARGET, sink, false), new AudioFormat(44100, 2, C.ENCODING_PCM_16BIT), input, 8820);

        int frames = sink.data().length / 4;
        assertEquals(0, sink.data().length % 4);
        assertTrue("about one second at 48 kHz, got " + frames + " frames", frames > 47_000 && frames <= 48_100);
        boolean audible = false;
        byte[] data = sink.data();
        for (int i = 0; i < data.length - 1; i += 2) {
            if (Math.abs((short) ((data[i] & 0xFF) | (data[i + 1] << 8))) > 4000) {
                audible = true;
            }
        }
        assertTrue("the tone survived the rate conversion", audible);
    }

    @Test
    public void theChainTurns24BitAndSurroundInto16BitStereo() throws Exception {
        RecordingSink sink = new RecordingSink();
        // 6 channels of 24 bit at 48 kHz: front left at a quarter scale, nothing else
        int frames = 4800;
        byte[] input = new byte[frames * 6 * 3];
        for (int f = 0; f < frames; f++) {
            int value = 0x200000; // 0.25 of full scale
            input[f * 18] = (byte) value;
            input[f * 18 + 1] = (byte) (value >> 8);
            input[f * 18 + 2] = (byte) (value >> 16);
        }

        run(FeedTapChain.create(TARGET, sink, false), new AudioFormat(48000, 6, C.ENCODING_PCM_24BIT), input, 18 * 480);

        byte[] data = sink.data();
        assertEquals(frames * 4, data.length);
        int left = (short) ((data[0] & 0xFF) | (data[1] << 8));
        int right = (short) ((data[2] & 0xFF) | (data[3] << 8));
        double expected = 0.25 * 32768 / (1 + 2 * Math.sqrt(0.5));
        assertEquals(expected, left, 1.0);
        assertEquals(0, right);
    }

    @Test
    public void theChainHasTheThreeStepsInTheRightOrder() {
        AudioProcessor[] chain = FeedTapChain.create(TARGET, new RecordingSink(), false);

        assertEquals(3, chain.length);
        assertEquals("androidx.media3.common.audio.ToInt16PcmAudioProcessor", chain[0].getClass().getName());
        assertEquals("androidx.media3.common.audio.SonicAudioProcessor", chain[1].getClass().getName());
        assertEquals(PcmTapAudioProcessor.class, chain[2].getClass());
    }
}
