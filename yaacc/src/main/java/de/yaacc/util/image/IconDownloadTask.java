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

import de.yaacc.browser.BrowseContentItemAdapter;

/**
 * AsyncTask fpr retrieving icons while browsing.
 *
 * @author Christoph Hähnel (eyeless)
 */
public class IconDownloadTask extends AsyncTask<Uri, Integer, Bitmap> {

    private final ImageView imageView;
    private final IconDownloadCacheHandler cache;
    private final BrowseContentItemAdapter browseContentItemAdapter;


    public IconDownloadTask(ImageView imageView) {
        this.imageView = imageView;
        this.browseContentItemAdapter = null;
        this.cache = IconDownloadCacheHandler.getInstance();
    }

    public IconDownloadTask(ImageView imageView, BrowseContentItemAdapter browseContentItemAdapter) {
        this.imageView = imageView;
        this.cache = IconDownloadCacheHandler.getInstance();
        this.browseContentItemAdapter = browseContentItemAdapter;
    }


    /**
     * Download image and convert it to icon
     *
     * @param uri uri of resource
     * @return icon
     */
    @Override
    protected Bitmap doInBackground(Uri... uri) {
        int defaultHeight = 48;
        int defaultWidth = 48;
        Bitmap result = null;
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
        imageView.setImageBitmap(result);
        if (browseContentItemAdapter != null) {
            browseContentItemAdapter.removeTask(this);
        }
    }


}