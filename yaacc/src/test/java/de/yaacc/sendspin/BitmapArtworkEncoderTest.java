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
package de.yaacc.sendspin;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class BitmapArtworkEncoderTest {

    @Test
    public void theDecodedImageStaysAtLeastAsLargeAsTheChannel() {
        assertEquals(1, BitmapArtworkEncoder.sampleSize(300, 300, 300, 300));
        assertEquals(1, BitmapArtworkEncoder.sampleSize(500, 500, 300, 300)); // halving would be 250: too small
        assertEquals(2, BitmapArtworkEncoder.sampleSize(600, 600, 300, 300));
        assertEquals(4, BitmapArtworkEncoder.sampleSize(3000, 2000, 400, 300));
        assertEquals("the narrow side decides", 1, BitmapArtworkEncoder.sampleSize(4000, 100, 400, 100));
    }
}
