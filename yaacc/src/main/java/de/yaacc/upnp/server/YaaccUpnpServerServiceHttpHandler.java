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
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.util.Size;

import androidx.core.content.res.ResourcesCompat;
import androidx.documentfile.provider.DocumentFile;
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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import de.yaacc.R;
import de.yaacc.upnp.server.contentdirectory.ContentDirectoryIDs;
import de.yaacc.upnp.server.contentdirectory.MediaPathFilter;
import de.yaacc.util.HttpRange;

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
    public AsyncRequestConsumer<Message<HttpRequest, byte[]>> prepare(HttpRequest request, EntityDetails entityDetails,
                                                                      HttpContext context) {
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
        if (pathSegments.size() == 1 && "health".equals(pathSegments.get(0))) {
            responseBuilder.setStatus(HttpStatus.SC_OK);
            responseBuilder.setEntity(
                    AsyncEntityProducers.create("<html><body>I am alive</body></html>", ContentType.TEXT_HTML));
            responseTrigger.submitResponse(responseBuilder.build(), context);
            return;
        }
        if (pathSegments.size() < 2 || pathSegments.size() > 3) {
            createForbiddenResponse(responseTrigger, context, responseBuilder);
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
                createForbiddenResponse(responseTrigger, context, responseBuilder);
                return;
            }
        } else if ("thumb".equals(type)) {
            thumbId = pathSegments.get(1);
            try {
                Long.parseLong(thumbId);
            } catch (NumberFormatException nex) {
                createForbiddenResponse(responseTrigger, context, responseBuilder);
                return;
            }
        } else if ("res".equals(type)) {
            contentId = pathSegments.get(1);
            try {
                Long.parseLong(contentId);
            } catch (NumberFormatException nex) {
                createForbiddenResponse(responseTrigger, context, responseBuilder);
                return;
            }
        }
        Arrays.stream(request.getHead().getHeaders())
                .forEach(it -> Log.d(getClass().getName(), "HEADER " + it.getName() + ": " + it.getValue()));
        List<HttpRange> ranges = new ArrayList<>();
        if (request.getHead().getHeader(HttpHeaders.RANGE) != null) {
            ranges = HttpRange.parseRangeHeader(request.getHead().getHeader(HttpHeaders.RANGE).getValue().toString());
        }
        ContentHolder contentHolder = null;
        if (!contentId.isEmpty()) {
            contentHolder = lookupContent(contentId, ranges);
        } else if (!albumId.isEmpty()) {
            contentHolder = lookupAlbumArt(albumId, ranges);
        } else if (!thumbId.isEmpty()) {
            contentHolder = lookupThumbnail(thumbId, ranges);
        } else if (YaaccUpnpServerService.PROXY_PATH.equals(type)) {
            contentHolder = lookupProxyContent(pathSegments.get(1), ranges);
        } else if (YaaccUpnpServerService.SAF_PATH.equals(type)) {
            contentHolder = lookupSafContent(pathSegments.get(1), pathSegments.get(2), ranges);
        }

        if (contentHolder == null) {
            // tricky but works
            Log.d(getClass().getName(), "Resource with id " + contentId
                    + albumId + thumbId + pathSegments.get(1) + " not found");
            responseBuilder.setStatus(HttpStatus.SC_NOT_FOUND);
            String response = "<html><body>Resource with id " + contentId + albumId
                    + thumbId + pathSegments.get(1) + " not found</body></html>";
            responseBuilder.setEntity(AsyncEntityProducers.create(response, ContentType.TEXT_HTML));
        } else {
            if (!ranges.isEmpty()) {
                responseBuilder.setStatus(HttpStatus.SC_PARTIAL_CONTENT);
                // Add Content-Range header for partial content
                HttpRange range = ranges.get(0);
                long fileSize = contentHolder.getContentLength();
                long start = range.getStart() != null ? range.getStart() : 0;
                long end = range.getEnd() != null ? range.getEnd() : fileSize - 1;
                responseBuilder.setHeader(HttpHeaders.CONTENT_RANGE, 
                    "bytes " + start + "-" + end + "/" + fileSize);
            } else {
                responseBuilder.setStatus(HttpStatus.SC_OK);
            }
            responseBuilder.setEntity(contentHolder.getEntityProducer());
        }
        responseBuilder.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        responseTrigger.submitResponse(responseBuilder.build(), context);
        Log.d(getClass().getName(), "end doService: ");
    }

    private void createForbiddenResponse(ResponseTrigger responseTrigger, HttpContext context,
                                         AsyncResponseBuilder responseBuilder) throws HttpException, IOException {
        responseBuilder.setStatus(HttpStatus.SC_FORBIDDEN);
        responseBuilder.setEntity(
                AsyncEntityProducers.create("<html><body>Access denied</body></html>", ContentType.TEXT_HTML));
        responseTrigger.submitResponse(responseBuilder.build(), context);
        Log.d(getClass().getName(), "end doService: Access denied");
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
    private ContentHolder lookupContent(String contentId, List<HttpRange> ranges) {
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
        String selection = MediaStore.Files.FileColumns._ID + "=? and (" + MediaPathFilter.makeLikeClause(
                MediaStore.Files.FileColumns.DATA, MediaPathFilter.getMediaPathes(getContext()).size()) + ")";
        List<String> selectionArgsList = new ArrayList<>();
        selectionArgsList.add(contentId);
        selectionArgsList.addAll(MediaPathFilter.getMediaPathesForLikeClause(getContext()));
        String[] selectionArgs = selectionArgsList.toArray(new String[0]);
        try (Cursor mFilesCursor = getContext().getContentResolver().query(
                MediaStore.Files.getContentUri("external"), projection,
                selection, selectionArgs, null)) {

            if (mFilesCursor != null) {
                mFilesCursor.moveToFirst();
                while (!mFilesCursor.isAfterLast()) {
                    @SuppressLint("Range")
                    String dataUri = mFilesCursor.getString(mFilesCursor
                            .getColumnIndex(MediaStore.Files.FileColumns.DATA));

                    @SuppressLint("Range")
                    String mimeTypeStr = mFilesCursor
                            .getString(mFilesCursor
                                    .getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE));
                    MimeType mimeType = MimeType.valueOf("*/*");
                    if (mimeTypeStr != null) {
                        mimeType = MimeType.valueOf(mimeTypeStr);
                    }
                    Log.d(getClass().getName(), "Content found: " + mimeType
                            + " Uri: " + dataUri);
                    result = new ContentHolder(mimeType, dataUri, ranges, context);
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
    private ContentHolder lookupAlbumArt(String albumId, List<HttpRange> ranges) {

        ContentHolder result = new ContentHolder(MimeType.valueOf("image/png"),
                getDefaultIcon(), ranges, context);
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
        if (!preferences.getBoolean(getContext().getString(R.string.settings_local_server_chkbx), false)) {
            return result;
        }
        if (albumId == null) {
            return result;
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
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
                        @SuppressLint("Range")
                        String dataUri = cursor.getString(cursor
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
                            result = new ContentHolder(mimeType, dataUri, ranges, context);
                        } else {
                            Log.d(getClass().getName(), "Album art not found in media store. Fallback to default");
                            Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(),
                                    R.drawable.yaacc192_32);

                            try {
                                File art = new File(context.getCacheDir(), "albumart" + albumId + ".jpg");
                                art.createNewFile();
                                FileOutputStream fos = new FileOutputStream(art);
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos);
                                fos.flush();
                                fos.close();
                                result = new ContentHolder(mimeType, art.getAbsolutePath(), ranges, context);
                            } catch (IOException e) {
                                Log.e(getClass().getName(), "Error loading album art from file", e);
                            }
                        }
                        cursor.moveToNext();
                    }
                } else {
                    Log.d(getClass().getName(), "System media store is empty.");
                }
            }
        } else {
            Uri albumArtUri = ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                    Long.parseLong(albumId));
            MimeType mimeType = MimeType.valueOf("image/jpeg");
            Log.d(getClass().getName(), "Content found: " + mimeType
                    + " Uri: " + albumArtUri);
            Bitmap bitmap;
            try {
                bitmap = context.getContentResolver().loadThumbnail(albumArtUri, new Size(1024, 1024), null);
            } catch (IOException io) {
                Log.d(getClass().getName(), "Album art not found in media store. Fallback to default");
                bitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.yaacc192_32);
            }
            try {
                File art = new File(context.getCacheDir(), "albumart" + albumId + ".jpg");
                art.createNewFile();
                FileOutputStream fos = new FileOutputStream(art);
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos);
                fos.flush();
                fos.close();
                result = new ContentHolder(mimeType, art.getAbsolutePath(), ranges, context);
            } catch (IOException e) {
                Log.e(getClass().getName(), "Error loading album art from file", e);
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
    private ContentHolder lookupThumbnail(String idStr, List<HttpRange> ranges) {

        ContentHolder result = new ContentHolder(MimeType.valueOf("image/png"),
                getDefaultIcon(), ranges, context);
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

            result = new ContentHolder(mimeType, byteArray, ranges, context);

        } else {
            Log.d(getClass().getName(), "System media store is empty.");
        }
        return result;
    }

    private ContentHolder lookupProxyContent(String contentKey, List<HttpRange> ranges) {

        String targetUri = PreferenceManager.getDefaultSharedPreferences(getContext())
                .getString(YaaccUpnpServerService.PROXY_LINK_KEY_PREFIX + contentKey, null);
        if (targetUri == null) {
            return null;
        }
        String targetMimetype = PreferenceManager.getDefaultSharedPreferences(getContext())
                .getString(YaaccUpnpServerService.PROXY_LINK_MIME_TYPE_KEY_PREFIX + contentKey, null);
        MimeType mimeType = MimeType.valueOf("*/*");
        if (targetMimetype != null) {
            mimeType = MimeType.valueOf(targetMimetype);
        }
        return new ContentHolder(mimeType, targetUri, ranges, context);
    }

    private ContentHolder lookupSafContent(String contentKey, String contentEnc, List<HttpRange> ranges) {
        if (!contentKey.startsWith(ContentDirectoryIDs.SAF_PREFIX.getId())) {
            Log.d(getClass().getName(), "SAF content id is unknown: " + contentKey);
            return null;
        }
        String contentId = contentKey.substring(ContentDirectoryIDs.SAF_PREFIX.getId().length());
        if (contentEnc.indexOf(".") == -1) {
            Log.d(getClass().getName(), "SAF content id is invalid: " + contentEnc);
            return null;
        }
        String contentUri = new String(
                Base64.decode(contentEnc.substring(0, contentEnc.indexOf(".")).getBytes(), Base64.NO_WRAP));
        if (!contentId.equals("" + contentUri.hashCode())) {
            Log.d(getClass().getName(), "SAF content id is invalid: " + contentId);
            return null;
        }

        DocumentFile file = null;
        try {
            Uri uri = Uri.parse(contentUri);
            // Use fromSingleUri for document URIs, fromTreeUri for tree URIs
            if (contentUri.contains("/document/")) {
                file = DocumentFile.fromSingleUri(getContext(), uri);
            } else {
                file = DocumentFile.fromTreeUri(getContext(), uri);
            }
            
            if (file == null || !file.exists()) {
                Log.d(getClass().getName(), "SAF content uri is unknown: " + contentUri);
                return null;
            }
            
            // Check if it's a directory - directories cannot be streamed
            if (file.isDirectory()) {
                Log.d(getClass().getName(), "SAF content is a directory, cannot stream: " + contentUri);
                return null;
            }
        } catch (Exception e) {
            Log.e(getClass().getName(), "Error accessing SAF content: " + contentUri, e);
            return null;
        }

        String mimeTypeStr = null;
        try {
            mimeTypeStr = file.getType();
        } catch (Exception e) {
            Log.w(getClass().getName(), "Error getting MIME type for SAF content", e);
        }

        MimeType mimeType = MimeType.valueOf("*/*");
        if (mimeTypeStr != null) {
            mimeType = MimeType.valueOf(mimeTypeStr);
        } else {
            // Fallback: try to determine MIME type from file extension
            //String fileName = file.getName();
            String fileName = contentEnc;
            Log.d(getClass().getName(), "File name: " + fileName);
            if (fileName != null) {
                String fileExtension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(fileName);
                if (fileExtension != null && !fileExtension.isEmpty()) {
                    String fallbackMimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileExtension);
                    if (fallbackMimeType != null) {
                        mimeType = MimeType.valueOf(fallbackMimeType);
                    }
                }
            }
        }

        // Check if contentUri or its parents are included in selectedSafPathes
        // FIXME
        /*
         * Set<String> selectedSafPathes =
         * MediaPathFilter.getSelectedSafPathes(getContext());
         * boolean isPathAllowed = false;
         *
         * for (String selectedPath : selectedSafPathes) {
         * if (contentUri.startsWith(selectedPath)) {
         * isPathAllowed = true;
         * break;
         * }
         * }
         *
         * if (!isPathAllowed) {
         * Log.d(getClass().getName(), "SAF content URI not in selected paths: " +
         * contentUri);
         * return null;
         * }
         */
        String targetUri = contentUri;
        // Pass the DocumentFile instance to avoid re-creating it during streaming
        return new SafContentHolder(mimeType, targetUri, ranges, context, file);
    }

    /**
     * Special ContentHolder for SAF content that avoids blocking DocumentFile operations
     */
    static class SafContentHolder extends ContentHolder {
        private final DocumentFile documentFile;

        public SafContentHolder(MimeType mimeType, String uri, List<HttpRange> ranges, Context context, DocumentFile documentFile) {
            super(mimeType, uri, ranges, context);
            this.documentFile = documentFile;
        }

        @Override
        public AsyncEntityProducer getEntityProducer() throws IOException {
            if (documentFile != null && documentFile.exists() && !documentFile.isDirectory()) {
                return new AbstractBinAsyncEntityProducer(8192, ContentType.parse(getMimeType().toString())) {
                    private InputStream input;
                    private long totalBytesRead = 0;
                    private final long fileLength = documentFile.length();
                    private long startPosition = 0;
                    private long rangeLength = fileLength;

                    {
                        // Calculate range if specified
                        if (!ranges.isEmpty()) {
                            HttpRange range = ranges.get(0);
                            startPosition = range.getStart() == null ? 0 : range.getStart();
                            if (range.getEnd() != null && range.getEnd() > 0) {
                                rangeLength = range.getEnd() - startPosition + 1;
                            } else {
                                rangeLength = fileLength - startPosition;
                            }
                            if (range.getSuffixLength() != null && range.getSuffixLength() > 0) {
                                startPosition = Math.max(0, fileLength - range.getSuffixLength());
                                rangeLength = range.getSuffixLength();
                            }
                            // Ensure range is valid
                            if (startPosition >= fileLength) {
                                startPosition = 0;
                                rangeLength = fileLength;
                            }
                            if (startPosition + rangeLength > fileLength) {
                                rangeLength = fileLength - startPosition;
                            }
                        }
                    }

                    @Override
                    public long getContentLength() {
                        return rangeLength;
                    }

                    @Override
                    protected int availableData() {
                        return input != null ? 1 : 0;
                    }

                    @Override
                    protected void produceData(final StreamChannel<ByteBuffer> channel) throws IOException {
                        if (input == null) {
                            try {
                                input = context.getContentResolver().openInputStream(documentFile.getUri());
                                if (startPosition > 0) {
                                    input.skip(startPosition);
                                }
                                Log.d(getClass().getName(), "Opened SAF input stream for: " + documentFile.getUri() + 
                                      " range: " + startPosition + "-" + (startPosition + rangeLength - 1));
                            } catch (Exception e) {
                                Log.e(getClass().getName(), "Error opening SAF input stream", e);
                                channel.endStream();
                                return;
                            }
                        }
                        
                        if (input != null && totalBytesRead < rangeLength) {
                            byte[] buffer = new byte[8192];
                            int maxRead = (int) Math.min(buffer.length, rangeLength - totalBytesRead);
                            try {
                                int bytesRead = input.read(buffer, 0, maxRead);
                                if (bytesRead > 0) {
                                    totalBytesRead += bytesRead;
                                    channel.write(ByteBuffer.wrap(buffer, 0, bytesRead));
                                } else {
                                    Log.d(getClass().getName(), "End of SAF stream reached. Total bytes: " + totalBytesRead);
                                    input.close();
                                    channel.endStream();
                                }
                            } catch (IOException e) {
                                Log.e(getClass().getName(), "Error reading from SAF stream", e);
                                if (input != null) {
                                    try { input.close(); } catch (IOException ignored) {}
                                }
                                channel.endStream();
                            }
                        } else {
                            if (input != null) {
                                try { input.close(); } catch (IOException ignored) {}
                            }
                            channel.endStream();
                        }
                    }

                    @Override
                    public boolean isRepeatable() {
                        return false;
                    }

                    @Override
                    public void failed(final Exception cause) {
                        Log.e(getClass().getName(), "SAF streaming failed", cause);
                        if (input != null) {
                            try { input.close(); } catch (IOException ignored) {}
                        }
                    }
                };
            }
            Log.e(getClass().getName(), "DocumentFile is null, doesn't exist, or is a directory");
            return super.getEntityProducer();
        }
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
        protected final MimeType mimeType;
        protected String uri;
        protected byte[] content;
        protected final Context context;
        protected List<HttpRange> ranges;

        public ContentHolder(MimeType mimeType, String uri, List<HttpRange> ranges, Context context) {
            this.uri = uri;
            this.mimeType = mimeType;
            this.ranges = ranges;
            this.context = context;

        }

        public ContentHolder(MimeType mimeType, byte[] content, List<HttpRange> ranges, Context context) {
            this.content = content;
            this.mimeType = mimeType;
            this.ranges = ranges;
            this.context = context;

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

        /**
         * @return the content length
         */
        public long getContentLength() {
            if (content != null) {
                return content.length;
            } else if (uri != null) {
                // Check if it's an external URL
                if (uri.startsWith("http://") || uri.startsWith("https://")) {
                    try {
                        HttpURLConnection con = (HttpURLConnection) new URL(uri).openConnection();
                        con.setRequestMethod("HEAD");
                        con.setConnectTimeout(5000); // 5 second timeout
                        con.setReadTimeout(5000); // 5 second timeout
                        long length = con.getContentLengthLong();
                        con.disconnect();
                        if (length > 0) {
                            return length;
                        }
                        return -1; // Unknown length for external URLs
                    } catch (Exception e) {
                        Log.e(getClass().getName(), "Error getting external content length", e);
                        return -1;
                    }
                } else {
                    // Handle local files
                    File file = new File(uri);
                    if (file.exists()) {
                        return file.length();
                    } else {
                        // Handle SAF content
                        try {
                            Uri contentUri = Uri.parse(uri);
                            DocumentFile docFile = DocumentFile.fromSingleUri(context, contentUri);
                            if (docFile != null) {
                                return docFile.length();
                            }
                        } catch (Exception e) {
                            Log.e(getClass().getName(), "Error getting SAF content length", e);
                        }
                    }
                }
            }
            return -1;
        }

        private byte[] readRangeFormFile(File file, List<HttpRange> ranges) throws IOException {

            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                long fileSize = raf.length();
                long startPosition;
                long rangeLength;
                if (ranges.size() > 1) {
                    Log.d(getClass().getName(),
                            "More than on ranges requested. Currently only one range is supported. Responding with the first range");
                }
                if (ranges.isEmpty()) {
                    startPosition = 0;
                    rangeLength = fileSize;
                } else {
                    HttpRange range = ranges.get(0);
                    startPosition = range.getStart() == null ? 0 : range.getStart();
                    if (range.getEnd() == null || range.getEnd() == 0) {
                        rangeLength = fileSize;
                    } else {
                        rangeLength = range.getEnd() - startPosition;
                    }
                    if (range.getSuffixLength() != null && range.getSuffixLength() > 0) {
                        startPosition = fileSize - range.getSuffixLength();
                        rangeLength = range.getSuffixLength();
                    }
                }

                // Read a range of bytes (e.g., bytes 100 to 200)
                if (startPosition < 0 || startPosition + rangeLength > fileSize) {
                    Log.d(getClass().getName(), "Invalid range startPosition: " + startPosition + " rangeLength: "
                            + rangeLength + " fileSize: " + fileSize);
                    rangeLength = fileSize - startPosition;
                    Log.d(getClass().getName(), "Adjusted range startPosition: " + startPosition + " rangeLength: "
                            + rangeLength + " fileSize: " + fileSize);
                }

                raf.seek(startPosition); // Move to the starting position
                byte[] buffer = new byte[(int) rangeLength]; // Create a buffer
                raf.read(buffer);
                return buffer;
            }

        }

        public AsyncEntityProducer getEntityProducer() throws IOException {
            AsyncEntityProducer result = null;
            if (getUri() != null && !getUri().isEmpty()) {
                // Check if it's an external URL (http/https)
                if (getUri().startsWith("http://") || getUri().startsWith("https://")) {
                    // Handle external URL directly
                    result = new AbstractBinAsyncEntityProducer(0, ContentType.parse(getMimeType().toString())) {
                        private InputStream input;
                        private long length = -1;
                        private long bytesRead = 0;

                        AbstractBinAsyncEntityProducer init() {
                            try {
                                if (input == null) {
                                    HttpURLConnection con = (HttpURLConnection) new URL(getUri()).openConnection();
                                    if (!ranges.isEmpty()) {
                                        con.setRequestProperty("Range", HttpRange.toHeaderString(ranges));
                                    }
                                    con.connect();
                                    input = con.getInputStream();
                                    length = con.getContentLengthLong();
                                    if (length <= 0) {
                                        length = con.getContentLength();
                                    }
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
                            try {
                                return input != null ? input.available() : 0;
                            } catch (IOException e) {
                                return 0;
                            }
                        }

                        @Override
                        protected void produceData(final StreamChannel<ByteBuffer> channel) throws IOException {
                            try {
                                if (input == null) {
                                    init(); // retry opening if needed
                                }
                                if (input != null) {
                                    byte[] buffer = new byte[8192];
                                    int read = input.read(buffer);
                                    if (read > 0) {
                                        bytesRead += read;
                                        channel.write(ByteBuffer.wrap(buffer, 0, read));
                                    } else {
                                        input.close();
                                        channel.endStream();
                                    }
                                } else {
                                    channel.endStream();
                                }
                            } catch (IOException e) {
                                Log.e(getClass().getName(), "Error reading external content", e);
                                if (input != null) {
                                    try { input.close(); } catch (IOException ignored) {}
                                }
                                channel.endStream();
                            }
                        }

                        @Override
                        public boolean isRepeatable() {
                            return true; // Keep as repeatable for compatibility with renderers
                        }

                        @Override
                        public void failed(final Exception cause) {
                            Log.e(getClass().getName(), "External content streaming failed", cause);
                            if (input != null) {
                                try { input.close(); } catch (IOException ignored) {}
                            }
                        }
                    }.init();
                } else {
                    // Handle local files and SAF content
                    File file = new File(getUri());
                    if (file.exists()) {
                    if (ranges.isEmpty()) {
                        result = AsyncEntityProducers.create(file, ContentType.parse(getMimeType().toString()));
                        Log.d(getClass().getName(), "Return without range request file-Uri: " + getUri()
                                + " Mimetype: " + getMimeType());
                    } else {
                        result = AsyncEntityProducers.create(readRangeFormFile(file, ranges),
                                ContentType.parse(getMimeType().toString()));
                    }
                    } else {
                        // DocumentFile handling - need to read content through InputStream
                        try {
                            Uri uri = Uri.parse(getUri());
                            // For DocumentFile, we need to handle it differently since AsyncEntityProducers doesn't support it directly
                            // We'll read the content as bytes and create from that
                            if (ranges.isEmpty()) {
                                // DocumentFile handling using ContentResolver
                                result = new AbstractBinAsyncEntityProducer(0, ContentType.parse(getMimeType().toString())) {
                                    private InputStream input;
                                    private long length = -1;

                                    AbstractBinAsyncEntityProducer init() {
                                        try {
                                            input = context.getContentResolver().openInputStream(uri);
                                            DocumentFile docFile = DocumentFile.fromSingleUri(context, uri);
                                            if (docFile != null) {
                                                length = docFile.length();
                                            }
                                        } catch (IOException e) {
                                            Log.e(getClass().getName(), "Error opening DocumentFile", e);
                                        }
                                        return this;
                                    }

                                    @Override
                                    public long getContentLength() {
                                        return length;
                                    }

                                    @Override
                                    protected int availableData() {
                                        try {
                                            return input != null ? input.available() : 0;
                                        } catch (IOException e) {
                                            return 0;
                                        }
                                    }

                                    @Override
                                    protected void produceData(final StreamChannel<ByteBuffer> channel) throws IOException {
                                        if (input != null) {
                                            byte[] buffer = new byte[8192];
                                            int bytesRead = input.read(buffer);
                                            if (bytesRead > 0) {
                                                channel.write(ByteBuffer.wrap(buffer, 0, bytesRead));
                                            } else {
                                                input.close();
                                                channel.endStream();
                                            }
                                        } else {
                                            channel.endStream();
                                        }
                                    }

                                    @Override
                                    public boolean isRepeatable() {
                                        return true;
                                    }

                                    @Override
                                    public void failed(final Exception cause) {
                                    }
                                }.init();
                            } else {
                                // Range requests for DocumentFile not implemented yet
                                result = AsyncEntityProducers.create("DocumentFile range requests not implemented".getBytes(),
                                        ContentType.parse(getMimeType().toString()));
                            }
                        } catch (Exception e) {
                            Log.e(getClass().getName(), "Error handling DocumentFile", e);
                        }
                    }
                }
            } else if (content != null) {
                result = AsyncEntityProducers.create(content, ContentType.parse(getMimeType().toString()));
            }
            
            if (result == null) {
                Log.d(getClass().getName(), "Resource is null");
                return AsyncEntityProducers.create("<html><body><h1>Resource not found</h1></body></html>",
                        ContentType.TEXT_HTML);
            }
            return result;
        }
    }
}
