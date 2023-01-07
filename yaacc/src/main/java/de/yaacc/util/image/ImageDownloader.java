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
import android.util.Log;

import java.io.FileNotFoundException;
import java.io.FilterInputStream;
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
     * @return
     */
    public Bitmap retrieveImageWithCertainSize(Uri imageUri, int imageWidth, int imageHeight) {
        Log.d(getClass().getName(), "retrieveImage size:" + imageWidth + "x" + imageHeight);
        Bitmap result = decodeSampledBitmapFromStream(imageUri, imageWidth, imageHeight, true);
        return result;
    }


    /**
     * Loads an image from the given URI and return a Bitmap that matches the requested size
     *
     * @param imageUri  image location
     * @param reqWidth  width of result image
     * @param reqHeight height of result image
     * @return requested image
     * @throws IOException problem while loading image from stream
     */
    private Bitmap decodeSampledBitmapFromStream(Uri imageUri, int reqWidth,
                                                 int reqHeight, boolean rescaleImage) {

        Bitmap bitmap = null;

        try {
            InputStream is = getUriAsStream(imageUri);


            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = false;
            options.outWidth = reqWidth;
            options.outHeight = reqHeight;
            options.inPreferQualityOverSpeed = false;
            options.inDensity = DisplayMetrics.DENSITY_DEFAULT;
            options.inTempStorage = new byte[7680016];

            Log.d(this.getClass().getName(),
                    "displaying image size width, height, inSampleSize "
                            + options.outWidth + "," + options.outHeight + ","
                            + options.inSampleSize);
            Log.d(this.getClass().getName(), "free memory before image load: "
                    + Runtime.getRuntime().freeMemory());


            bitmap = BitmapFactory.decodeStream(new FlushedInputStream(is),
                    null, options);

            // if the image must be rescaled its ratio must be recalculated
            if (rescaleImage) {
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
            }
            Log.d(this.getClass().getName(), "free memory after image load: "
                    + Runtime.getRuntime().freeMemory());

            if (bitmap.getHeight() != reqHeight) {
                Log.w(this.getClass().getName(), "Bitmap has wrong size !!! height: " + bitmap.getHeight() + " width: " + bitmap.getWidth());
            }

        } catch (Exception e) {
            Log.d(this.getClass().getName(), "while decoding image: " + e.getMessage());
        }

        return bitmap;
    }

    /**
     * Converts URI to InputStream.
     *
     * @param imageUri
     * @return
     * @throws FileNotFoundException
     * @throws IOException
     * @throws MalformedURLException
     */
    private InputStream getUriAsStream(Uri imageUri)
            throws FileNotFoundException, IOException, MalformedURLException {
        InputStream is = null;
        Log.d(getClass().getName(), "Start load: " + System.currentTimeMillis());

        is = (InputStream) new java.net.URL(imageUri.toString())
                .getContent();
        Log.d(getClass().getName(), "Stop load: " + System.currentTimeMillis());
        Log.d(getClass().getName(), "InputStream: " + is);
        return is;
    }

    static class FlushedInputStream extends FilterInputStream {
        public FlushedInputStream(InputStream inputStream) {
            super(inputStream);
        }

        @Override
        public long skip(long n) throws IOException {
            long totalBytesSkipped = 0L;
            while (totalBytesSkipped < n) {
                long bytesSkipped = in.skip(n - totalBytesSkipped);
                if (bytesSkipped == 0L) {
                    int byte_ = read();
                    if (byte_ < 0) {
                        break; // we reached EOF
                    } else {
                        bytesSkipped = 1; // we read one byte
                    }
                }
                totalBytesSkipped += bytesSkipped;
            }
            return totalBytesSkipped;
        }
    }


}
