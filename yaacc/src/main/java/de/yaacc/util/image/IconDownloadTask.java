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

/**
 * AsyncTask fpr retrieving icons while browsing.
 *
 * @author Christoph Hähnel (eyeless)
 */
public class IconDownloadTask extends AsyncTask<Uri, Integer, Bitmap> {

    private final ImageView imageView;
    private final IconDownloadCacheHandler cache;
    private final Listener listener;
    private final String deviceId;
    private final int targetWidth;
    private final int targetHeight;


    public IconDownloadTask(ImageView imageView) {
        this(imageView, 48, 48);
    }

    public IconDownloadTask(ImageView imageView, String deviceId) {
        this.imageView = imageView;
        this.cache = IconDownloadCacheHandler.getInstance();
        this.listener = null;
        this.deviceId = deviceId;
        this.targetWidth = 48;
        this.targetHeight = 48;
    }
    
    public IconDownloadTask(ImageView imageView, int width, int height) {
        this.imageView = imageView;
        this.cache = IconDownloadCacheHandler.getInstance();
        this.listener = null;
        this.deviceId = null;
        this.targetWidth = width;
        this.targetHeight = height;
    }

    /**
     * Tells the owner of the row that the load is over, on the main thread, whether it delivered, failed or was
     * cancelled (a cancelled task never reaches {@link #onPostExecute}), so the owner can forget the task.
     */
    public interface Listener {
        /**
         * @param task       the task that ended
         * @param imageShown true if its bitmap was set on the view
         */
        void onFinished(IconDownloadTask task, boolean imageShown);
    }

    /**
     * For a recycled row: the bitmap is only set while the view's tag still equals {@code requestKey} (the row
     * was not bound to something else meanwhile), and {@code listener} is told when the task ends, however it
     * ends.
     */
    public IconDownloadTask(ImageView imageView, String requestKey, int width, int height, Listener listener) {
        this.imageView = imageView;
        this.cache = IconDownloadCacheHandler.getInstance();
        this.listener = listener;
        this.deviceId = requestKey;
        this.targetWidth = width;
        this.targetHeight = height;
    }


    /**
     * Download image and convert it to icon
     *
     * @param uri uri of resource
     * @return icon
     */
    @Override
    protected Bitmap doInBackground(Uri... uri) {
        int defaultHeight = targetHeight;
        int defaultWidth = targetWidth;
        Bitmap result = null;
        if (isCancelled()) {
            return null; // the row was recycled while the task waited in the queue
        }
        if (cache != null) {
            result = cache.getBitmap(uri[0], defaultHeight, defaultWidth);
        }
        if (result == null) {
            result = new ImageDownloader().retrieveImageWithCertainSize(uri[0], defaultHeight, defaultWidth);
            if (result != null) {
                if (cache != null) {
                    cache.addBitmap(uri[0], defaultHeight, defaultWidth, result);
                }
            }
        }
        return result;
    }


    /**
     * Replaces the icon in the list with the recently loaded icon
     *
     * @param result downloaded icon
     */
    @Override
    protected void onPostExecute(Bitmap result) {
        boolean shown = false;
        // Check if ImageView still belongs to the same device
        if (deviceId == null || deviceId.equals(imageView.getTag())) {
            if (result != null) {
                imageView.setVisibility(android.view.View.VISIBLE);
                imageView.setImageBitmap(result);
                shown = true;
            }
        }
        // else: ImageView has been recycled for a different device
        if (listener != null) {
            listener.onFinished(this, shown);
        }
    }

    @Override
    protected void onCancelled(Bitmap result) {
        if (listener != null) {
            listener.onFinished(this, false);
        }
    }


}
