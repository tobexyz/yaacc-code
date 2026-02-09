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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * Minimal WebM muxer for H.264 + AAC streaming
 *
 * @author tobexyz
 */
@androidx.annotation.RequiresApi(api = android.os.Build.VERSION_CODES.Q)
public class WebMMuxer {

    private final OutputStream output;
    private boolean headerWritten = false;
    private long clusterTimestamp = 0;

    public WebMMuxer(OutputStream output) {
        this.output = output;
    }

    public void writeHeader(int width, int height, int sampleRate) throws IOException {
        if (headerWritten) return;

        // EBML Header
        writeEBML(0x1A45DFA3, new byte[]{
                0x42, (byte) 0x86, (byte) 0x81, 0x01,  // EBMLVersion = 1
                0x42, (byte) 0xF7, (byte) 0x81, 0x01,  // EBMLReadVersion = 1
                0x42, (byte) 0xF2, (byte) 0x81, 0x04,  // EBMLMaxIDLength = 4
                0x42, (byte) 0xF3, (byte) 0x81, 0x08,  // EBMLMaxSizeLength = 8
                0x42, (byte) 0x82, (byte) 0x88, 0x77, 0x65, 0x62, 0x6D, 0x00, 0x00, 0x00, 0x00,  // DocType = "webm"
                0x42, (byte) 0x87, (byte) 0x81, 0x02,  // DocTypeVersion = 2
                0x42, (byte) 0x85, (byte) 0x81, 0x02   // DocTypeReadVersion = 2
        });

        // Segment (unknown size for streaming)
        writeID(0x18538067);
        output.write(0xFF); // Unknown size

        // Tracks
        writeEBML(0x1654AE6B, buildTracks(width, height, sampleRate));

        headerWritten = true;
    }

    private byte[] buildTracks(int width, int height, int sampleRate) {
        java.io.ByteArrayOutputStream tracks = new java.io.ByteArrayOutputStream();
        try {
            // Video track
            tracks.write(buildElement(0xAE, buildVideoTrack(width, height)));
            // Audio track
            tracks.write(buildElement(0xAE, buildAudioTrack(sampleRate)));
        } catch (IOException e) {
            // Won't happen with ByteArrayOutputStream
        }
        return tracks.toByteArray();
    }

    private byte[] buildVideoTrack(int width, int height) {
        java.io.ByteArrayOutputStream track = new java.io.ByteArrayOutputStream();
        try {
            track.write(new byte[]{(byte) 0xD7, (byte) 0x81, 0x01}); // TrackNumber = 1
            track.write(new byte[]{(byte) 0x73, (byte) 0xC5, (byte) 0x81, 0x01}); // TrackUID = 1
            track.write(new byte[]{(byte) 0x83, (byte) 0x81, 0x01}); // TrackType = 1 (video)
            track.write(new byte[]{(byte) 0x86, (byte) 0x85, 0x56, 0x5F, 0x56, 0x50, 0x38}); // CodecID = "V_VP8"

            // Video settings
            java.io.ByteArrayOutputStream video = new java.io.ByteArrayOutputStream();
            video.write(new byte[]{(byte) 0xB0, (byte) 0x82}); // PixelWidth
            video.write(encodeSize(width));
            video.write(new byte[]{(byte) 0xBA, (byte) 0x82}); // PixelHeight
            video.write(encodeSize(height));

            track.write(buildElement(0xE0, video.toByteArray()));
        } catch (IOException e) {
            // Won't happen
        }
        return track.toByteArray();
    }

    private byte[] buildAudioTrack(int sampleRate) {
        java.io.ByteArrayOutputStream track = new java.io.ByteArrayOutputStream();
        try {
            track.write(new byte[]{(byte) 0xD7, (byte) 0x81, 0x02}); // TrackNumber = 2
            track.write(new byte[]{(byte) 0x73, (byte) 0xC5, (byte) 0x81, 0x02}); // TrackUID = 2
            track.write(new byte[]{(byte) 0x83, (byte) 0x81, 0x02}); // TrackType = 2 (audio)
            track.write(new byte[]{(byte) 0x86, (byte) 0x86, 0x41, 0x5F, 0x4F, 0x50, 0x55, 0x53}); // CodecID = "A_OPUS"

            // Audio settings
            java.io.ByteArrayOutputStream audio = new java.io.ByteArrayOutputStream();
            audio.write(new byte[]{(byte) 0xB5, (byte) 0x84}); // SamplingFrequency
            audio.write(floatToBytes(sampleRate));
            audio.write(new byte[]{(byte) 0x9F, (byte) 0x81, 0x02}); // Channels = 2

            track.write(buildElement(0xE1, audio.toByteArray()));
        } catch (IOException e) {
            // Won't happen
        }
        return track.toByteArray();
    }

    public void writeVideoFrame(ByteBuffer data, long timestampUs, boolean keyframe) throws IOException {
        writeFrame(data, timestampUs, 1, keyframe);
    }

    public void writeAudioFrame(ByteBuffer data, long timestampUs) throws IOException {
        writeFrame(data, timestampUs, 2, true);
    }

    private void writeFrame(ByteBuffer data, long timestampUs, int track, boolean keyframe) throws IOException {
        long timestampMs = timestampUs / 1000;

        // Start new cluster every 1 second or on keyframe
        if (timestampMs - clusterTimestamp >= 1000 || (keyframe && track == 1)) {
            writeClusterHeader(timestampMs);
            clusterTimestamp = timestampMs;
        }

        // SimpleBlock
        writeID(0xA3);
        int size = data.remaining() + 4;
        writeSize(size);

        output.write(0x80 | track); // Track number
        output.write((byte) ((timestampMs - clusterTimestamp) >> 8)); // Timestamp relative to cluster
        output.write((byte) (timestampMs - clusterTimestamp));
        output.write(keyframe ? (byte) 0x80 : 0x00); // Flags (keyframe bit)

        byte[] frameData = new byte[data.remaining()];
        data.get(frameData);
        output.write(frameData);
        output.flush();
    }

    private void writeClusterHeader(long timestampMs) throws IOException {
        writeID(0x1F43B675); // Cluster
        output.write(0xFF); // Unknown size

        // Timestamp
        writeID(0xE7);
        writeSize(8);
        writeLong(timestampMs);
    }

    private void writeEBML(int id, byte[] data) throws IOException {
        writeID(id);
        writeSize(data.length);
        output.write(data);
    }

    private byte[] buildElement(int id, byte[] data) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            writeIDToStream(out, id);
            writeSizeToStream(out, data.length);
            out.write(data);
        } catch (IOException e) {
            // Won't happen
        }
        return out.toByteArray();
    }

    private void writeID(int id) throws IOException {
        writeIDToStream(output, id);
    }

    private void writeIDToStream(OutputStream out, int id) throws IOException {
        if (id > 0xFFFFFF) {
            out.write((id >> 24) & 0xFF);
        }
        if (id > 0xFFFF) {
            out.write((id >> 16) & 0xFF);
        }
        if (id > 0xFF) {
            out.write((id >> 8) & 0xFF);
        }
        out.write(id & 0xFF);
    }

    private void writeSize(int size) throws IOException {
        writeSizeToStream(output, size);
    }

    private void writeSizeToStream(OutputStream out, int size) throws IOException {
        if (size < 127) {
            out.write(0x80 | size);
        } else if (size < 16383) {
            out.write(0x40 | (size >> 8));
            out.write(size & 0xFF);
        } else {
            out.write(0x20 | (size >> 16));
            out.write((size >> 8) & 0xFF);
            out.write(size & 0xFF);
        }
    }

    private void writeLong(long value) throws IOException {
        for (int i = 7; i >= 0; i--) {
            output.write((byte) ((value >> (i * 8)) & 0xFF));
        }
    }

    private byte[] encodeSize(int value) {
        return new byte[]{(byte) (value >> 8), (byte) (value & 0xFF)};
    }

    private byte[] floatToBytes(float value) {
        int bits = Float.floatToIntBits(value);
        return new byte[]{
                (byte) ((bits >> 24) & 0xFF),
                (byte) ((bits >> 16) & 0xFF),
                (byte) ((bits >> 8) & 0xFF),
                (byte) (bits & 0xFF)
        };
    }
}
