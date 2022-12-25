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

import org.seamless.util.MimeType;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;

import de.yaacc.R;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class YaaccUpnpServerServlet extends HttpServlet {

    private static String HEAVY_RESOURCE
            = "This is some heavy resource that will be served in an async way";
    private Context context;

    public YaaccUpnpServerServlet(Context context) {
        this.context = context;
    }

    private Context getContext() {
        return context;
    }

    @Override
    protected void doHead(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        //FIXME create head method
        doGet(req, resp);
    }

    @Override
    protected void doGet(
            HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        Uri requestUri = Uri.parse(request.getRequestURI());
        String contentId = requestUri.getQueryParameter("id");
        contentId = contentId == null ? "" : contentId;
        String albumId = requestUri.getQueryParameter("album");
        albumId = albumId == null ? "" : albumId;
        String thumbId = requestUri.getQueryParameter("thumb");
        thumbId = thumbId == null ? "" : thumbId;
        if (contentId.equals("") && albumId.equals("") && thumbId.equals("")) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("text/html");
            String result = "<html><body><h1>Access denied</h1></body></html>";
            response.getWriter().write(result);
            Log.d(getClass().getName(), "end doService: Access denied");
            return;
        }
        YaaccUpnpServerServlet.ContentHolder contentHolder = null;
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
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            String result = "<html><body><h1>Resource with id " + contentId + albumId
                    + thumbId + " not found</h1></body></html>";
            response.setContentType("text/html");
            response.getWriter().write(result);
        } else {
            AsyncContext async = request.startAsync();
            ServletOutputStream out = response.getOutputStream();
            response.setContentType(contentHolder.getMimeType().toString());
            ByteBuffer content = contentHolder.getContent();
            out.setWriteListener(new WriteListener() {
                @Override
                public void onWritePossible() throws IOException {
                    while (out.isReady()) {
                        if (!content.hasRemaining()) {
                            response.setStatus(HttpServletResponse.SC_OK);
                            async.complete();
                            return;
                        }
                        out.write(content.get());
                    }
                }

                @Override
                public void onError(Throwable t) {
                    getServletContext().log("Async Error", t);
                    async.complete();
                }
            });


        }
        Log.d(getClass().getName(), "end doService: ");
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
        Cursor mFilesCursor = getContext().getContentResolver().query(
                MediaStore.Files.getContentUri("external"), projection,
                selection, selectionArgs, null);

        if (mFilesCursor != null) {
            mFilesCursor.moveToFirst();
            while (!mFilesCursor.isAfterLast()) {
                @SuppressLint("Range") String dataUri = mFilesCursor.getString(mFilesCursor
                        .getColumnIndex(MediaStore.Files.FileColumns.DATA));

                @SuppressLint("Range") String mimeTypeStr = mFilesCursor.getString(mFilesCursor
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
        mFilesCursor.close();
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
        Cursor cursor = getContext().getContentResolver().query(
                MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, projection,
                selection, selectionArgs, null);

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
        cursor.close();
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
        Long id = null;
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
        Drawable drawable = getContext().getResources().getDrawable(
                R.drawable.yaacc192_32);
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
        private String uri;
        private MimeType mimeType;
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

        public ByteBuffer getContent() throws IOException {

            ByteBuffer result = null;
            if (getUri() != null && !getUri().equals("")) {
                FileInputStream inputStream = new FileInputStream(getUri());
                ByteBuffer byteBuffer = ByteBuffer.allocate(inputStream.available());
                Channels.newChannel(inputStream).read(byteBuffer);
                Log.d(getClass().getName(), "Return file-Uri: " + getUri()
                        + "Mimetype: " + getMimeType());
                result = byteBuffer;
            } else if (content != null) {
                result = ByteBuffer.wrap(content);
            }
            return result;
        }
    }
}
