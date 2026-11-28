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
package de.yaacc.multiroom.server;

import static org.junit.Assert.assertEquals;

import de.yaacc.multiroom.protocol.Codec;
import de.yaacc.multiroom.protocol.CodecHeaderMessage;
import de.yaacc.multiroom.protocol.PcmFormat;

import org.junit.Test;

/** The RIFF WAVE header that is the CodecHeader of a pcm stream: the app's own parser reads back what the server writes. */
public class PcmWaveHeaderTest {

    @Test
    public void theHeaderIs44BytesAndReadsBackAsTheFormatItWasMadeFrom() throws Exception {
        byte[] header = PcmWaveHeader.build(48000, 2, 16);
        assertEquals(44, header.length);
        PcmFormat format = PcmFormat.parse(header);
        assertEquals(48000, format.getSampleRate());
        assertEquals(2, format.getChannels());
        assertEquals(16, format.getBitsPerSample());
    }

    @Test
    public void everyFormatTheClientSupportsRoundTrips() throws Exception {
        int[] rates = {8000, 22050, 44100, 48000, 96000};
        int[] channels = {1, 2};
        int[] bits = {16, 24, 32};
        for (int rate : rates) {
            for (int c : channels) {
                for (int b : bits) {
                    CodecHeaderMessage m = CodecHeaderMessage.of(Codec.PCM, PcmWaveHeader.build(rate, c, b));
                    assertEquals(rate, m.getSampleRate());
                    assertEquals(c, m.getChannels());
                    assertEquals(b, m.getBitsPerSample());
                }
            }
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void aSampleSizeOfNoWholeBytesIsRefused() {
        PcmWaveHeader.build(48000, 2, 12);
    }

    @Test(expected = IllegalArgumentException.class)
    public void noChannelsIsRefused() {
        PcmWaveHeader.build(48000, 0, 16);
    }
}
