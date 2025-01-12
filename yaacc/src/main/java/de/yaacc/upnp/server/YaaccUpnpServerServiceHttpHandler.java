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
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import androidx.core.content.res.ResourcesCompat;
import androidx.preference.PreferenceManager;

import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.Message;
import org.apache.hc.core5.http.MethodNotSupportedException;
import org.apache.hc.core5.http.nio.AsyncEntityProducer;
import org.apache.hc.core5.http.nio.AsyncRequestConsumer;
import org.apache.hc.core5.http.nio.AsyncServerRequestHandler;
import org.apache.hc.core5.http.nio.StreamChannel;
import org.apache.hc.core5.http.nio.entity.AbstractBinAsyncEntityProducer;
import org.apache.hc.core5.http.nio.entity.AsyncEntityProducers;
import org.apache.hc.core5.http.nio.entity.BasicAsyncEntityConsumer;
import org.apache.hc.core5.http.nio.support.AsyncResponseBuilder;
import org.apache.hc.core5.http.nio.support.BasicRequestConsumer;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.seamless.util.MimeType;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import de.yaacc.R;

/**
 * A http service to retrieve media content by an id.
 *
 * @author Tobias Schoene (tobexyz)
 */
public class YaaccUpnpServerServiceHttpHandler implements AsyncServerRequestHandler<Message<HttpRequest, byte[]>> {

    private final Context context;

    public YaaccUpnpServerServiceHttpHandler(Context context) {
        this.context = context;

    }


    @Override
    public AsyncRequestConsumer<Message<HttpRequest, byte[]>> prepare(HttpRequest request, EntityDetails entityDetails, HttpContext context) {
        return new BasicRequestConsumer<>(entityDetails != null ? new BasicAsyncEntityConsumer() : null);
    }

    @Override
    public void handle(final Message<HttpRequest, byte[]> request,
                       final ResponseTrigger responseTrigger,
                       final HttpContext context) throws HttpException, IOException {

        Log.d(getClass().getName(), "Processing HTTP request: "
                + request.getHead().getRequestUri());
        final AsyncResponseBuilder responseBuilder = AsyncResponseBuilder.create(HttpStatus.SC_OK);
        // Extract what we need from the HTTP httpRequest
        String requestMethod = request.getHead().getMethod()
                .toUpperCase(Locale.ENGLISH);

        // Only accept HTTP-GET
        if (!requestMethod.equals("GET") && !requestMethod.equals("HEAD")) {
            Log.d(getClass().getName(),
                    "HTTP request isn't GET or HEAD stop! Method was: "
                            + requestMethod);
            throw new MethodNotSupportedException(requestMethod
                    + " method not supported");
        }

        Uri requestUri = Uri.parse(request.getHead().getRequestUri());
        List<String> pathSegments = requestUri.getPathSegments();
        if (pathSegments.size() < 2 || pathSegments.size() > 3) {
            responseBuilder.setStatus(HttpStatus.SC_FORBIDDEN);
            responseBuilder.setEntity(AsyncEntityProducers.create("<html><body><h1>Access denied</h1></body></html>", ContentType.TEXT_HTML));
            responseTrigger.submitResponse(responseBuilder.build(), context);
            Log.d(getClass().getName(), "end doService: Access denied");
            return;
        }
        String type = pathSegments.get(0);
        String albumId = "";
        String thumbId = "";
        String contentId = "";
        if ("album".equals(type)) {
            albumId = pathSegments.get(1);
            try {
                Long.parseLong(albumId);
            } catch (NumberFormatException nex) {
                responseBuilder.setStatus(HttpStatus.SC_FORBIDDEN);
                responseBuilder.setEntity(AsyncEntityProducers.create("<html><body><h1>Access denied</h1></body></html>", ContentType.TEXT_HTML));
                responseTrigger.submitResponse(responseBuilder.build(), context);
                Log.d(getClass().getName(), "end doService: Access denied");
                return;
            }
        } else if ("thumb".equals(type)) {
            thumbId = pathSegments.get(1);
            try {
                Long.parseLong(thumbId);
            } catch (NumberFormatException nex) {
                responseBuilder.setStatus(HttpStatus.SC_FORBIDDEN);
                responseBuilder.setEntity(AsyncEntityProducers.create("<html><body><h1>Access denied</h1></body></html>", ContentType.TEXT_HTML));
                responseTrigger.submitResponse(responseBuilder.build(), context);
                Log.d(getClass().getName(), "end doService: Access denied");
                return;
            }
        } else if ("res".equals(type)) {
            contentId = pathSegments.get(1);
            try {
                Long.parseLong(contentId);
            } catch (NumberFormatException nex) {
                responseBuilder.setStatus(HttpStatus.SC_FORBIDDEN);
                responseBuilder.setEntity(AsyncEntityProducers.create("<html><body><h1>Access denied</h1></body></html>", ContentType.TEXT_HTML));
                responseTrigger.submitResponse(responseBuilder.build(), context);
                Log.d(getClass().getName(), "end doService: Access denied");
                return;
            }
        }
        Arrays.stream(request.getHead().getHeaders()).forEach(it -> Log.d(getClass().getName(), "HEADER " + it.getName() + ": " + it.getValue()));
        ContentHolder contentHolder = null;

        if (!contentId.isEmpty()) {
            contentHolder = lookupContent(contentId);
        } else if (!albumId.isEmpty()) {
            contentHolder = lookupAlbumArt(albumId);
        } else if (!thumbId.isEmpty()) {
            contentHolder = lookupThumbnail(thumbId);
        } else if (YaaccUpnpServerService.PROXY_PATH.equals(type)) {
            contentHolder = lookupProxyContent(pathSegments.get(1));
        }
        if (contentHolder == null) {
            // tricky but works
            Log.d(getClass().getName(), "Resource with id " + contentId
                    + albumId + thumbId + pathSegments.get(1) + " not found");
            responseBuilder.setStatus(HttpStatus.SC_NOT_FOUND);
            String response =
                    "<html><body><h1>Resource with id " + contentId + albumId
                            + thumbId + pathSegments.get(1) + " not found</h1></body></html>";
            responseBuilder.setEntity(AsyncEntityProducers.create(response, ContentType.TEXT_HTML));
        } else {

            responseBuilder.setStatus(HttpStatus.SC_OK);
            responseBuilder.setEntity(contentHolder.getEntityProducer());
        }
        responseBuilder.setHeader(HttpHeaders.ACCEPT_RANGES, "none");
        responseTrigger.submitResponse(responseBuilder.build(), context);
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
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
        if (!preferences.getBoolean(getContext().getString(R.string.settings_local_server_chkbx), false)) {
            return null;
        }

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
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
        if (!preferences.getBoolean(getContext().getString(R.string.settings_local_server_chkbx), false)) {
            return result;
        }
        if (albumId == null) {
            return result;
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
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
        if (!preferences.getBoolean(getContext().getString(R.string.settings_local_server_chkbx), false)) {
            return result;
        }
        if (idStr == null) {
            return result;
        }
        long id;
        try {
            id = Long.parseLong(idStr);
        } catch (NumberFormatException nfe) {
            Log.d(getClass().getName(), "ParsingError of id: " + idStr, nfe);
            return result;
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

    private ContentHolder lookupProxyContent(String contentKey) {

        String targetUri = PreferenceManager.getDefaultSharedPreferences(getContext()).getString(YaaccUpnpServerService.PROXY_LINK_KEY_PREFIX + contentKey, null);
        if (targetUri == null) {
            return null;
        }
        String targetMimetype = PreferenceManager.getDefaultSharedPreferences(getContext()).getString(YaaccUpnpServerService.PROXY_LINK_MIME_TYPE_KEY_PREFIX + contentKey, null);
        MimeType mimeType = MimeType.valueOf("*/*");
        if (targetMimetype != null) {
            mimeType = MimeType.valueOf(targetMimetype);
        }
        return new ContentHolder(mimeType, targetUri);
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


        public AsyncEntityProducer getEntityProducer() {
            AsyncEntityProducer result = null;
            if (getUri() != null && !getUri().isEmpty()) {
                if (new File(getUri()).exists()) {

                    File file = new File(getUri());
                    result = AsyncEntityProducers.create(file, ContentType.parse(getMimeType().toString()));
                    Log.d(getClass().getName(), "Return file-Uri: " + getUri()
                            + "Mimetype: " + getMimeType());
                } else {
                    //file not found maybe external url
                    result = new AbstractBinAsyncEntityProducer(0, ContentType.parse(getMimeType().toString())) {
                        private InputStream input;
                        private long length = -1;

                        AbstractBinAsyncEntityProducer init() {
                            try {
                                if (input == null) {
                                    URLConnection con = new URL(getUri()).openConnection();
                                    input = con.getInputStream();
                                    length = con.getContentLength();
                                }
                            } catch (IOException e) {
                                Log.e(getClass().getName(), "Error opening external content", e);
                            }
                            return this;
                        }

                        @Override
                        public long getContentLength() {
                            return length;
                        }

                        @Override
                        protected int availableData() {
                            return Integer.MAX_VALUE;
                        }

                        @Override
                        protected void produceData(final StreamChannel<ByteBuffer> channel) throws IOException {
                            try {
                                if (input == null) {
                                    //retry opening external content if it hasn't been opened yet
                                    URLConnection con = new URL(getUri()).openConnection();
                                    input = con.getInputStream();
                                    length = con.getContentLength();
                                }
                                byte[] tempBuffer = new byte[1024];
                                int bytesRead;
                                if (-1 != (bytesRead = input.read(tempBuffer))) {
                                    channel.write(ByteBuffer.wrap(tempBuffer, 0, bytesRead));
                                }
                                if (bytesRead == -1) {
                                    channel.endStream();
                                }

                            } catch (IOException e) {
                                Log.e(getClass().getName(), "Error reading external content", e);
                                throw e;
                            }
                        }


                        @Override
                        public boolean isRepeatable() {
                            return false;
                        }

                        @Override
                        public void failed(final Exception cause) {
                        }

                    }.init();

                    Log.d(getClass().getName(), "Return external-Uri: " + getUri()
                            + "Mimetype: " + getMimeType());
                }
            } else if (content != null) {
                result = AsyncEntityProducers.create(content, ContentType.parse(getMimeType().toString()));
            }
            return result;

        }
    }
}
