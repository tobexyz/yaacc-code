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
package de.yaacc.sendspin.player;

import de.yaacc.multiroom.client.EngineHost;
import de.yaacc.multiroom.client.SnapcastClientEngine;
import de.yaacc.multiroom.protocol.Codec;
import de.yaacc.multiroom.protocol.CodecHeaderMessage;
import de.yaacc.multiroom.protocol.ServerSettingsMessage;
import de.yaacc.multiroom.protocol.WireChunkMessage;
import de.yaacc.sendspin.client.PlayerSession;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Plays the stream of a Sendspin server with the engine that already plays a Snapcast stream in sync ({@link SnapcastClientEngine}: exact
 * start, drift correction by dropping or repeating single frames, the output's own position reports). This class only translates: a
 * Sendspin stream start is a PCM header, a chunk's timestamp is the time it must be heard on the server's clock (the Snapcast
 * "buffer" is 0, so the engine plays it at {@code timestamp - offset}), volume and mute are the settings, the output delay of the
 * player is the manual latency, and a {@code stream/clear} or {@code stream/end} is a flush of the engine (the header is sent again to
 * go on after a clear). The offset of the clock is the session's.
 */
public final class SendspinAudioEngine implements PlayerSession.Audio {

    /** What the engine side takes; the production one is an {@link EngineHost}, the tests drive the engine by hand. */
    public interface Target {
        void onServerSettings(ServerSettingsMessage settings);

        void onCodecHeader(CodecHeaderMessage header);

        void onChunk(WireChunkMessage chunk);

        /** Drops what is buffered and silences the output; the stream goes on with the next header. */
        void flush();

        void setManualLatencyMs(int milliseconds);
    }

    /** A target that runs on the engine thread of an {@link EngineHost}. */
    public static Target onHost(final EngineHost host) {
        return new Target() {
            @Override
            public void onServerSettings(ServerSettingsMessage settings) {
                host.onServerSettings(settings);
            }

            @Override
            public void onCodecHeader(CodecHeaderMessage header) {
                host.onCodecHeader(header);
            }

            @Override
            public void onChunk(WireChunkMessage chunk) {
                host.onChunk(chunk);
            }

            @Override
            public void flush() {
                host.onLost(null);
            }

            @Override
            public void setManualLatencyMs(int milliseconds) {
                host.setManualLatencyMs(milliseconds);
            }
        };
    }

    /** A target that calls the engine itself (tests, and a caller that is the engine's one thread). */
    public static Target direct(final SnapcastClientEngine engine) {
        return new Target() {
            @Override
            public void onServerSettings(ServerSettingsMessage settings) {
                engine.onServerSettings(settings);
            }

            @Override
            public void onCodecHeader(CodecHeaderMessage header) {
                engine.onCodecHeader(header);
            }

            @Override
            public void onChunk(WireChunkMessage chunk) {
                engine.onChunk(chunk);
            }

            @Override
            public void flush() {
                engine.onConnectionLost();
            }

            @Override
            public void setManualLatencyMs(int milliseconds) {
                engine.setManualLatencyMs(milliseconds);
            }
        };
    }

    private final Target target;
    private volatile int volume = 100;
    private volatile boolean muted;
    private CodecHeaderMessage header;

    public SendspinAudioEngine(Target target) {
        this.target = target;
    }

    private void sendSettings() {
        // no buffer: a Sendspin timestamp is the time the chunk is heard, not the time it was recorded; the latency is the manual one
        target.onServerSettings(new ServerSettingsMessage(0, 0, muted, volume));
    }

    // ------------------------------------------------------------------ PlayerSession.Audio

    @Override
    public void start(String codec, int sampleRate, int channels, int bitDepth, byte[] codecHeader) {
        // PCM: the engine reads the format from a RIFF header made here; FLAC: the header of the server (fLaC and the stream info)
        CodecHeaderMessage made = "flac".equals(codec) ? CodecHeaderMessage.of(Codec.FLAC, codecHeader)
                : CodecHeaderMessage.of(Codec.PCM, wavHeader(sampleRate, channels, bitDepth));
        if (made.getSampleRate() != sampleRate || made.getChannels() != channels) {
            throw new IllegalArgumentException("the header does not match the stream");
        }
        header = made;
        sendSettings();
        target.onCodecHeader(header);
    }

    @Override
    public void chunk(long serverTimestampMicros, byte[] pcm) {
        long seconds = Math.floorDiv(serverTimestampMicros, 1_000_000L);
        if (header == null || seconds < 0 || seconds > Integer.MAX_VALUE) {
            return; // no stream, or a time the engine can not take
        }
        target.onChunk(new WireChunkMessage((int) seconds, (int) Math.floorMod(serverTimestampMicros, 1_000_000L), pcm));
    }

    @Override
    public void clear(Long fromTimestamp) {
        // the engine knows one flush: everything buffered goes (a clear from a time on is treated as a clear of all)
        target.flush();
        if (header != null) {
            target.onCodecHeader(header);
        }
    }

    @Override
    public void end() {
        target.flush();
        header = null;
    }

    // ------------------------------------------------------------------ the controls

    public void setVolume(int percent) {
        volume = Math.max(0, Math.min(100, percent));
        sendSettings();
    }

    public void setMuted(boolean mute) {
        muted = mute;
        sendSettings();
    }

    /** The delay of the output beyond the audio port (a Bluetooth speaker): the engine plays this much earlier. */
    public void setOutputDelayMs(int milliseconds) {
        target.setManualLatencyMs(milliseconds);
    }

    public int volume() {
        return volume;
    }

    public boolean muted() {
        return muted;
    }

    /** The 44 byte RIFF header of the PCM stream (the engine reads the format from it). */
    static byte[] wavHeader(int sampleRate, int channels, int bitDepth) {
        int blockAlign = channels * bitDepth / 8;
        ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36).put("WAVE".getBytes(StandardCharsets.US_ASCII));
        b.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) channels).putInt(sampleRate)
                .putInt(sampleRate * blockAlign).putShort((short) blockAlign).putShort((short) bitDepth);
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(0);
        return b.array();
    }
}
