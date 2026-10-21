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
package de.yaacc.util.image;

import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Shared helpers for decoding a bitmap downsampled to a target size instead
 * of at native resolution (issue #285 - a high-resolution source image
 * decoded at full size, then scaled down afterward, allocates and decodes
 * every pixel of the original for no benefit once it's about to be shrunk).
 * Used by both the full-screen image viewer ({@code ImageFragment}) and the
 * thumbnail/icon loading path ({@code ImageDownloader}), which independently
 * had the same underlying flaw.
 */
public final class BitmapSampling {

    private BitmapSampling() {
    }

    /**
     * Reads an entire stream into memory. Decoding a bitmap downsampled
     * (see {@link #computeInSampleSize}) needs two passes over the same
     * image data (a bounds-only pass, then the real decode), and most
     * sources here (network streams in particular) can't be rewound/read
     * twice - buffering the compressed bytes once is far cheaper than the
     * decoded bitmap they produce, so this is a net memory reduction
     * versus decoding directly from a live stream, not a cost.
     */
    public static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int bytesRead;
        while ((bytesRead = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, bytesRead);
        }
        return buffer.toByteArray();
    }

    /**
     * The standard Android pattern for sizing a downsampled decode: the
     * smallest power-of-two {@code inSampleSize} whose resulting dimensions
     * still cover the requested size. {@code options} must already have
     * been populated by a bounds-only decode ({@code inJustDecodeBounds =
     * true}) of the same image. See "Loading Large Bitmaps Efficiently" in
     * the Android developer guide.
     *
     * <p>{@code reqWidth}/{@code reqHeight} of 0 or less (e.g. a caller
     * passing an unmeasured {@code View}'s {@code getWidth()}/{@code
     * getHeight()}) are clamped up to 1: with an unclamped 0, the loop's
     * {@code >= 0} condition is unconditionally true for any non-negative
     * {@code inSampleSize}, so it never terminates naturally and just
     * doubles {@code inSampleSize} until it overflows - a last-resort
     * safety net, not a substitute for callers passing a real target size
     * (the resulting bitmap would still be near-unusably tiny, just not a
     * crash).
     */
    public static int computeInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        int height = options.outHeight;
        int width = options.outWidth;
        int safeReqWidth = Math.max(reqWidth, 1);
        int safeReqHeight = Math.max(reqHeight, 1);
        int inSampleSize = 1;
        if (height > safeReqHeight || width > safeReqWidth) {
            int halfHeight = height / 2;
            int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= safeReqHeight && (halfWidth / inSampleSize) >= safeReqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    /**
     * Like {@link #computeInSampleSize}, but for capping a maximum dimension
     * rather than guaranteeing a minimum one - these are NOT interchangeable.
     * {@code computeInSampleSize}'s loop condition is AND: it keeps
     * downsampling only while BOTH halved dimensions still exceed the
     * target, which is correct when the target is a small view size you
     * want the decode to at least cover (the view crops/scales the rest).
     * Capping a maximum dimension needs the opposite - keep downsampling
     * while EITHER dimension still exceeds the cap - otherwise a
     * non-square image whose *shorter* side already happens to be within
     * roughly 2x of the cap stops the loop immediately regardless of how
     * far the *longer* side overshoots it, leaving the image undownsampled
     * (this was issue #285's full-screen-viewer fix initially reusing
     * {@code computeInSampleSize} for this purpose - for an 8160x6144
     * photo capped at 4096, height/2 (3072) is already below 4096, so the
     * AND condition is false from the first check and inSampleSize never
     * advances past 1, reproducing the original full-resolution-decode
     * crash for any photo with a side in (cap, 2*cap) - the common case
     * for modern phone cameras, not an edge case).
     */
    public static int computeInSampleSizeForMaxDimension(BitmapFactory.Options options, int maxDimension) {
        int width = options.outWidth;
        int height = options.outHeight;
        int safeMax = Math.max(maxDimension, 1);
        int inSampleSize = 1;
        while ((width / inSampleSize) > safeMax || (height / inSampleSize) > safeMax) {
            inSampleSize *= 2;
        }
        return inSampleSize;
    }
}
