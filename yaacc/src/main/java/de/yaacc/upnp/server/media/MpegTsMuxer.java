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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Minimal MPEG-TS muxer for H.264 video and AAC audio streaming.
 * 
 * @author tobexyz
 */
public class MpegTsMuxer {
    
    private static final int TS_PACKET_SIZE = 188;
    private static final int PMT_PID = 0x100;
    private static final int VIDEO_PID = 0x101;
    private static final int AUDIO_PID = 0x102;
    
    private int patContinuity = 0;
    private int pmtContinuity = 0;
    private int videoContinuity = 0;
    private int audioContinuity = 0;
    
    private long videoPts = 0;
    private long audioPts = 0;
    
    /**
     * Write PAT (Program Association Table).
     */
    public void writePAT(OutputStream out) throws IOException {
        ByteArrayOutputStream packet = new ByteArrayOutputStream(TS_PACKET_SIZE);
        
        // TS header
        packet.write(0x47); // sync byte
        packet.write(0x40); // payload start, PID high bits (0x0000)
        packet.write(0x00); // PID low bits
        packet.write(0x10 | (patContinuity++ & 0x0F)); // no adaptation, payload only, continuity
        
        // Pointer field
        packet.write(0x00);
        
        // PAT
        packet.write(0x00); // table_id
        packet.write(0xB0); // section syntax, section length high
        packet.write(0x0D); // section length low (13 bytes)
        packet.write(0x00); packet.write(0x01); // transport_stream_id
        packet.write(0xC1); // version 0, current
        packet.write(0x00); // section_number
        packet.write(0x00); // last_section_number
        packet.write(0x00); packet.write(0x01); // program_number
        packet.write(0xE0 | (PMT_PID >> 8)); // PMT PID high
        packet.write(PMT_PID & 0xFF); // PMT PID low
        
        // CRC32 (simplified - use zeros)
        packet.write(0x00); packet.write(0x00); packet.write(0x00); packet.write(0x00);
        
        // Padding
        while (packet.size() < TS_PACKET_SIZE) {
            packet.write(0xFF);
        }
        
        out.write(packet.toByteArray());
    }
    
    /**
     * Write PMT (Program Map Table).
     */
    public void writePMT(OutputStream out) throws IOException {
        ByteArrayOutputStream packet = new ByteArrayOutputStream(TS_PACKET_SIZE);
        
        // TS header
        packet.write(0x47); // sync byte
        packet.write(0x40 | (PMT_PID >> 8)); // payload start, PID high
        packet.write(PMT_PID & 0xFF); // PID low
        packet.write(0x10 | (pmtContinuity++ & 0x0F)); // continuity
        
        // Pointer field
        packet.write(0x00);
        
        // PMT
        packet.write(0x02); // table_id
        packet.write(0xB0); // section syntax, section length high
        packet.write(0x17); // section length low (23 bytes)
        packet.write(0x00); packet.write(0x01); // program_number
        packet.write(0xC1); // version 0, current
        packet.write(0x00); // section_number
        packet.write(0x00); // last_section_number
        packet.write(0xE0 | (VIDEO_PID >> 8)); // PCR PID high
        packet.write(VIDEO_PID & 0xFF); // PCR PID low
        packet.write(0xF0); packet.write(0x00); // program_info_length
        
        // Video stream (H.264)
        packet.write(0x1B); // stream_type (H.264)
        packet.write(0xE0 | (VIDEO_PID >> 8)); // elementary PID high
        packet.write(VIDEO_PID & 0xFF); // elementary PID low
        packet.write(0xF0); packet.write(0x00); // ES_info_length
        
        // Audio stream (AAC)
        packet.write(0x0F); // stream_type (AAC)
        packet.write(0xE0 | (AUDIO_PID >> 8)); // elementary PID high
        packet.write(AUDIO_PID & 0xFF); // elementary PID low
        packet.write(0xF0); packet.write(0x00); // ES_info_length
        
        // CRC32 (simplified)
        packet.write(0x00); packet.write(0x00); packet.write(0x00); packet.write(0x00);
        
        // Padding
        while (packet.size() < TS_PACKET_SIZE) {
            packet.write(0xFF);
        }
        
        out.write(packet.toByteArray());
    }
    
    /**
     * Write PES packet for video.
     */
    public void writeVideo(OutputStream out, byte[] data, boolean keyFrame) throws IOException {
        writePES(out, VIDEO_PID, data, videoPts, keyFrame, true);
        videoPts += 3003; // ~30fps (90000 / 30)
    }
    
    /**
     * Write PES packet for audio.
     */
    public void writeAudio(OutputStream out, byte[] data) throws IOException {
        writePES(out, AUDIO_PID, data, audioPts, false, false);
        audioPts += 2048; // AAC frame duration at 44.1kHz
    }
    
    private void writePES(OutputStream out, int pid, byte[] data, long pts, boolean keyFrame, boolean isVideo) throws IOException {
        // PES header
        ByteArrayOutputStream pes = new ByteArrayOutputStream();
        pes.write(0x00); pes.write(0x00); pes.write(0x01); // packet_start_code
        pes.write(isVideo ? 0xE0 : 0xC0); // stream_id
        
        int pesHeaderLength = 8; // PTS only
        int pesPacketLength = data.length + pesHeaderLength + 3;
        if (pesPacketLength > 0xFFFF) pesPacketLength = 0; // unbounded
        
        pes.write((pesPacketLength >> 8) & 0xFF);
        pes.write(pesPacketLength & 0xFF);
        
        pes.write(0x80); // marker bits
        pes.write(0x80); // PTS flag
        pes.write(pesHeaderLength); // PES_header_data_length
        
        // PTS (33 bits)
        long ptsValue = pts & 0x1FFFFFFFFL;
        pes.write((int)(0x21 | ((ptsValue >> 29) & 0x0E)));
        pes.write((int)((ptsValue >> 22) & 0xFF));
        pes.write((int)(0x01 | ((ptsValue >> 14) & 0xFE)));
        pes.write((int)((ptsValue >> 7) & 0xFF));
        pes.write((int)(0x01 | ((ptsValue << 1) & 0xFE)));
        
        pes.write(data);
        
        byte[] pesData = pes.toByteArray();
        
        // Fragment into TS packets
        int offset = 0;
        boolean first = true;
        int continuity = isVideo ? videoContinuity : audioContinuity;
        
        while (offset < pesData.length) {
            ByteArrayOutputStream packet = new ByteArrayOutputStream(TS_PACKET_SIZE);
            
            // TS header
            packet.write(0x47); // sync byte
            int flags = (pid >> 8) & 0x1F;
            if (first) flags |= 0x40; // payload_unit_start
            packet.write(flags);
            packet.write(pid & 0xFF);
            packet.write(0x10 | (continuity++ & 0x0F));
            
            // Payload
            int payloadSize = Math.min(TS_PACKET_SIZE - 4, pesData.length - offset);
            packet.write(pesData, offset, payloadSize);
            offset += payloadSize;
            
            // Padding
            while (packet.size() < TS_PACKET_SIZE) {
                packet.write(0xFF);
            }
            
            out.write(packet.toByteArray());
            first = false;
        }
        
        if (isVideo) {
            videoContinuity = continuity;
        } else {
            audioContinuity = continuity;
        }
    }
}
