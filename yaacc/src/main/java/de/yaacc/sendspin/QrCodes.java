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

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/**
 * The QR code of a pairing token (issue #325): the Sendspin specification renders a token as a QR code that carries the token
 * verbatim, with no URI scheme. The token is upper case letters and digits, so the code is small (alphanumeric mode).
 */
public final class QrCodes {
    /** The modules around the code that a scanner needs as a quiet zone, as the QR standard says. */
    public static final int QUIET_ZONE = 4;

    private QrCodes() {
    }

    /**
     * The dark modules of the code of the text, without the quiet zone.
     *
     * @return {@code [row][column]}, true for a dark module
     * @throws IllegalArgumentException if the text does not fit into a code
     */
    public static boolean[][] modules(String text) {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 0);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        BitMatrix matrix;
        try {
            matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints);
        } catch (WriterException e) {
            throw new IllegalArgumentException("text does not fit into a QR code", e);
        }
        boolean[][] result = new boolean[matrix.getHeight()][matrix.getWidth()];
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                result[y][x] = matrix.get(x, y);
            }
        }
        return result;
    }

    /**
     * Pixels of the code with its quiet zone: white 0xFFFFFFFF and black 0xFF000000, {@code size} by {@code size} where
     * {@code size = (modules + 2 * QUIET_ZONE) * scale}.
     */
    public static int[] pixels(boolean[][] modules, int scale) {
        int n = modules.length;
        int size = (n + 2 * QUIET_ZONE) * scale;
        int[] pixels = new int[size * size];
        java.util.Arrays.fill(pixels, 0xFFFFFFFF);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if (!modules[y][x]) {
                    continue;
                }
                for (int dy = 0; dy < scale; dy++) {
                    int row = ((y + QUIET_ZONE) * scale + dy) * size + (x + QUIET_ZONE) * scale;
                    for (int dx = 0; dx < scale; dx++) {
                        pixels[row + dx] = 0xFF000000;
                    }
                }
            }
        }
        return pixels;
    }

    /** The side in pixels of {@link #pixels} for a code of this many modules. */
    public static int sizeOf(int modules, int scale) {
        return (modules + 2 * QUIET_ZONE) * scale;
    }
}
