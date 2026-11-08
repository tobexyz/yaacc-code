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

/**
 * Provides cache functionality for Bitmap images in lists. Implemented as singleton to assure the
 * is always just one instance. Since there is always only one list shown at once there must not be
 * any other caches.
 *
 * @author Christoph Hähnel (eyeless)
 */
public class IconDownloadCacheHandler {
    private static IconDownloadCacheHandler instance;
    private volatile ByteLruCache<String, Bitmap> cache;

    private IconDownloadCacheHandler() {
        initializeCache();
    }

    /**
     * Provides access to the current instance.If none exists a new one is created.
     *
     * @return instance with empty cache
     */
    public static synchronized IconDownloadCacheHandler getInstance() {
        if (instance == null) {
            instance = new IconDownloadCacheHandler();
        }
        return instance;
    }

    /**
     * Loads image from cache
     *
     * @param uri uri the image is saved at
     * @return required image
     */
    public Bitmap getBitmap(Uri uri, int width, int height) {
        return cache.get(uri.toString() + width + "x" + height);
    }

    /**
     * Adds image to cache
     *
     * @param uri uri the image is saved at
     * @param img image to save
     */
    public void addBitmap(Uri uri, int width, int height, Bitmap img) {
        if (img == null) return;
        cache.put(uri.toString() + width + "x" + height, img);
    }

    /**
     * Clear the whole cache.
     */
    public void resetCache() {
        initializeCache();
    }

    /**
     * Initializes a new cache bounded in bytes (one sixteenth of the heap, 8 to 32 MB, see
     * {@link ByteLruCache#defaultMaxBytes(long)}). This cache replaces older caches if existing.
     */
    private void initializeCache() {
        cache = new ByteLruCache<>(ByteLruCache.defaultMaxBytes(Runtime.getRuntime().maxMemory()), Bitmap::getByteCount);
    }
}
