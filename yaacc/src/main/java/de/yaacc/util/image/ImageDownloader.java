/*
 *
 * Copyright (C) 2014 Tobias Schoene www.yaacc.de
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

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.DisplayMetrics;
import de.yaacc.util.YaaccLogger;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;

/**
 * Manages downloading of images from various sources and some refactoring to these images.
 *
 * @author Christoph Hähnel (eyeless)
 */
public class ImageDownloader {

    public ImageDownloader() {
    }


    /**
     * Loads the handed image location with the given size
     *
     * @param imageUri image location
     * @return image
     */
    public Bitmap retrieveImageWithCertainSize(Uri imageUri, int imageWidth, int imageHeight) {
        YaaccLogger.d(getClass().getName(), "retrieveImage size:" + imageWidth + "x" + imageHeight);
        return decodeSampledBitmapFromStream(imageUri, imageWidth, imageHeight);
    }


    /**
     * Loads an image from the given URI and return a Bitmap that matches the
     * requested size. Downsamples during decode (issue #285) rather than
     * decoding at native resolution and scaling down afterward: for a
     * high-resolution source requested at a small size (a 50MP photo's
     * thumbnail, say), the previous implementation here allocated and
     * decoded the full-resolution bitmap regardless -
     * {@code options.inSampleSize} was hardcoded to {@code 1}, and the
     * {@code options.outWidth}/{@code outHeight} assignments below did
     * nothing (those are {@link BitmapFactory} *output* fields, populated
     * by a bounds-only decode - setting them before a real decode has no
     * effect on it). See {@code ImageFragment} (the full-screen image
     * viewer) for the same fix applied to the same underlying bug.
     *
     * @param imageUri  image location
     * @param reqWidth  width of result image
     * @param reqHeight height of result image
     * @return requested image
     */
    private Bitmap decodeSampledBitmapFromStream(Uri imageUri, int reqWidth,
                                                 int reqHeight) {

        Bitmap bitmap = null;

        try {
            YaaccLogger.d(this.getClass().getName(), "image uri to load: " + imageUri.toString());
            byte[] imageBytes = BitmapSampling.readAllBytes(getUriAsStream(imageUri));

            BitmapFactory.Options boundsOptions = new BitmapFactory.Options();
            boundsOptions.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, boundsOptions);

            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inDensity = DisplayMetrics.DENSITY_DEFAULT;
            options.inSampleSize = BitmapSampling.computeInSampleSize(boundsOptions, reqWidth, reqHeight);

            YaaccLogger.d(this.getClass().getName(),
                    "displaying image size width, height, inSampleSize "
                            + reqWidth + "," + reqHeight + ","
                            + options.inSampleSize);
            YaaccLogger.d(this.getClass().getName(), "free memory before image load: "
                    + Runtime.getRuntime().freeMemory());


            bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, options);
            YaaccLogger.d(this.getClass().getName(), "free memory after image load: "
                    + Runtime.getRuntime().freeMemory());


            // The sampled decode above only gets within a power-of-two of
            // the requested size - rescale to the exact requested
            // dimensions (aspect-ratio-preserving), same as before.
            if (bitmap != null) {
                int outWidth;
                int outHeight;
                int inWidth = bitmap.getWidth();
                int inHeight = bitmap.getHeight();
                if (inWidth > inHeight) {
                    outWidth = reqWidth;
                    outHeight = (inHeight * reqWidth) / inWidth;
                } else {
                    outWidth = (inWidth * reqHeight) / inHeight;
                    outHeight = reqHeight;
                }
                bitmap = Bitmap.createScaledBitmap(bitmap, outWidth, outHeight, false);
                YaaccLogger.d(this.getClass().getName(), "free memory after image scaling: "
                        + Runtime.getRuntime().freeMemory());

            }
            if (bitmap == null) {
                YaaccLogger.w(this.getClass().getName(), "Bitmap is null !!!");
            } else if (bitmap.getHeight() != reqHeight) {
                YaaccLogger.w(this.getClass().getName(), "Bitmap has wrong size !!! height: " + bitmap.getHeight() + " width: " + bitmap.getWidth());
            }

        } catch (Exception e) {
            YaaccLogger.d(this.getClass().getName(), "while decoding image: " + e.getMessage());
        }

        return bitmap;
    }

    /**
     * Converts URI to InputStream.
     *
     * @param imageUri the image uri
     * @return the stream
     * @throws IOException           an exception
     * @throws MalformedURLException an exception
     */
    private InputStream getUriAsStream(Uri imageUri)
            throws IOException, MalformedURLException {
        InputStream is;
        YaaccLogger.d(getClass().getName(), "Start load: " + System.currentTimeMillis());

        is = (InputStream) new java.net.URL(imageUri.toString())
                .getContent();
        YaaccLogger.d(getClass().getName(), "Stop load: " + System.currentTimeMillis());
        YaaccLogger.d(getClass().getName(), "InputStream: " + is);
        return is;
    }

}
