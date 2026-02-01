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
import android.media.MediaMetadataRetriever;
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
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    // Server-side position management for renderers
    private static final Map<String, RendererState> rendererStates = new ConcurrentHashMap<>();

    static class RendererState {
        long currentTimePosition = 0; // milliseconds
        boolean isPaused = false;
        String currentUrl = "";
        long totalDuration = 0;
        long lastUpdateTime = System.currentTimeMillis();
        long lastBytePosition = 0; // Store last calculated byte position for pause
    }

    public YaaccUpnpServerServiceHttpHandler(Context context) {
        this.context = context;
    }

    private long calculateBytePositionFromTime(String url, long timeMs, long totalDurationMs) {
        // More conservative estimation for MP3 files
        if (totalDurationMs <= 0) return 0;

        try {
            // Get total file size with HEAD request
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setRequestMethod("HEAD");
            con.setConnectTimeout(5000);
            con.setReadTimeout(5000);
            long totalSize = con.getContentLengthLong();
            con.disconnect();

            if (totalSize > 0) {
                // More conservative calculation for MP3 files
                // Account for MP3 headers and variable bitrate
                double timeRatio = (double) timeMs / totalDurationMs;

                // Assume first 10% of file contains headers/metadata
                long dataSize = (long) (totalSize * 0.9);
                long headerSize = totalSize - dataSize;

                // Calculate position within the data portion
                long estimatedDataPosition = (long) (dataSize * timeRatio);
                long estimatedPosition = headerSize + estimatedDataPosition;

                // Additional safety margin - don't go beyond 85% of file size
                long maxSafePosition = (long) (totalSize * 0.85);
                estimatedPosition = Math.min(estimatedPosition, maxSafePosition);

                Log.d(getClass().getName(), "Calculated byte position " + estimatedPosition + " for time " + timeMs + "ms (file size: " + totalSize + ", ratio: " + String.format("%.3f", timeRatio) + ")");
                return estimatedPosition;
            }
        } catch (Exception e) {
            Log.w(getClass().getName(), "Failed to calculate byte position", e);
        }

        return 0;
    }

    private long getDurationFromUrl(String url) {
        try {
            // Get file size with HEAD request
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setRequestMethod("HEAD");
            con.setConnectTimeout(5000);
            con.setReadTimeout(5000);
            long fileSize = con.getContentLengthLong();
            con.disconnect();
            
            if (fileSize > 0) {
                // Estimate duration for MP3 files based on file size
                // Assume average bitrate of 128 kbps for MP3 files
                // Duration (seconds) = (file size in bytes * 8) / (bitrate in bits per second)
                long estimatedDurationMs = (fileSize * 8 * 1000) / (128 * 1024);
                Log.d(getClass().getName(), "Estimated duration: " + estimatedDurationMs + "ms from file size: " + fileSize + " bytes");
                return estimatedDurationMs;
            }
        } catch (Exception e) {
            Log.w(getClass().getName(), "Failed to estimate duration from URL: " + url, e);
        }
        return 0;
    }

    public static void updateRendererPosition(String rendererKey, long timeMs) {
        updateRendererPosition(rendererKey, timeMs, false);
    }

    public static void updateRendererPosition(String rendererKey, long timeMs, boolean isPaused) {
        RendererState state = rendererStates.get(rendererKey);
        if (state != null) {
            state.currentTimePosition = timeMs;
            state.isPaused = isPaused;
            state.lastUpdateTime = System.currentTimeMillis();
            Log.d("YaaccUpnpServerServiceHttpHandler", "Updated renderer position: " + rendererKey + " -> " + timeMs + "ms, paused=" + isPaused);
        }
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
            Log.d(getClass().getName(), "Processing proxy request: " + requestUri);
            // Handle both old and new proxy URL formats
            if (pathSegments.size() >= 3) {
                // New format: /proxy/encodedDeviceId/contentKey
                String encodedDeviceId = pathSegments.get(1);
                String deviceId = java.net.URLDecoder.decode(encodedDeviceId, "UTF-8");
                String contentKey = pathSegments.get(2);
                contentHolder = lookupProxyContent(contentKey, ranges, deviceId);
            } else if (pathSegments.size() >= 2) {
                // Old format: /proxy/contentKey (fallback)
                contentHolder = lookupProxyContent(pathSegments.get(1), ranges, null);
            }
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

                // For external URLs with unknown length, don't send Content-Range header
                if (fileSize > 0) {
                    long start = range.getStart() != null ? range.getStart() : 0;
                    long end = range.getEnd() != null ? range.getEnd() : fileSize - 1;
                    responseBuilder.setHeader(HttpHeaders.CONTENT_RANGE,
                            "bytes " + start + "-" + end + "/" + fileSize);
                }
            } else {
                responseBuilder.setStatus(HttpStatus.SC_OK);
            }

            // Add essential streaming headers for UPnP renderers
            responseBuilder.setHeader(HttpHeaders.CONNECTION, "close");
            responseBuilder.setHeader("transferMode.dlna.org", "Streaming");
            responseBuilder.setHeader("contentFeatures.dlna.org", "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000");

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
        return lookupProxyContent(contentKey, ranges, null);
    }

    private ContentHolder lookupProxyContent(String contentKey, List<HttpRange> ranges, String deviceId) {
        Log.d(getClass().getName(), "Looking up proxy content for key: " + contentKey + ", device: " + deviceId);

        String targetUri = PreferenceManager.getDefaultSharedPreferences(getContext())
                .getString(YaaccUpnpServerService.PROXY_LINK_KEY_PREFIX + contentKey, null);
        Log.d(getClass().getName(), "Target URI from preferences: " + targetUri);

        if (targetUri == null) {
            Log.e(getClass().getName(), "No target URI found for proxy key: " + contentKey);
            return null;
        }
        String targetMimetype = PreferenceManager.getDefaultSharedPreferences(getContext())
                .getString(YaaccUpnpServerService.PROXY_LINK_MIME_TYPE_KEY_PREFIX + contentKey, null);
        Log.d(getClass().getName(), "Target MIME type: " + targetMimetype);

        // Check if this renderer needs server-side position management
        boolean useServerPositionManagement = false; // Default: no server-side management

        if (deviceId != null) {
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
            String prefKey = "manage_external_seeking_" + deviceId;
            useServerPositionManagement = preferences.getBoolean(prefKey, false);
            Log.d(getClass().getName(), "Checking preference key: " + prefKey);
            Log.d(getClass().getName(), "Server position management for device " + deviceId + ": " + useServerPositionManagement);
        } else {
            Log.d(getClass().getName(), "No device ID available, using default (no server-side management)");
        }

        if (useServerPositionManagement && !ranges.isEmpty()) {
            Log.d(getClass().getName(), "Server-side management enabled, processing ranges: " + ranges.size());
            // For basic renderers, ignore their range requests and use server-managed position
            String rendererKey = deviceId + "_" + contentKey;
            RendererState state = rendererStates.get(rendererKey);
            Log.d(getClass().getName(), "Looking for renderer state: " + rendererKey + ", found: " + (state != null));
            if (state != null && state.currentUrl.equals(targetUri)) {
                long bytePosition;
                if (state.isPaused) {
                    // When paused, use the exact same byte position as before
                    bytePosition = state.lastBytePosition;
                    Log.d(getClass().getName(), "Using cached byte position for pause: " + bytePosition);
                } else {
                    // Calculate new byte position and cache it
                    // If we don't have duration yet, get it now
                    if (state.totalDuration <= 0) {
                        state.totalDuration = getDurationFromUrl(targetUri);
                        Log.d(getClass().getName(), "Updated renderer state duration: " + state.totalDuration + "ms");
                    }
                    bytePosition = calculateBytePositionFromTime(targetUri, state.currentTimePosition, state.totalDuration);
                    if (bytePosition > 0) {
                        state.lastBytePosition = bytePosition; // Update cache for future pause
                    }
                }

                if (bytePosition > 0) {
                    // Override the renderer's incorrect range request
                    ranges.clear();
                    ranges.add(new HttpRange("bytes", (int) bytePosition, null, null));
                    Log.d(getClass().getName(), "Overriding range request with server-managed position: bytes=" + bytePosition + "- (paused=" + state.isPaused + ")");
                }
            } else {
                // Initialize renderer state for new playback  
                RendererState newState = new RendererState();
                newState.currentUrl = targetUri;
                // Get current server position (in case this is after a seek)
                SharedPreferences serverPrefs = PreferenceManager.getDefaultSharedPreferences(getContext());
                long serverPosition = serverPrefs.getLong("server_position_" + deviceId, 0);
                newState.currentTimePosition = serverPosition;
                newState.totalDuration = 0; // Will be determined dynamically
                newState.isPaused = false;
                newState.lastBytePosition = 0;
                rendererStates.put(rendererKey, newState);
                Log.d(getClass().getName(), "Initialized renderer state for: " + rendererKey + " with position: " + serverPosition + "ms");
                
                // Calculate byte position for the current server position
                if (serverPosition > 0) {
                    Log.d(getClass().getName(), "Attempting to get duration for URL: " + targetUri);
                    // Get the total duration dynamically from the media file
                    long totalDuration = getDurationFromUrl(targetUri);
                    Log.d(getClass().getName(), "Retrieved duration: " + totalDuration + "ms for position: " + serverPosition + "ms");
                    
                    if (totalDuration > 0) {
                        long bytePosition = calculateBytePositionFromTime(targetUri, serverPosition, totalDuration);
                        Log.d(getClass().getName(), "Calculated byte position: " + bytePosition + " for time: " + serverPosition + "ms");
                        if (bytePosition > 0) {
                            // Override the renderer's range request
                            ranges.clear();
                            ranges.add(new HttpRange("bytes", (int) bytePosition, null, null));
                            Log.d(getClass().getName(), "Overriding range request with server-managed position: bytes=" + bytePosition + "- (time=" + serverPosition + "ms, duration=" + totalDuration + "ms)");
                        } else {
                            Log.w(getClass().getName(), "Byte position calculation returned 0 or negative: " + bytePosition);
                        }
                    } else {
                        Log.w(getClass().getName(), "Could not determine duration for URL: " + targetUri);
                    }
                } else {
                    Log.d(getClass().getName(), "Server position is 0, no range override needed");
                }
            }
        }

        MimeType mimeType = MimeType.valueOf("*/*");
        if (targetMimetype != null) {
            mimeType = MimeType.valueOf(targetMimetype);
        }

        Log.d(getClass().getName(), "Creating ContentHolder for proxy: " + targetUri + " with MIME: " + mimeType + ", ranges: " + ranges.size());
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
                                    try {
                                        input.close();
                                    } catch (IOException ignored) {
                                    }
                                }
                                channel.endStream();
                            }
                        } else {
                            if (input != null) {
                                try {
                                    input.close();
                                } catch (IOException ignored) {
                                }
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
                            try {
                                input.close();
                            } catch (IOException ignored) {
                            }
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
                    result = new AbstractBinAsyncEntityProducer(8192, ContentType.parse(getMimeType().toString())) {
                        private InputStream input;
                        private long length = -1;
                        private long bytesRead = 0;

                        AbstractBinAsyncEntityProducer init() {
                            try {
                                if (input == null) {
                                    int retries = 3;
                                    Exception lastException = null;

                                    for (int i = 0; i < retries; i++) {
                                        try {
                                            HttpURLConnection con = (HttpURLConnection) new URL(getUri()).openConnection();
                                            con.setConnectTimeout(15000); // Increased to 15 seconds
                                            con.setReadTimeout(60000); // Increased to 60 seconds for UPnP renderers
                                            con.setRequestProperty("User-Agent", "YAACC/1.0 (Android UPnP Proxy)");
                                            con.setRequestProperty("Connection", "keep-alive"); // Try to keep connection alive
                                            // Apply range request to external connection
                                            if (!ranges.isEmpty()) {
                                                con.setRequestProperty("Range", HttpRange.toHeaderString(ranges));
                                                Log.d(getClass().getName(), "Applying range request to external URL: " + HttpRange.toHeaderString(ranges));
                                            }
                                            con.connect();

                                            // Check if we got a partial content response
                                            int responseCode = con.getResponseCode();
                                            Log.d(getClass().getName(), "External server response code: " + responseCode);

                                            input = con.getInputStream();
                                            length = con.getContentLengthLong();
                                            if (length <= 0) {
                                                length = con.getContentLength();
                                            }
                                            Log.d(getClass().getName(), "External connection established on attempt " + (i + 1) + ", content length: " + length);
                                            break; // Success, exit retry loop
                                        } catch (Exception e) {
                                            lastException = e;
                                            Log.w(getClass().getName(), "Connection attempt " + (i + 1) + " failed: " + e.getMessage());
                                            if (i < retries - 1) {
                                                try {
                                                    Thread.sleep(1000);
                                                } catch (InterruptedException ignored) {
                                                }
                                            }
                                        }
                                    }

                                    if (input == null && lastException != null) {
                                        throw new IOException("Failed to connect after " + retries + " attempts", lastException);
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
                            // For external URLs, always indicate data is available if stream is open
                            // input.available() is unreliable for HTTP streams
                            return input != null ? 8192 : 0;
                        }

                        @Override
                        protected void produceData(final StreamChannel<ByteBuffer> channel) throws IOException {
                            try {
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
                                    Log.w(getClass().getName(), "Input stream is null, ending stream");
                                    channel.endStream();
                                }
                            } catch (IOException e) {
                                Log.e(getClass().getName(), "Error reading external content", e);
                                if (input != null) {
                                    try {
                                        input.close();
                                    } catch (IOException ignored) {
                                    }
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
                                try {
                                    input.close();
                                } catch (IOException ignored) {
                                }
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
                                result = new AbstractBinAsyncEntityProducer(8192, ContentType.parse(getMimeType().toString())) {
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
