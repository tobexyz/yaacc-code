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
import java.io.InputStream;

/**
 * Wraps PCM audio stream with WAV header for streaming.
 *
 * @author tobexyz
 */
public class WavHeaderInputStream extends InputStream {

    private final InputStream pcmStream;
    private final byte[] header;
    private int headerPos = 0;
    private boolean headerSent = false;

    public WavHeaderInputStream(InputStream pcmStream, int sampleRate, int channels, int bitsPerSample) {
        this.pcmStream = pcmStream;
        this.header = createWavHeader(sampleRate, channels, bitsPerSample);
        de.yaacc.util.YaaccLogger.d(getClass().getName(), "Created WAV header wrapper: " + sampleRate + "Hz, " + channels + "ch, " + bitsPerSample + "bit");
    }

    @Override
    public int read() throws IOException {
        if (!headerSent) {
            if (headerPos < header.length) {
                return header[headerPos++] & 0xFF;
            }
            headerSent = true;
        }
        return pcmStream.read();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (!headerSent) {
            int headerRemaining = header.length - headerPos;
            if (headerRemaining > 0) {
                int toCopy = Math.min(headerRemaining, len);
                System.arraycopy(header, headerPos, b, off, toCopy);
                headerPos += toCopy;
                return toCopy;
            }
            headerSent = true;
        }
        return pcmStream.read(b, off, len);
    }

    @Override
    public void close() throws IOException {
        pcmStream.close();
    }

    private byte[] createWavHeader(int sampleRate, int channels, int bitsPerSample) {
        byte[] header = new byte[44];
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;

        // RIFF header
        header[0] = 'R';
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';
        writeInt(header, 4, 0x7FFFFFFF); // File size (unknown for streaming)
        header[8] = 'W';
        header[9] = 'A';
        header[10] = 'V';
        header[11] = 'E';

        // fmt chunk
        header[12] = 'f';
        header[13] = 'm';
        header[14] = 't';
        header[15] = ' ';
        writeInt(header, 16, 16); // fmt chunk size
        writeShort(header, 20, (short) 1); // PCM format
        writeShort(header, 22, (short) channels);
        writeInt(header, 24, sampleRate);
        writeInt(header, 28, byteRate);
        writeShort(header, 32, (short) blockAlign);
        writeShort(header, 34, (short) bitsPerSample);

        // data chunk
        header[36] = 'd';
        header[37] = 'a';
        header[38] = 't';
        header[39] = 'a';
        writeInt(header, 40, 0x7FFFFFFF); // Data size (unknown for streaming)

        return header;
    }

    private void writeInt(byte[] buffer, int offset, int value) {
        buffer[offset] = (byte) (value & 0xFF);
        buffer[offset + 1] = (byte) ((value >> 8) & 0xFF);
        buffer[offset + 2] = (byte) ((value >> 16) & 0xFF);
        buffer[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private void writeShort(byte[] buffer, int offset, short value) {
        buffer[offset] = (byte) (value & 0xFF);
        buffer[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }
}
