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

import android.media.MediaCodec;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Streaming muxer using raw H.264 Annex B format
 * Simpler than containers, works with most players
 *
 * @author tobexyz
 */
public class StreamingMuxer {

    private final File outputFile;
    private FileOutputStream outputStream;
    private boolean started = false;

    public StreamingMuxer(File outputFile) {
        this.outputFile = outputFile;
    }

    public void start() throws IOException {
        outputStream = new FileOutputStream(outputFile);
        started = true;
    }

    public synchronized void writeVideoSample(ByteBuffer buffer, MediaCodec.BufferInfo info) throws IOException {
        if (!started || outputStream == null) return;

        byte[] data = new byte[info.size];
        buffer.get(data);
        outputStream.write(data);
    }

    public synchronized void writeAudioSample(ByteBuffer buffer, MediaCodec.BufferInfo info) throws IOException {
        // Skip audio for now - H.264 only stream
    }

    public void stop() throws IOException {
        if (outputStream != null) {
            outputStream.flush();
            outputStream.close();
            outputStream = null;
        }
        started = false;
    }

    public void flush() throws IOException {
        if (outputStream != null) {
            outputStream.flush();
        }
    }
}
