/*
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
package de.yaacc.util.image;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Reading an image from an address that comes from another device: it stops at a limit. */
public class BitmapSamplingReadTest {

    @Test
    public void aStreamWithinTheLimitIsReadCompletely() throws IOException {
        byte[] data = new byte[20_000];
        java.util.Arrays.fill(data, (byte) 7);

        assertArrayEquals(data, BitmapSampling.readAllBytes(new ByteArrayInputStream(data), 20_000));
        assertEquals(0, BitmapSampling.readAllBytes(new ByteArrayInputStream(new byte[0]), 10).length);
    }

    @Test
    public void aStreamOverTheLimitEndsInAnException() {
        try {
            BitmapSampling.readAllBytes(new ByteArrayInputStream(new byte[20_001]), 20_000);
            fail();
        } catch (IOException expected) {
            // too long
        }
    }

    @Test
    public void aStreamThatNeverEndsIsStoppedAtTheLimit() {
        InputStream endless = new InputStream() {
            @Override
            public int read() {
                return 1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                return len;
            }
        };
        try {
            BitmapSampling.readAllBytes(endless, 1_000_000);
            fail();
        } catch (IOException expected) {
            // too long
        }
    }
}
