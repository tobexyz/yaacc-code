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
package de.yaacc.multiroom.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import de.yaacc.multiroom.protocol.PcmFormat;

import org.junit.Test;

public class PcmFrameDecoderTest {

    private static PcmFormat format(int rate, int channels, int bits) throws Exception {
        return PcmFormat.parse(ClientFixtures.wav(rate, channels, bits));
    }

    @Test
    public void wholeFramesAreConverted() throws Exception {
        PcmFrameDecoder d = new PcmFrameDecoder(format(48000, 2, 24), 1000);
        byte[] out = d.decode(new byte[6 * 4]); // 4 frames of stereo 24 bit
        assertEquals(4 * 2 * 2, out.length);
        assertEquals(0, d.droppedPartialBytes());
    }

    @Test
    public void aTrailingPartialFrameIsDroppedAndCounted() throws Exception {
        PcmFrameDecoder d = new PcmFrameDecoder(format(48000, 2, 16), 1000);
        assertEquals(3 * 4, d.decode(new byte[3 * 4 + 3]).length);
        assertEquals(3, d.droppedPartialBytes());
        assertEquals(0, d.decode(new byte[3]).length);
        assertEquals(6, d.droppedPartialBytes());
    }

    @Test
    public void aChunkAboveTheFrameLimitIsRefusedBeforeAnythingIsAllocatedFromIt() throws Exception {
        PcmFrameDecoder d = new PcmFrameDecoder(format(48000, 2, 16), 100);
        try {
            d.decode(new byte[101 * 4]);
            fail("oversized");
        } catch (DecoderException e) {
            assertEquals(DecoderException.Reason.OVERSIZED, e.getReason());
        }
        assertEquals(400, d.decode(new byte[400]).length);
    }

    @Test
    public void monoStaysMono() throws Exception {
        PcmFrameDecoder d = new PcmFrameDecoder(format(44100, 1, 32), 1000);
        assertEquals(10 * 2, d.decode(new byte[10 * 4]).length);
    }
}
