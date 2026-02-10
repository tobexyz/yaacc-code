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

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;

import de.yaacc.util.YaaccLogger;

public class FragmentedMp4Muxer {

    private static final int TS_PACKET_SIZE = 188;
    private static final int VIDEO_PID = 0x101;
    private static final int AUDIO_PID = 0x102;
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB circular buffer
    private static final int PAT_PMT_INTERVAL = 30; // Write PAT/PMT every N video frames

    private final File outputFile;
    private RandomAccessFile outputStream;
    private boolean started = false;
    private int videoContinuity = 0;
    private int audioContinuity = 0;
    private int patContinuity = 0;
    private int pmtContinuity = 0;
    private byte[] audioSpecificConfig = null;
    private long firstVideoPts = -1;
    private long firstAudioPts = -1;
    private long writePosition = 0;
    private int videoFrameCount = 0;
    private long lastKeyframePosition = 0;
    private long lastRealPts = -1;
    private long ptsOffset = 0;

    public FragmentedMp4Muxer(File outputFile) {
        this.outputFile = outputFile;
    }

    public void start() throws IOException {
        outputStream = new RandomAccessFile(outputFile, "rw");
        outputStream.setLength(MAX_FILE_SIZE); // Pre-allocate
        writePosition = 0;
        firstVideoPts = -1;
        firstAudioPts = -1;
        videoContinuity = 0;
        audioContinuity = 0;
        patContinuity = 0;
        pmtContinuity = 0;
        writePAT();
        writePMT();
        started = true;
        YaaccLogger.i(getClass().getName(), "MPEG-TS muxer started with " + MAX_FILE_SIZE + " byte circular buffer");
    }

    public void setAudioConfig(byte[] config) {
        YaaccLogger.i(getClass().getName(), "setAudioConfig called with " + (config != null ? config.length : 0) + " bytes");
        this.audioSpecificConfig = config;
        if (config != null && config.length >= 2) {
            int profile = ((config[0] >> 3) & 0x1F);
            int freqIndex = ((config[0] & 0x07) << 1) | ((config[1] >> 7) & 0x01);
            int channelConfig = (config[1] >> 3) & 0x0F;
            YaaccLogger.i(getClass().getName(), "AAC config: profile=" + profile + " freq=" + freqIndex + " channels=" + channelConfig + " bytes=" + String.format("%02X %02X", config[0], config[1]));
        }
    }

    private void writePAT() throws IOException {
        byte[] packet = new byte[TS_PACKET_SIZE];
        packet[0] = 0x47;
        packet[1] = 0x40;
        packet[2] = 0x00;
        packet[3] = (byte) (0x10 | (patContinuity & 0x0F));
        packet[4] = 0x00;
        packet[5] = 0x00;
        packet[6] = (byte) 0xB0;
        packet[7] = 0x0D;
        packet[8] = 0x00;
        packet[9] = 0x01;
        packet[10] = (byte) 0xC1;
        packet[11] = 0x00;
        packet[12] = 0x00;
        packet[13] = 0x00;
        packet[14] = 0x01;
        packet[15] = (byte) 0xE1;
        packet[16] = 0x00;
        int crc = crc32(packet, 5, 12);
        packet[17] = (byte) (crc >> 24);
        packet[18] = (byte) (crc >> 16);
        packet[19] = (byte) (crc >> 8);
        packet[20] = (byte) crc;
        for (int i = 21; i < TS_PACKET_SIZE; i++) packet[i] = (byte) 0xFF;
        writePacket(packet);
        patContinuity = (patContinuity + 1) & 0x0F;
    }

    private void writePMT() throws IOException {
        byte[] packet = new byte[TS_PACKET_SIZE];
        packet[0] = 0x47;
        packet[1] = 0x41;
        packet[2] = 0x00;
        packet[3] = (byte) (0x10 | (pmtContinuity & 0x0F));
        packet[4] = 0x00;
        packet[5] = 0x02;
        packet[6] = (byte) 0xB0;
        packet[7] = 0x17;
        packet[8] = 0x00;
        packet[9] = 0x01;
        packet[10] = (byte) 0xC1;
        packet[11] = 0x00;
        packet[12] = 0x00;
        packet[13] = (byte) 0xE1;
        packet[14] = 0x01;
        packet[15] = (byte) 0xF0;
        packet[16] = 0x00;
        // Video stream
        packet[17] = 0x1B;
        packet[18] = (byte) 0xE1;
        packet[19] = 0x01;
        packet[20] = (byte) 0xF0;
        packet[21] = 0x00;
        // Audio stream - AAC with ADTS
        packet[22] = 0x0F;
        packet[23] = (byte) 0xE1;
        packet[24] = 0x02;
        packet[25] = (byte) 0xF0;
        packet[26] = 0x00;

        int crc = crc32(packet, 5, 22);
        packet[27] = (byte) (crc >> 24);
        packet[28] = (byte) (crc >> 16);
        packet[29] = (byte) (crc >> 8);
        packet[30] = (byte) crc;
        for (int i = 31; i < TS_PACKET_SIZE; i++) packet[i] = (byte) 0xFF;
        writePacket(packet);
        pmtContinuity = (pmtContinuity + 1) & 0x0F;
    }

    private void writePacket(byte[] packet) throws IOException {
        outputStream.seek(writePosition);
        outputStream.write(packet);
        writePosition = (writePosition + TS_PACKET_SIZE) % MAX_FILE_SIZE;
    }

    public synchronized void writeVideoSample(ByteBuffer buffer, long pts, boolean keyFrame) throws IOException {
        if (!started) return;

        // Handle PTS discontinuities by tracking offset
        if (firstVideoPts < 0) {
            firstVideoPts = pts;
            lastRealPts = pts;
            ptsOffset = 0;
            YaaccLogger.i(getClass().getName(), "First video PTS: " + pts);
        } else {
            // Detect discontinuity (jump > 5 seconds)
            long delta = pts - lastRealPts;
            if (delta < 0 || delta > 450000) { // 5 seconds at 90kHz
                ptsOffset += (lastRealPts - firstVideoPts);
                firstVideoPts = pts;
                YaaccLogger.w(getClass().getName(), "PTS discontinuity detected, adjusting offset to " + ptsOffset);
            }
            lastRealPts = pts;
        }

        // Write PAT/PMT at keyframes for clients joining mid-stream
        if (keyFrame) {
            lastKeyframePosition = writePosition; // Save position before PAT/PMT
            // Reset PTS at keyframes to prevent unbounded growth
            firstVideoPts = pts;
            firstAudioPts = -1;
            ptsOffset = 0;
            writePAT();
            writePMT();
        }
        videoFrameCount++;

        // Use real PTS with offset correction
        long continuousPts = (pts - firstVideoPts) + ptsOffset;
        if (videoFrameCount % 30 == 0) {
            YaaccLogger.i(getClass().getName(), "Frame " + videoFrameCount + " PTS: " + continuousPts + " (" + (continuousPts / 90000.0) + "s)");
        }
        writePES(VIDEO_PID, 0xE0, buffer, continuousPts, videoContinuity, true);
    }

    public long getLastKeyframePosition() {
        return lastKeyframePosition;
    }

    public synchronized void writeAudioSample(ByteBuffer buffer, long pts) throws IOException {
        if (!started) return;
        if (firstAudioPts < 0) firstAudioPts = pts;

        int frameSize = buffer.remaining();
        int packetLen = frameSize + 7;

        byte[] adts = new byte[packetLen];
        adts[0] = (byte) 0xFF;
        adts[1] = (byte) 0xF1;
        adts[2] = (byte) 0x50;
        adts[3] = (byte) (0x80 | ((packetLen >> 11) & 0x03));
        adts[4] = (byte) ((packetLen >> 3) & 0xFF);
        adts[5] = (byte) (((packetLen & 0x07) << 5) | 0x1F);
        adts[6] = (byte) 0xFC;
        buffer.get(adts, 7, frameSize);

        long relativePts = pts - firstAudioPts;
        writePES(AUDIO_PID, 0xC0, ByteBuffer.wrap(adts), relativePts, audioContinuity, false);
    }

    private void writeRawToTS(int pid, byte[] data, int startContinuity) throws IOException {
        synchronized (outputStream) {
            int offset = 0;
            boolean first = true;
            int continuity = startContinuity;

            while (offset < data.length) {
                byte[] packet = new byte[TS_PACKET_SIZE];
                packet[0] = 0x47;
                packet[1] = (byte) ((first ? 0x40 : 0x00) | ((pid >> 8) & 0x1F));
                packet[2] = (byte) (pid & 0xFF);
                packet[3] = (byte) (0x10 | (continuity & 0x0F));

                int toCopy = Math.min(TS_PACKET_SIZE - 4, data.length - offset);
                System.arraycopy(data, offset, packet, 4, toCopy);
                for (int i = 4 + toCopy; i < TS_PACKET_SIZE; i++) packet[i] = (byte) 0xFF;

                writePacket(packet);
                offset += toCopy;
                first = false;
                continuity = (continuity + 1) & 0x0F;
            }

            if (pid == AUDIO_PID) {
                audioContinuity = continuity;
            }
        }
    }

    private void writePES(int pid, int streamId, ByteBuffer data, long pts, int continuity, boolean includePCR) throws IOException {
        synchronized (outputStream) {
            int size = data.remaining();
            byte[] payload = new byte[size];
            data.get(payload);

            byte[] pes = new byte[14 + size];
            pes[0] = 0;
            pes[1] = 0;
            pes[2] = 1;
            pes[3] = (byte) streamId;
            // PES packet length: 0 for video (unbounded), actual length for audio
            if (pid == VIDEO_PID) {
                pes[4] = 0;
                pes[5] = 0;
            } else {
                pes[4] = (byte) ((size + 8) >> 8);
                pes[5] = (byte) (size + 8);
            }
            pes[6] = (byte) 0x80;
            pes[7] = (byte) 0x80;
            pes[8] = 0x05;
            long ptsValue = pts * 9 / 100;
            pes[9] = (byte) (0x21 | ((ptsValue >> 29) & 0x0E));
            pes[10] = (byte) ((ptsValue >> 22) & 0xFF);
            pes[11] = (byte) (0x01 | ((ptsValue >> 14) & 0xFE));
            pes[12] = (byte) ((ptsValue >> 7) & 0xFF);
            pes[13] = (byte) (0x01 | ((ptsValue << 1) & 0xFE));
            System.arraycopy(payload, 0, pes, 14, size);

            int offset = 0;
            boolean first = true;
            int packetContinuity = continuity;

            while (offset < pes.length) {
                byte[] packet = new byte[TS_PACKET_SIZE];
                packet[0] = 0x47;
                packet[1] = (byte) ((first ? 0x40 : 0x00) | ((pid >> 8) & 0x1F));
                packet[2] = (byte) (pid & 0xFF);

                int headerSize = 4;

                if (first && includePCR) {
                    packet[3] = (byte) (0x30 | (packetContinuity & 0x0F));
                    packet[4] = 7;
                    packet[5] = 0x10;

                    long pcrBase = ptsValue / 300;
                    int pcrExt = (int) (ptsValue % 300);
                    packet[6] = (byte) ((pcrBase >> 25) & 0xFF);
                    packet[7] = (byte) ((pcrBase >> 17) & 0xFF);
                    packet[8] = (byte) ((pcrBase >> 9) & 0xFF);
                    packet[9] = (byte) ((pcrBase >> 1) & 0xFF);
                    packet[10] = (byte) (((pcrBase & 1) << 7) | 0x7E | ((pcrExt >> 8) & 1));
                    packet[11] = (byte) (pcrExt & 0xFF);

                    headerSize = 12;
                } else {
                    packet[3] = (byte) (0x10 | (packetContinuity & 0x0F));
                }

                int toCopy = Math.min(TS_PACKET_SIZE - headerSize, pes.length - offset);
                System.arraycopy(pes, offset, packet, headerSize, toCopy);
                for (int i = headerSize + toCopy; i < TS_PACKET_SIZE; i++) packet[i] = (byte) 0xFF;

                writePacket(packet);
                offset += toCopy;
                first = false;
                packetContinuity = (packetContinuity + 1) & 0x0F;
            }

            if (pid == VIDEO_PID) {
                videoContinuity = packetContinuity;
            } else if (pid == AUDIO_PID) {
                audioContinuity = packetContinuity;
            }
        }
    }

    public void stop() throws IOException {
        started = false;
        if (outputStream != null) {
            outputStream.close();
            outputStream = null;
        }
    }

    public long getWritePosition() {
        return writePosition;
    }

    private static final int[] CRC_TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int crc = i << 24;
            for (int j = 0; j < 8; j++) {
                crc = (crc << 1) ^ ((crc & 0x80000000) != 0 ? 0x04C11DB7 : 0);
            }
            CRC_TABLE[i] = crc;
        }
    }

    private int crc32(byte[] data, int offset, int length) {
        int crc = 0xFFFFFFFF;
        for (int i = 0; i < length; i++) {
            crc = (crc << 8) ^ CRC_TABLE[((crc >> 24) ^ (data[offset + i] & 0xFF)) & 0xFF];
        }
        return crc;
    }
}
