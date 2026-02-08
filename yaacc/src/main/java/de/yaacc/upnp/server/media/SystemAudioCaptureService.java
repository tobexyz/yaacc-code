/*
 * Copyright (C) 2026 www.yaacc.de
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

import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.os.Build;

import androidx.annotation.RequiresApi;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.List;

import de.yaacc.util.YaaccLogger;

/**
 * Captures system audio using AudioPlaybackCapture (Android 10+).
 * Provides PCM audio data via InputStream.
 *
 * @author tobexyz
 */
@RequiresApi(api = Build.VERSION_CODES.Q)
public class SystemAudioCaptureService {

    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BUFFER_SIZE_MULTIPLIER = 8; // Larger buffer for stability
    
    private AudioRecord audioRecord;
    private Thread captureThread;
    private volatile boolean isCapturing = false;
    private final List<PipedOutputStream> outputStreams = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Start capturing system audio.
     */
    public boolean startCapture(MediaProjection mediaProjection) {
        if (isCapturing) {
            YaaccLogger.w(getClass().getName(), "Already capturing");
            return false;
        }

        try {
            int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
            bufferSize *= BUFFER_SIZE_MULTIPLIER; // Increase buffer for stability
            
            AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
                .build();

            audioRecord = new AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(new AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG)
                    .build())
                .setBufferSizeInBytes(bufferSize)
                .build();

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                YaaccLogger.e(getClass().getName(), "AudioRecord not initialized");
                return false;
            }

            audioRecord.startRecording();
            isCapturing = true;

            captureThread = new Thread(this::captureLoop);
            captureThread.setPriority(Thread.MAX_PRIORITY); // High priority for audio
            captureThread.start();

            YaaccLogger.i(getClass().getName(), "Audio capture started with buffer size: " + bufferSize);
            return true;

        } catch (Exception e) {
            YaaccLogger.e(getClass().getName(), "Failed to start audio capture", e);
            stopCapture();
            return false;
        }
    }

    /**
     * Stop capturing audio.
     */
    public void stopCapture() {
        isCapturing = false;

        if (captureThread != null) {
            try {
                captureThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            captureThread = null;
        }

        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception e) {
                YaaccLogger.e(getClass().getName(), "Error stopping AudioRecord", e);
            }
            audioRecord = null;
        }

        // Close all output streams
        for (PipedOutputStream stream : outputStreams) {
            try {
                stream.close();
            } catch (IOException e) {
                YaaccLogger.e(getClass().getName(), "Error closing output stream", e);
            }
        }
        outputStreams.clear();

        YaaccLogger.i(getClass().getName(), "Audio capture stopped");
    }

    /**
     * Create a new input stream for a client.
     * Each client gets its own stream for concurrent playback.
     */
    public java.io.InputStream createInputStream() throws IOException {
        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * BUFFER_SIZE_MULTIPLIER;
        PipedOutputStream outputStream = new PipedOutputStream();
        PipedInputStream inputStream = new PipedInputStream(outputStream, bufferSize * 4);
        
        outputStreams.add(outputStream);
        YaaccLogger.i(getClass().getName(), "Created new input stream, total clients: " + outputStreams.size());
        
        return inputStream;
    }
    
    /**
     * Remove a client's output stream when they disconnect.
     */
    private void removeOutputStream(PipedOutputStream stream) {
        if (outputStreams.remove(stream)) {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
            YaaccLogger.i(getClass().getName(), "Removed output stream, remaining clients: " + outputStreams.size());
        }
    }

    /**
     * Get InputStream wrapped with WAV header.
     */
    public java.io.InputStream getWavInputStream() throws IOException {
        if (!isCapturing) {
            return null;
        }
        java.io.InputStream stream = createInputStream();
        return new WavHeaderInputStream(stream, SAMPLE_RATE, 2, 16);
    }

    /**
     * Check if currently capturing.
     */
    public boolean isCapturing() {
        return isCapturing;
    }

    private void captureLoop() {
        byte[] buffer = new byte[8192];
        
        while (isCapturing && audioRecord != null) {
            int bytesRead = audioRecord.read(buffer, 0, buffer.length);
            
            if (bytesRead > 0) {
                // Broadcast to all connected clients
                List<PipedOutputStream> deadStreams = new java.util.ArrayList<>();
                
                for (PipedOutputStream stream : outputStreams) {
                    try {
                        stream.write(buffer, 0, bytesRead);
                        stream.flush();
                    } catch (IOException e) {
                        // Client disconnected or pipe broken
                        YaaccLogger.d(getClass().getName(), "Client stream error: " + e.getMessage());
                        deadStreams.add(stream);
                    }
                }
                
                // Remove dead streams
                for (PipedOutputStream dead : deadStreams) {
                    removeOutputStream(dead);
                }
                
            } else if (bytesRead < 0) {
                YaaccLogger.e(getClass().getName(), "AudioRecord read error: " + bytesRead);
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        YaaccLogger.i(getClass().getName(), "Capture loop ended");
    }
}
