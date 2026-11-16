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

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DRAFT of the tap of task 4.1 (not wired into the player, not verified on a device): a Media3 audio processor that
 * hands everything ExoPlayer decodes to a {@link PcmSink} as 16 bit stereo at the stream's sample rate, and sends
 * either silence or the unchanged audio on to the speaker.
 *
 * <p>It sits at the end of the chain built by {@link FeedTapChain}: the processors in front of it convert the bit depth
 * ({@code ToInt16PcmAudioProcessor}) and the sample rate ({@code SonicAudioProcessor}); this one does not change the
 * rate (a different rate is refused with an {@link AudioProcessor.UnhandledAudioFormatException}) and folds the channels
 * to stereo with a {@link PcmFormatConverter}, which also converts any bit depth it is given.
 *
 * <p>With {@code playLocally == false} the output is zeros of the same length as the input: ExoPlayer's clock keeps
 * running at the speed of the audio track, which is what paces the decoder (and with it the sink), and the phone
 * stays silent. The output keeps the input format, so the processors behind this one in ExoPlayer's chain see nothing
 * unusual. A flush (seek, stop) calls {@link PcmSink#discard()}, so that queued audio of the old position is not heard.
 *
 * <p>{@link #queueInput} runs on the playback thread of ExoPlayer and must not block for long: the sink is expected to
 * wait a bounded time at most ({@link PcmFeeder#write} does). Data that the sink did not take is counted in
 * {@link #getDroppedBuffers()}; playback goes on.
 */
@OptIn(markerClass = UnstableApi.class)
public final class PcmTapAudioProcessor extends BaseAudioProcessor {

    private final PcmFormat target;
    private final PcmSink sink;
    private final boolean playLocally;

    private PcmFormatConverter converter;
    private byte[] inputBytes = new byte[0];
    private byte[] outputBytes = new byte[0];
    private byte[] zeros = new byte[0];
    private final AtomicLong droppedBuffers = new AtomicLong();

    /**
     * @param target      the format of the stream; only {@link PcmFormat#SNAPCAST_DEFAULT}-like 16 bit stereo is supported
     * @param sink        receives the converted PCM
     * @param playLocally true to pass the audio on to the speaker as well, false for silence there
     */
    public PcmTapAudioProcessor(PcmFormat target, PcmSink sink, boolean playLocally) {
        if (target == null || sink == null) {
            throw new IllegalArgumentException("missing argument");
        }
        if (target.getBitsPerSample() != 16 || target.getChannels() != PcmFormatConverter.OUTPUT_CHANNELS) {
            throw new IllegalArgumentException("the tap produces 16 bit stereo");
        }
        this.target = target;
        this.sink = sink;
        this.playLocally = playLocally;
    }

    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat) throws UnhandledAudioFormatException {
        PcmEncoding encoding = encodingOf(inputAudioFormat.encoding);
        if (encoding == null || inputAudioFormat.sampleRate != target.getSampleRate()
                || !PcmFormatConverter.isSupported(inputAudioFormat.channelCount)) {
            throw new UnhandledAudioFormatException(inputAudioFormat);
        }
        converter = new PcmFormatConverter(encoding, inputAudioFormat.channelCount);
        return inputAudioFormat;
    }

    private static PcmEncoding encodingOf(int encoding) {
        switch (encoding) {
            case C.ENCODING_PCM_8BIT:
                return PcmEncoding.UNSIGNED_8;
            case C.ENCODING_PCM_16BIT:
                return PcmEncoding.SIGNED_16;
            case C.ENCODING_PCM_24BIT:
                return PcmEncoding.SIGNED_24;
            case C.ENCODING_PCM_32BIT:
                return PcmEncoding.SIGNED_32;
            case C.ENCODING_PCM_FLOAT:
                return PcmEncoding.FLOAT_32;
            default:
                return null; // big endian, double and every compressed encoding
        }
    }

    @Override
    public void queueInput(ByteBuffer input) {
        final int count = input.remaining();
        if (count == 0) {
            return;
        }
        if (inputBytes.length < count) {
            inputBytes = new byte[count];
        }
        input.duplicate().get(inputBytes, 0, count); // a copy: the position of input is moved below
        final int wholeFrames = count - count % converter.inputFrameBytes();
        if (wholeFrames > 0) {
            int converted = converter.outputBytesFor(wholeFrames);
            if (outputBytes.length < converted) {
                outputBytes = new byte[converted];
            }
            converter.convert(inputBytes, 0, wholeFrames, outputBytes, 0);
            if (!sink.write(outputBytes, 0, converted)) {
                droppedBuffers.incrementAndGet();
            }
        }
        input.position(input.limit());

        ByteBuffer output = replaceOutputBuffer(count);
        if (playLocally) {
            output.put(inputBytes, 0, count);
        } else {
            if (zeros.length < count) {
                zeros = new byte[count];
            }
            output.put(zeros, 0, count);
        }
        output.flip();
    }

    @Override
    protected void onFlush(StreamMetadata streamMetadata) {
        sink.discard();
    }

    @Override
    protected void onReset() {
        converter = null;
        inputBytes = new byte[0];
        outputBytes = new byte[0];
        zeros = new byte[0];
    }

    /** How many buffers the sink did not take (it was full or closed) since this processor was created. */
    public long getDroppedBuffers() {
        return droppedBuffers.get();
    }
}
