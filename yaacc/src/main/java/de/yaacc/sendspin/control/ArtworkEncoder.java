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
package de.yaacc.sendspin.control;

import java.io.IOException;

/**
 * Makes the image for an artwork channel (issue #325, K15): the cover at an address, scaled to fit the declared width and height,
 * centred on black, encoded as JPEG or PNG (see {@code ArtworkLayout}). The Android side does the bitmap work; the tests use a fake.
 */
public interface ArtworkEncoder {

    /**
     * @param format "jpeg" or "png"
     * @return the encoded image of exactly {@code width} by {@code height}, or null if there is none (not reachable, not an image, too
     * big); may block, so it is called off the connection's thread
     * @throws IOException if the cover could not be read
     */
    byte[] encode(String url, String format, int width, int height) throws IOException;
}
