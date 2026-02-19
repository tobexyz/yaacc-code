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
import android.net.Uri;
import android.os.AsyncTask;
import android.widget.ImageView;

import androidx.media3.ui.PlayerNotificationManager;

/**
 * AsyncTask fpr retrieving icons while browsing.
 *
 * @author Christoph Hähnel (eyeless)
 */
public class ImageDownloadTask extends AsyncTask<Uri, Integer, Bitmap> {


    private final ImageView imageView;
    private final PlayerNotificationManager.BitmapCallback bitmapCallback;
    private final IconDownloadCacheHandler cache;

    /**
     * Initialize a new download by handing over the the list where the image should be shown
     *
     * @param imageView contains the view
     */
    public ImageDownloadTask(ImageView imageView) {
        this.imageView = imageView;
        this.bitmapCallback = null;
        this.cache = IconDownloadCacheHandler.getInstance();
    }

    /**
     * Initialize a new download with a callback for notification manager
     *
     * @param callback bitmap callback
     */
    public ImageDownloadTask(PlayerNotificationManager.BitmapCallback callback) {
        this.imageView = null;
        this.bitmapCallback = callback;
        this.cache = IconDownloadCacheHandler.getInstance();
    }

    /**
     * Download image and convert it to icon
     *
     * @param uri uri of resource
     * @return icon
     */
    @Override
    protected Bitmap doInBackground(Uri... uri) {
        int width = imageView != null ? imageView.getWidth() : 512;
        int height = imageView != null ? imageView.getHeight() : 512;
        
        if (cache.getBitmap(uri[0], width, height) == null) {
            cache.addBitmap(uri[0], width, height, new ImageDownloader().retrieveImageWithCertainSize(uri[0], width, height));
        }

        return cache.getBitmap(uri[0], width, height);
    }

    /**
     * Replaces the icon in the list with the recently loaded icon
     *
     * @param result downloaded icon
     */
    @Override
    protected void onPostExecute(Bitmap result) {
        if (imageView != null) {
            imageView.setImageBitmap(result);
        } else if (bitmapCallback != null) {
            bitmapCallback.onBitmap(result);
        }
    }
}