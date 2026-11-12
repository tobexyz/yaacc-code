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

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecList;
import android.media.MediaFormat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import de.yaacc.multiroom.protocol.CodecHeaderMessage;

/**
 * FLAC through the platform's {@code audio/flac} decoder ({@link MediaCodec}, synchronous mode, no new dependency).
 * The codec-specific data is the header of the stream as the server sent it ("fLaC" and the STREAMINFO block, optionally
 * more metadata blocks), which is what the platform documents for this codec. Every call is bounded by short timeouts
 * so the engine thread can not wait for ever; the number of bytes taken out per chunk is limited from the validated
 * rate and channels (not from the FLAC block sizes). Thin glue, not unit tested.
 *
 * <p><b>Guesses that need a device:</b> that a chunk of the server holds whole FLAC frames (the encoder's write callback
 * delivers whole frames), that the decoder gives 16 bit PCM (a float output is converted; anything else is refused), that
 * it does not hold samples back for long, and that a decoder made from the header works the same on every device.
 */
final class MediaCodecFlacDecoder implements FrameDecoder {

    private static final String MIME = "audio/flac";
    private static final long INPUT_TIMEOUT_US = 20_000L;
    private static final long OUTPUT_TIMEOUT_US = 5_000L;
    private static final int MAX_INPUT_BYTES = 512 * 1024;

    private final byte[] header;
    private final int sampleRate;
    private final int channels;
    private final int maxFrames;
    private MediaCodec codec;
    private boolean floatOutput;

    private MediaCodecFlacDecoder(byte[] header, int sampleRate, int channels, int maxFrames) {
        this.header = header;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.maxFrames = maxFrames;
    }

    static MediaCodecFlacDecoder create(CodecHeaderMessage message, int maxFrames) throws DecoderException {
        MediaCodecFlacDecoder decoder = new MediaCodecFlacDecoder(message.getPayload(), message.getSampleRate(),
                message.getChannels(), maxFrames);
        decoder.start();
        return decoder;
    }

    private MediaFormat format() {
        MediaFormat format = MediaFormat.createAudioFormat(MIME, sampleRate, channels);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(header));
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES);
        return format;
    }

    private void start() throws DecoderException {
        MediaFormat format = format();
        String name = new MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format);
        if (name == null) {
            throw new DecoderException(DecoderException.Reason.UNAVAILABLE, "no flac decoder on this device");
        }
        try {
            codec = MediaCodec.createByCodecName(name);
            codec.configure(format, null, null, 0);
            codec.start();
        } catch (IOException | RuntimeException e) {
            closeQuietly();
            throw new DecoderException(DecoderException.Reason.BAD_HEADER, "the flac decoder could not be started");
        }
        floatOutput = false;
    }

    @Override
    public byte[] decode(byte[] payload) throws DecoderException {
        if (codec == null) {
            throw new DecoderException(DecoderException.Reason.DECODE_FAILED, "decoder is closed");
        }
        if (payload.length > MAX_INPUT_BYTES) {
            throw new DecoderException(DecoderException.Reason.OVERSIZED, "chunk larger than the decoder takes");
        }
        try {
            int index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US);
            if (index < 0) {
                throw new DecoderException(DecoderException.Reason.DECODE_FAILED, "decoder busy");
            }
            ByteBuffer input = codec.getInputBuffer(index);
            if (input == null || input.capacity() < payload.length) {
                codec.queueInputBuffer(index, 0, 0, 0, 0); // give the buffer back
                throw new DecoderException(DecoderException.Reason.OVERSIZED, "chunk larger than the decoder buffer");
            }
            input.clear();
            input.put(payload);
            codec.queueInputBuffer(index, 0, payload.length, 0, 0);
            return drain();
        } catch (IllegalStateException e) { // MediaCodec.CodecException is one
            throw new DecoderException(DecoderException.Reason.DECODE_FAILED, "the flac decoder failed");
        }
    }

    private byte[] drain() throws DecoderException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long limit = (long) maxFrames * channels * 4; // the most a chunk may give (float samples)
        boolean tooMuch = false;
        while (true) {
            int index = codec.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US);
            if (index >= 0) {
                ByteBuffer buffer = codec.getOutputBuffer(index);
                if (buffer != null && info.size > 0 && !tooMuch) {
                    if (out.size() + (long) info.size > limit) {
                        tooMuch = true;
                    } else {
                        byte[] piece = new byte[info.size];
                        buffer.position(info.offset);
                        buffer.get(piece);
                        out.write(piece, 0, piece.length);
                    }
                }
                codec.releaseOutputBuffer(index, false);
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                checkOutputFormat(codec.getOutputFormat());
            } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            }
            // INFO_OUTPUT_BUFFERS_CHANGED (deprecated) and others: look again
        }
        if (tooMuch) {
            throw new DecoderException(DecoderException.Reason.OVERSIZED, "chunk decodes to more than allowed");
        }
        byte[] pcm = out.toByteArray();
        if (floatOutput) {
            return PcmConverter.toPcm16(pcm, 0, pcm.length / 4, PcmConverter.Encoding.FLOAT32);
        }
        return pcm;
    }

    private void checkOutputFormat(MediaFormat format) throws DecoderException {
        int encoding = format.containsKey(MediaFormat.KEY_PCM_ENCODING) ? format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                : AudioFormat.ENCODING_PCM_16BIT;
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            floatOutput = true;
        } else if (encoding == AudioFormat.ENCODING_PCM_16BIT) {
            floatOutput = false;
        } else {
            throw new DecoderException(DecoderException.Reason.UNSUPPORTED_OUTPUT, "the decoder gives an unknown sample format");
        }
        int rate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : sampleRate;
        int ch = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : channels;
        if (rate != sampleRate || ch != channels) {
            throw new DecoderException(DecoderException.Reason.UNSUPPORTED_OUTPUT, "the decoder output differs from the header");
        }
    }

    @Override
    public void reset() {
        // flush() would leave the state of a synchronous codec to the platform's version; a fresh decoder is certain
        closeQuietly();
        try {
            start();
        } catch (DecoderException e) {
            codec = null; // the next decode() reports it
        }
    }

    @Override
    public long droppedPartialBytes() {
        return 0;
    }

    @Override
    public void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        MediaCodec old = codec;
        codec = null;
        if (old != null) {
            try {
                old.stop();
            } catch (RuntimeException ignored) {
                // released next
            }
            try {
                old.release();
            } catch (RuntimeException ignored) {
                // nothing more to do
            }
        }
    }
}
