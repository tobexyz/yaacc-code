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
import android.view.View;
import android.widget.ImageView;
import android.widget.ListView;

import de.yaacc.R;
import de.yaacc.browser.BrowseItemAdapter;

/**
 * AsyncTask fpr retrieving icons while browsing.
 *
 * @author: Christoph Hähnel (eyeless)
 */
public class IconDownloadTask extends AsyncTask<Uri, Integer, Bitmap> {

    private BrowseItemAdapter browseItemAdapter;
    private ListView listView;
    private int position;
    private IconDownloadCacheHandler cache;

    /**
     * Initialize a new download by handing over the the list and the position
     * with the icon to download
     *
     * @param list     contains all item
     * @param position position in list
     */
    public IconDownloadTask(ListView list, int position) {
        this.listView = list;
        this.position = position;
        this.cache = IconDownloadCacheHandler.getInstance();
    }

    public IconDownloadTask(BrowseItemAdapter adapter, ListView list, int position) {
        this.listView = list;
        this.position = position;
        this.browseItemAdapter = adapter;
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


    public int getPosition() {
        return position;
    }

    /**
     * Replaces the icon in the list with the recently loaded icon
     *
     * @param result downloaded icon
     */
    @Override
    protected void onPostExecute(Bitmap result) {
        int visiblePosition = listView.getFirstVisiblePosition();
        View v = listView.getChildAt(position - visiblePosition);
        if (v != null && result != null) {
            ImageView c = v.findViewById(R.id.browseItemIcon);
            c.setImageBitmap(result);
        }
        if (browseItemAdapter != null) {
            browseItemAdapter.removeTask(this);
        }
    }


}