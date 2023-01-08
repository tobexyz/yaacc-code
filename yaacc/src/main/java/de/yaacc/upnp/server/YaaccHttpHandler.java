/*
 *
 * Copyright (C) 2013 Tobias Schoene www.yaacc.de
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
package de.yaacc.upnp.server;

import android.annotation.SuppressLint;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import androidx.core.content.res.ResourcesCompat;

import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.MethodNotSupportedException;
import org.apache.hc.core5.http.io.HttpRequestHandler;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.FileEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.seamless.util.MimeType;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import de.yaacc.R;

/**
 * A http service to retrieve media content by an id.
 *
 * @author Tobias Schoene (tobexyz)
 */
public class YaaccHttpHandler implements HttpRequestHandler {

    private final Context context;

    public YaaccHttpHandler(Context context) {
        this.context = context;

    }


    @Override
    public void handle(ClassicHttpRequest request, ClassicHttpResponse response, HttpContext context) throws HttpException, IOException {
        Log.d(getClass().getName(), "Processing HTTP request: "
                + request.getRequestUri());

        // Extract what we need from the HTTP httpRequest
        String requestMethod = request.getMethod()
                .toUpperCase(Locale.ENGLISH);

        // Only accept HTTP-GET
        if (!requestMethod.equals("GET") && !requestMethod.equals("HEAD")) {
            Log.d(getClass().getName(),
                    "HTTP request isn't GET or HEAD stop! Method was: "
                            + requestMethod);
            throw new MethodNotSupportedException(requestMethod
                    + " method not supported");
        }

        Uri requestUri = Uri.parse(request.getRequestUri());
        String contentId = requestUri.getQueryParameter("id");
        contentId = contentId == null ? "" : contentId;
        String albumId = requestUri.getQueryParameter("album");
        albumId = albumId == null ? "" : albumId;
        String thumbId = requestUri.getQueryParameter("thumb");
        thumbId = thumbId == null ? "" : thumbId;
        if (contentId.equals("") && albumId.equals("") && thumbId.equals("")) {
            response.setCode(HttpStatus.SC_FORBIDDEN);
            StringEntity entity = new StringEntity(
                    "<html><body><h1>Access denied</h1></body></html>", StandardCharsets.UTF_8);
            response.setEntity(entity);
            Log.d(getClass().getName(), "end doService: Access denied");
            return;
        }
        ContentHolder contentHolder = null;
        if (!contentId.equals("")) {
            contentHolder = lookupContent(contentId);

        } else if (!albumId.equals("")) {
            contentHolder = lookupAlbumArt(albumId);
        } else if (!thumbId.equals("")) {
            contentHolder = lookupThumbnail(thumbId);
        }
        if (contentHolder == null) {
            // tricky but works
            Log.d(getClass().getName(), "Resource with id " + contentId
                    + albumId + thumbId + " not found");
            response.setCode(HttpStatus.SC_NOT_FOUND);
            StringEntity entity = new StringEntity(
                    "<html><body><h1>Resource with id " + contentId + albumId
                            + thumbId + " not found</h1></body></html>",
                    StandardCharsets.UTF_8);
            response.setEntity(entity);
        } else {

            response.setCode(HttpStatus.SC_OK);
            response.setEntity(contentHolder.getHttpEntity());
        }
        Log.d(getClass().getName(), "end doService: ");
    }

    private Context getContext() {
        return context;
    }

    /**
     * Lookup content in the mediastore
     *
     * @param contentId the id of the content
     * @return the content description
     */
    private ContentHolder lookupContent(String contentId) {
        ContentHolder result = null;
        if (contentId == null) {
            return null;
        }
        Log.d(getClass().getName(), "System media store lookup: " + contentId);
        String[] projection = {MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.DATA};
        String selection = MediaStore.Files.FileColumns._ID + "=?";
        String[] selectionArgs = {contentId};
        try (Cursor mFilesCursor = getContext().getContentResolver().query(
                MediaStore.Files.getContentUri("external"), projection,
                selection, selectionArgs, null)) {

            if (mFilesCursor != null) {
                mFilesCursor.moveToFirst();
                while (!mFilesCursor.isAfterLast()) {
                    @SuppressLint("Range") String dataUri = mFilesCursor.getString(mFilesCursor
                            .getColumnIndex(MediaStore.Files.FileColumns.DATA));

                    @SuppressLint("Range") String mimeTypeStr = mFilesCursor
                            .getString(mFilesCursor
                                    .getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE));
                    MimeType mimeType = MimeType.valueOf("*/*");
                    if (mimeTypeStr != null) {
                        mimeType = MimeType.valueOf(mimeTypeStr);
                    }
                    Log.d(getClass().getName(), "Content found: " + mimeType
                            + " Uri: " + dataUri);
                    result = new ContentHolder(mimeType, dataUri);
                    mFilesCursor.moveToNext();
                }
            } else {
                Log.d(getClass().getName(), "System media store is empty.");
            }
        }

        return result;

    }

    /**
     * Lookup content in the mediastore
     *
     * @param albumId the id of the album
     * @return the content description
     */
    private ContentHolder lookupAlbumArt(String albumId) {

        ContentHolder result = new ContentHolder(MimeType.valueOf("image/png"),
                getDefaultIcon());
        if (albumId == null) {
            return null;
        }
        Log.d(getClass().getName(), "System media store lookup album: "
                + albumId);
        String[] projection = {MediaStore.Audio.Albums._ID,
                // FIXME what is the right mime type?
                // MediaStore.Audio.Albums.MIME_TYPE,
                MediaStore.Audio.Albums.ALBUM_ART};
        String selection = MediaStore.Audio.Albums._ID + "=?";
        String[] selectionArgs = {albumId};
        try (Cursor cursor = getContext().getContentResolver().query(
                MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, projection,
                selection, selectionArgs, null)) {

            if (cursor != null) {
                cursor.moveToFirst();
                while (!cursor.isAfterLast()) {
                    @SuppressLint("Range") String dataUri = cursor.getString(cursor
                            .getColumnIndex(MediaStore.Audio.Albums.ALBUM_ART));

                    // String mimeTypeStr = null;
                    // FIXME mime type resolving cursor
                    // .getString(cursor
                    // .getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE));

                    MimeType mimeType = MimeType.valueOf("image/png");
                    // if (mimeTypeStr != null) {
                    // mimeType = MimeType.valueOf(mimeTypeStr);
                    // }
                    if (dataUri != null) {
                        Log.d(getClass().getName(), "Content found: " + mimeType
                                + " Uri: " + dataUri);
                        result = new ContentHolder(mimeType, dataUri);
                    }
                    cursor.moveToNext();
                }
            } else {
                Log.d(getClass().getName(), "System media store is empty.");
            }
        }
        return result;
    }

    /**
     * Lookup a thumbnail content in the mediastore
     *
     * @param idStr the id of the thumbnail
     * @return the content description
     */
    private ContentHolder lookupThumbnail(String idStr) {

        ContentHolder result = new ContentHolder(MimeType.valueOf("image/png"),
                getDefaultIcon());
        if (idStr == null) {
            return null;
        }
        Long id;
        try {
            id = Long.valueOf(idStr);
        } catch (NumberFormatException nfe) {
            Log.d(getClass().getName(), "ParsingError of id: " + idStr, nfe);
            return null;
        }

        Log.d(getClass().getName(), "System media store lookup thumbnail: "
                + idStr);
        Bitmap bitmap = MediaStore.Images.Thumbnails.getThumbnail(getContext()
                        .getContentResolver(), id,
                MediaStore.Images.Thumbnails.MINI_KIND, null);
        if (bitmap != null) {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            byte[] byteArray = stream.toByteArray();

            MimeType mimeType = MimeType.valueOf("image/png");

            result = new ContentHolder(mimeType, byteArray);

        } else {
            Log.d(getClass().getName(), "System media store is empty.");
        }
        return result;
    }

    private byte[] getDefaultIcon() {
        Drawable drawable = ResourcesCompat.getDrawable(getContext().getResources(),
                R.drawable.yaacc192_32, getContext().getTheme());
        byte[] result = null;
        if (drawable != null) {
            Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            result = stream.toByteArray();
        }
        return result;
    }


    /**
     * ValueHolder for media content.
     */
    static class ContentHolder {
        private final MimeType mimeType;
        private String uri;
        private byte[] content;

        public ContentHolder(MimeType mimeType, String uri) {
            this.uri = uri;
            this.mimeType = mimeType;

        }

        public ContentHolder(MimeType mimeType, byte[] content) {
            this.content = content;
            this.mimeType = mimeType;

        }

        /**
         * @return the uri
         */
        public String getUri() {
            return uri;
        }

        /**
         * @return the mimeType
         */
        public MimeType getMimeType() {
            return mimeType;
        }

        public HttpEntity getHttpEntity() {
            HttpEntity result = null;
            if (getUri() != null && !getUri().equals("")) {
                File file = new File(getUri());

                result = new FileEntity(file, ContentType.parse(getMimeType().toString()));
                Log.d(getClass().getName(), "Return file-Uri: " + getUri()
                        + "Mimetype: " + getMimeType());
            } else if (content != null) {
                result = new ByteArrayEntity(content, ContentType.parse(getMimeType().toString()));
            }
            return result;
        }
    }
}
