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
package de.yaacc.upnp.server.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.zip.Inflater;

/**
 * The cover that the server sends for a music track whose album has none (issue 296): a small PNG shipped in {@code res/raw},
 * not the app logo. Plain JVM (the unit test classpath has no java.awt): the file is read from the source tree and decoded by hand
 * (8 bit RGBA, no interlace, as the file is written).
 */
public class GenericMusicIconResourceTest {

    private static final File ICON = new File("src/main/res/raw/album_art_music_generic.png");

    /** The decoded image: RGBA, 4 bytes per pixel. */
    private static final class Png {
        final int width;
        final int height;
        final byte[] rgba;

        Png(int width, int height, byte[] rgba) {
            this.width = width;
            this.height = height;
            this.rgba = rgba;
        }

        int channel(int x, int y, int channel) {
            return rgba[(y * width + x) * 4 + channel] & 0xff;
        }
    }

    private static Png read() throws Exception {
        ByteBuffer file = ByteBuffer.wrap(Files.readAllBytes(ICON.toPath()));
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a};
        for (byte expected : signature) {
            assertEquals("PNG signature", expected, file.get());
        }
        int width = 0;
        int height = 0;
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        while (file.remaining() >= 12) {
            int length = file.getInt();
            byte[] type = new byte[4];
            file.get(type);
            String name = new String(type, "US-ASCII");
            byte[] body = new byte[length];
            file.get(body);
            file.getInt(); // crc
            if (name.equals("IHDR")) {
                ByteBuffer header = ByteBuffer.wrap(body);
                width = header.getInt();
                height = header.getInt();
                assertEquals("8 bit", 8, header.get());
                assertEquals("color type 6: RGBA", 6, header.get());
                header.get();
                header.get();
                assertEquals("not interlaced", 0, header.get());
            } else if (name.equals("IDAT")) {
                data.write(body);
            }
        }
        int stride = width * 4;
        byte[] raw = new byte[(stride + 1) * height];
        Inflater inflater = new Inflater();
        inflater.setInput(data.toByteArray());
        int filled = 0;
        while (filled < raw.length && !inflater.finished()) {
            filled += inflater.inflate(raw, filled, raw.length - filled);
        }
        assertEquals("all scanlines", raw.length, filled);
        byte[] rgba = new byte[stride * height];
        for (int y = 0; y < height; y++) {
            int filter = raw[y * (stride + 1)];
            for (int i = 0; i < stride; i++) {
                int x = raw[y * (stride + 1) + 1 + i] & 0xff;
                int left = i >= 4 ? rgba[y * stride + i - 4] & 0xff : 0;
                int up = y > 0 ? rgba[(y - 1) * stride + i] & 0xff : 0;
                int upLeft = (y > 0 && i >= 4) ? rgba[(y - 1) * stride + i - 4] & 0xff : 0;
                int value;
                switch (filter) {
                    case 0: value = x; break;
                    case 1: value = x + left; break;
                    case 2: value = x + up; break;
                    case 3: value = x + ((left + up) / 2); break;
                    case 4: {
                        int p = left + up - upLeft;
                        int pa = Math.abs(p - left);
                        int pb = Math.abs(p - up);
                        int pc = Math.abs(p - upLeft);
                        value = x + ((pa <= pb && pa <= pc) ? left : (pb <= pc ? up : upLeft));
                        break;
                    }
                    default: throw new IllegalStateException("filter " + filter);
                }
                rgba[y * stride + i] = (byte) value;
            }
        }
        return new Png(width, height, rgba);
    }

    @Test
    public void theIconIsASmallSquareTransparentPngWithAGlyphInTheMiddle() throws Exception {
        assertTrue("the file is in the source tree: " + ICON.getAbsolutePath(), ICON.isFile());
        assertTrue("small enough to be cached by every control point", ICON.length() < 32 * 1024);
        Png image = read();

        assertEquals(512, image.width);
        assertEquals(512, image.height);
        assertEquals("transparent around the glyph, so that it shows on a light and a dark page", 0, image.channel(2, 2, 3));
        assertEquals("transparent in the other corner too", 0, image.channel(509, 509, 3));
        assertEquals("the glyph (the head of the note) is opaque", 255, image.channel(226, 316, 3));
    }

    @Test
    public void theGlyphIsNeutralGrayNotTheColorOfTheAppLogo() throws Exception {
        Png image = read();
        int red = image.channel(226, 316, 0);
        int green = image.channel(226, 316, 1);
        int blue = image.channel(226, 316, 2);

        assertTrue("gray: " + red + "," + green + "," + blue, Math.abs(red - green) < 8 && Math.abs(green - blue) < 8);
        assertTrue("neither black nor white", red > 60 && red < 200);
    }
}
