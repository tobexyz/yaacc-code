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

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

import de.yaacc.sendspin.control.ArtworkEncoder;
import de.yaacc.sendspin.session.ArtworkLayout;

/**
 * The artwork images of the server (issue #325, K15) made with the platform's bitmaps: the cover is fetched (http or https, no
 * redirect, a time limit and a size cap), decoded at about the size that is needed, scaled to fit the channel keeping its aspect ratio,
 * centred on black and encoded as JPEG or PNG. The last few results are kept, because several controllers ask for the same cover.
 */
final class BitmapArtworkEncoder implements ArtworkEncoder {

    private static final int CONNECT_TIMEOUT_MILLIS = 4000;
    private static final int READ_TIMEOUT_MILLIS = 6000;
    /** The largest cover that is read: far above any real one, far below what could exhaust the memory. */
    private static final int MAX_SOURCE_BYTES = 8 * 1024 * 1024;
    private static final int JPEG_QUALITY = 85;
    private static final int CACHE_ENTRIES = 4;

    private final Map<String, byte[]> cache = new LinkedHashMap<String, byte[]>(CACHE_ENTRIES + 1, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > CACHE_ENTRIES;
        }
    };

    @Override
    public byte[] encode(String url, String format, int width, int height) throws IOException {
        String key = format + " " + width + "x" + height + " " + url;
        synchronized (cache) {
            byte[] hit = cache.get(key);
            if (hit != null) {
                return hit;
            }
        }
        byte[] source = fetch(url);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(source, 0, source.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null; // not an image
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, width, height);
        Bitmap decoded = BitmapFactory.decodeByteArray(source, 0, source.length, options);
        if (decoded == null) {
            return null;
        }
        Bitmap out = null;
        try {
            ArtworkLayout layout = ArtworkLayout.fit(decoded.getWidth(), decoded.getHeight(), width, height);
            out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            canvas.drawColor(Color.BLACK);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(decoded, null, new Rect(layout.x, layout.y, layout.x + layout.width, layout.y + layout.height), paint);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            boolean png = "png".equals(format);
            if (!out.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bytes)) {
                return null;
            }
            byte[] image = bytes.toByteArray();
            synchronized (cache) {
                cache.put(key, image);
            }
            return image;
        } finally {
            decoded.recycle();
            if (out != null) {
                out.recycle();
            }
        }
    }

    /** The largest power of two that keeps the decoded image at least as large as the target in both directions. */
    static int sampleSize(int sourceWidth, int sourceHeight, int targetWidth, int targetHeight) {
        int sample = 1;
        while (sourceWidth / (sample * 2) >= targetWidth && sourceHeight / (sample * 2) >= targetHeight) {
            sample *= 2;
        }
        return sample;
    }

    private static byte[] fetch(String address) throws IOException {
        URL url = new URL(address);
        String protocol = url.getProtocol();
        if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
            throw new IOException("not http");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            connection.setInstanceFollowRedirects(false); // a cover is not behind a redirect, and a redirect could point anywhere
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("status");
            }
            long declared = connection.getContentLengthLong();
            if (declared > MAX_SOURCE_BYTES) {
                throw new IOException("too big");
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    if (out.size() + read > MAX_SOURCE_BYTES) {
                        throw new IOException("too big");
                    }
                    out.write(buffer, 0, read);
                }
                return out.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }
}
