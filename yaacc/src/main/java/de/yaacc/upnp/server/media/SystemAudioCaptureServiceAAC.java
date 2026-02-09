/*
 * Copyright (C) 2026 Tobias Schoene www.yaacc.de
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 */
package de.yaacc.upnp.server.media;

import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.os.Build;

import androidx.annotation.RequiresApi;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.ByteBuffer;

import de.yaacc.util.YaaccLogger;

@RequiresApi(api = Build.VERSION_CODES.Q)
public class SystemAudioCaptureServiceAAC {

    private static final int SAMPLE_RATE = 44100;
    private static final int BITRATE = 128000;

    private AudioRecord audioRecord;
    private MediaCodec audioEncoder;
    private Thread captureThread;
    private volatile boolean isCapturing = false;
    private PipedOutputStream outputStream;
    private PipedInputStream inputStream;

    public boolean startCapture(MediaProjection mediaProjection) {
        if (isCapturing) {
            YaaccLogger.w(getClass().getName(), "Already capturing");
            return false;
        }

        try {
            setupAudioCapture(mediaProjection);
            setupAudioEncoder();

            outputStream = new PipedOutputStream();
            inputStream = new PipedInputStream(outputStream, 256 * 1024);

            isCapturing = true;
            startCaptureThread();

            YaaccLogger.i(getClass().getName(), "AAC audio capture started");
            return true;
        } catch (Exception e) {
            YaaccLogger.e(getClass().getName(), "Failed to start AAC audio capture", e);
            stopCapture();
            return false;
        }
    }

    private void setupAudioCapture(MediaProjection mediaProjection) {
        AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
                .build();

        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT) * 4;

        audioRecord = new AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .build())
                .setBufferSizeInBytes(bufferSize)
                .build();

        audioRecord.startRecording();
    }

    private void setupAudioEncoder() throws IOException {
        MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);

        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        audioEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        audioEncoder.start();
    }

    private void startCaptureThread() {
        captureThread = new Thread(() -> {
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            byte[] buffer = new byte[4096];
            long audioTimestamp = 0;

            while (isCapturing) {
                try {
                    // Read PCM from AudioRecord
                    int read = audioRecord.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        int inputIndex = audioEncoder.dequeueInputBuffer(10000);
                        if (inputIndex >= 0) {
                            ByteBuffer inputBuffer = audioEncoder.getInputBuffer(inputIndex);
                            if (inputBuffer.remaining() >= read) {
                                inputBuffer.clear();
                                inputBuffer.put(buffer, 0, read);
                                audioEncoder.queueInputBuffer(inputIndex, 0, read, audioTimestamp, 0);
                                audioTimestamp += (read / 4) * 1000000L / SAMPLE_RATE;
                            }
                        }
                    }

                    // Get encoded AAC output
                    int outputIndex = audioEncoder.dequeueOutputBuffer(bufferInfo, 0);
                    if (outputIndex >= 0) {
                        ByteBuffer outputBuffer = audioEncoder.getOutputBuffer(outputIndex);
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            byte[] data = new byte[bufferInfo.size];
                            outputBuffer.position(bufferInfo.offset);
                            outputBuffer.get(data);

                            try {
                                outputStream.write(data);
                                outputStream.flush();
                            } catch (IOException e) {
                                // Client disconnected
                            }
                        }
                        audioEncoder.releaseOutputBuffer(outputIndex, false);
                    }
                } catch (Exception e) {
                    if (isCapturing) {
                        YaaccLogger.e(getClass().getName(), "Capture error", e);
                    }
                }
            }
        });
        captureThread.start();
    }

    public void stopCapture() {
        isCapturing = false;

        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (Exception e) {
            }
            try {
                audioRecord.release();
            } catch (Exception e) {
            }
            audioRecord = null;
        }

        if (audioEncoder != null) {
            try {
                audioEncoder.stop();
            } catch (Exception e) {
            }
            try {
                audioEncoder.release();
            } catch (Exception e) {
            }
            audioEncoder = null;
        }

        try {
            if (outputStream != null) outputStream.close();
        } catch (Exception e) {
        }

        YaaccLogger.i(getClass().getName(), "AAC audio capture stopped");
    }

    public boolean isCapturing() {
        return isCapturing;
    }

    public PipedInputStream getInputStream() throws IOException {
        if (inputStream == null) {
            throw new IOException("Audio capture not started");
        }
        return inputStream;
    }
}
