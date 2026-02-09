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
package de.yaacc.upnp.server.media;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

import de.yaacc.util.YaaccLogger;

/**
 * Combined video+audio capture using MediaCodec and MediaMuxer
 * Creates MP4 segments with H.264 video and AAC audio
 *
 * @author tobexyz
 */
@androidx.annotation.RequiresApi(api = android.os.Build.VERSION_CODES.Q)
public class CombinedCaptureService {

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int VIDEO_FPS = 30;
    private static final int VIDEO_BITRATE = 2000000;
    private static final int AUDIO_SAMPLE_RATE = 44100;
    private static final int AUDIO_BITRATE = 128000;

    private final Context context;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private MediaCodec videoEncoder;
    private MediaCodec audioEncoder;
    private AudioRecord audioRecord;
    private volatile boolean isCapturing = false;
    private Thread videoThread;
    private Thread audioThread;

    private FragmentedMp4Muxer muxer;
    private boolean muxerStarted = false;
    private final Object muxerLock = new Object();

    private MediaFormat videoFormat;
    private MediaFormat audioFormat;
    private File outputFile;
    private int audioSampleCount = 0;
    private ByteBuffer spsBuffer;
    private ByteBuffer ppsBuffer;
    private byte[] audioConfigData;

    public CombinedCaptureService(Context context) {
        this.context = context;
    }

    public void startCapture(MediaProjection projection) throws IOException {
        if (isCapturing) return;

        this.mediaProjection = projection;
        isCapturing = true;

        setupVideoEncoder();
        setupAudioEncoder();
        setupAudioCapture();

        outputFile = new File(context.getCacheDir(), "combined_stream.ts");
        if (outputFile.exists()) {
            outputFile.delete();
        }

        videoThread = new Thread(this::encodeVideo);
        audioThread = new Thread(this::encodeAudio);

        videoThread.start();
        audioThread.start();

        // Wait for formats
        int retries = 50;
        while ((videoFormat == null || audioFormat == null) && retries-- > 0) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                break;
            }
        }

        if (videoFormat == null || audioFormat == null) {
            YaaccLogger.e(getClass().getName(), "Failed to get encoder formats");
            throw new IOException("Encoder formats not available");
        }

        try {
            synchronized (muxerLock) {
                muxer = new FragmentedMp4Muxer(outputFile);
                if (audioConfigData != null) {
                    muxer.setAudioConfig(audioConfigData);
                }
                muxer.start();
                muxerStarted = true;
                YaaccLogger.i(getClass().getName(), "FragmentedMp4Muxer started");
            }
        } catch (Exception e) {
            YaaccLogger.e(getClass().getName(), "Failed to start muxer", e);
            throw new IOException("Muxer failed", e);
        }

        YaaccLogger.i(getClass().getName(), "Combined capture started with MPEG-TS streaming");
    }

    private void setupVideoEncoder() throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FPS);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface surface = videoEncoder.createInputSurface();
        videoEncoder.start();

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "YAACC-Combined",
                WIDTH, HEIGHT, context.getResources().getDisplayMetrics().densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
                surface, null, null);
    }

    private void setupAudioEncoder() throws IOException {
        MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, 2);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE);

        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        audioEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        audioEncoder.start();
    }

    private void setupAudioCapture() {
        AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build();

        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(AUDIO_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build();

        int bufferSize = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT) * 4;

        audioRecord = new AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(config)
                .build();

        audioRecord.startRecording();
    }

    private void encodeVideo() {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        int frameCount = 0;

        while (isCapturing) {
            try {
                int outputIndex = videoEncoder.dequeueOutputBuffer(bufferInfo, 10000);
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    videoFormat = videoEncoder.getOutputFormat();
                    spsBuffer = videoFormat.getByteBuffer("csd-0");
                    ppsBuffer = videoFormat.getByteBuffer("csd-1");
                    YaaccLogger.i(getClass().getName(), "Video format changed, SPS/PPS extracted");
                } else if (outputIndex >= 0) {
                    ByteBuffer outputBuffer = videoEncoder.getOutputBuffer(outputIndex);
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        synchronized (muxerLock) {
                            if (muxerStarted) {
                                boolean keyFrame = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;

                                // Prepend SPS/PPS to keyframes
                                if (keyFrame && spsBuffer != null && ppsBuffer != null) {
                                    outputBuffer.position(bufferInfo.offset);
                                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size);

                                    int spsSize = spsBuffer.limit();
                                    int ppsSize = ppsBuffer.limit();
                                    int frameSize = bufferInfo.size;
                                    int totalSize = spsSize + ppsSize + frameSize;

                                    ByteBuffer combined = ByteBuffer.allocate(totalSize);
                                    spsBuffer.position(0);
                                    ppsBuffer.position(0);
                                    combined.put(spsBuffer);
                                    combined.put(ppsBuffer);
                                    combined.put(outputBuffer);
                                    combined.flip();
                                    muxer.writeVideoSample(combined, bufferInfo.presentationTimeUs, keyFrame);
                                } else {
                                    outputBuffer.position(bufferInfo.offset);
                                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                                    muxer.writeVideoSample(outputBuffer, bufferInfo.presentationTimeUs, keyFrame);
                                }

                                if (++frameCount % 30 == 0) {
                                    YaaccLogger.i(getClass().getName(), "Video frames written: " + frameCount + ", size: " + bufferInfo.size + ", keyframe: " + keyFrame);
                                }
                                if (keyFrame) {
                                    YaaccLogger.i(getClass().getName(), "Keyframe at frame " + frameCount);
                                }
                            }
                        }
                    }
                    videoEncoder.releaseOutputBuffer(outputIndex, false);
                }
            } catch (Exception e) {
                if (isCapturing) {
                    YaaccLogger.e(getClass().getName(), "Video encoding error", e);
                }
            }
        }
    }

    private void encodeAudio() {
        byte[] buffer = new byte[960 * 2 * 2];
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        long audioTimestamp = 0;

        while (isCapturing) {
            try {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 0) {
                    if (audioSampleCount++ % 100 == 0) {
                        YaaccLogger.i(getClass().getName(), "AudioRecord read: " + read + " bytes");
                    }
                    int inputIndex = audioEncoder.dequeueInputBuffer(10000);
                    if (inputIndex >= 0) {
                        ByteBuffer inputBuffer = audioEncoder.getInputBuffer(inputIndex);
                        if (inputBuffer.remaining() >= read) {
                            inputBuffer.clear();
                            inputBuffer.put(buffer, 0, read);
                            audioEncoder.queueInputBuffer(inputIndex, 0, read, audioTimestamp, 0);
                            audioTimestamp += (read / 4) * 1000000L / AUDIO_SAMPLE_RATE;
                        }
                    }
                }

                int outputIndex = audioEncoder.dequeueOutputBuffer(bufferInfo, 0);
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    audioFormat = audioEncoder.getOutputFormat();
                    YaaccLogger.i(getClass().getName(), "Audio format changed: " + audioFormat);
                    if (audioFormat.containsKey("csd-0")) {
                        ByteBuffer csd = audioFormat.getByteBuffer("csd-0");
                        audioConfigData = new byte[csd.remaining()];
                        csd.get(audioConfigData);
                        YaaccLogger.i(getClass().getName(), "CSD-0 size: " + audioConfigData.length);
                        synchronized (muxerLock) {
                            if (muxer != null) muxer.setAudioConfig(audioConfigData);
                        }
                    }
                } else if (outputIndex >= 0) {
                    ByteBuffer outputBuffer = audioEncoder.getOutputBuffer(outputIndex);
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        YaaccLogger.i(getClass().getName(), "Audio sample: " + bufferInfo.size + " bytes");
                        synchronized (muxerLock) {
                            if (muxerStarted) {
                                muxer.writeAudioSample(outputBuffer, bufferInfo.presentationTimeUs);
                            }
                        }
                    }
                    audioEncoder.releaseOutputBuffer(outputIndex, false);
                }
            } catch (Exception e) {
                if (isCapturing) {
                    YaaccLogger.e(getClass().getName(), "Audio encoding error", e);
                }
            }
        }
    }

    public File getOutputFile() {
        return outputFile;
    }

    public FragmentedMp4Muxer getMuxer() {
        return muxer;
    }

    public void stopCapture() {
        isCapturing = false;

        if (videoThread != null) {
            try {
                videoThread.join(1000);
            } catch (InterruptedException e) {
            }
        }
        if (audioThread != null) {
            try {
                audioThread.join(1000);
            } catch (InterruptedException e) {
            }
        }

        synchronized (muxerLock) {
            if (muxer != null && muxerStarted) {
                try {
                    muxer.stop();
                } catch (Exception e) {
                    YaaccLogger.e(getClass().getName(), "Error stopping muxer", e);
                }
                muxer = null;
            }
        }

        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }

        if (videoEncoder != null) {
            videoEncoder.stop();
            videoEncoder.release();
            videoEncoder = null;
        }

        if (audioEncoder != null) {
            audioEncoder.stop();
            audioEncoder.release();
            audioEncoder = null;
        }

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }

        if (outputFile != null && outputFile.exists()) {
            outputFile.delete();
        }

        YaaccLogger.i(getClass().getName(), "Combined capture stopped");
    }

    public boolean isCapturing() {
        return isCapturing;
    }
}
