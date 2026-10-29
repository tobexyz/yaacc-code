/*
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
package de.yaacc.upnp.server.contentdirectory;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.text.TextUtils;

import androidx.documentfile.provider.DocumentFile;

import java.util.ArrayList;
import java.util.List;

import de.yaacc.util.YaaccLogger;

/**
 * Thin Android adapter that lists a SAF folder with ONE content provider query and keeps the
 * result in a process wide {@link SafListingCache}.
 * <p>
 * Why: {@code DocumentFile.listFiles()} only fetches the document ids, and then every
 * {@code isDirectory()}, {@code getName()}, {@code lastModified()}, {@code getType()},
 * {@code length()} of every child is one more query to the provider (androidx
 * {@code DocumentsContractApi19}). For a folder of thousands of files that are tens of thousands of
 * binder round trips per Browse request - see docs/tech.md, "Large SAF folders".
 * Here the id, name, mime type, last-modified and size of all children come back in one cursor.
 *
 * @author Tobias Schoene (tobexyz)
 */
public final class SafDirectoryLister {

    /** A cached listing is reused for this long; a later request lists the folder again. */
    static final long LISTING_TTL_MILLIS = 30_000L;
    /** Listings kept at the same time. */
    static final int MAX_CACHED_FOLDERS = 8;
    /** Sum of the cached folders' entries - bounds the memory (about 200 bytes per entry). */
    static final long MAX_CACHED_ENTRIES = 100_000L;
    /** A request waits at most this long for another request's listing of the same folder. */
    static final long MAX_WAIT_MILLIS = 120_000L;

    private static final String[] PROJECTION = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE
    };
    private static final int COLUMN_ID = 0;
    private static final int COLUMN_NAME = 1;
    private static final int COLUMN_MIME = 2;
    private static final int COLUMN_MODIFIED = 3;
    private static final int COLUMN_SIZE = 4;

    private static final SafListingCache<SafDirectory> CACHE = new SafListingCache<>(
            MAX_CACHED_FOLDERS, MAX_CACHED_ENTRIES, LISTING_TTL_MILLIS, MAX_WAIT_MILLIS,
            SystemClock::elapsedRealtime, SafDirectory::weight, SafDirectory::isCacheable);

    private SafDirectoryLister() {
    }

    /**
     * @param folderUri the folder's tree uri as the short id mapping holds it
     * @return the folder's listing from the cache or - if there is none or it is too old, or another
     * request is just listing it - from the provider, never null
     * @throws Exception only if waiting for another request's listing timed out or was interrupted
     */
    public static SafListingCache.Result<SafDirectory> list(Context context, String folderUri) throws Exception {
        return CACHE.get(folderUri, () -> load(context.getApplicationContext(), folderUri));
    }

    /** Forgets all cached listings - after a permission change or when the user clears the SAF cache. */
    public static void clearCache() {
        CACHE.clear();
    }

    /**
     * @return the uri {@code DocumentFile.listFiles()} would have given the child, which the short id
     * mapping and the metadata cache are keyed with
     */
    public static Uri childDocumentUri(Uri folderDocumentUri, String documentId) {
        return DocumentsContract.buildDocumentUriUsingTree(folderDocumentUri, documentId);
    }

    /**
     * What {@code DocumentFile.canRead()} answers, without the provider query for the mime type that
     * the listing already holds: read permission on the uri and a provider mime type that is not empty.
     */
    public static boolean canRead(Context context, Uri documentUri, String mimeType) {
        return context.checkCallingOrSelfUriPermission(documentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                == PackageManager.PERMISSION_GRANTED
                && !TextUtils.isEmpty(mimeType);
    }

    /**
     * The five listing values of ONE document (one provider query), for a BrowseMetadata request.
     *
     * @param documentUri a tree based document uri
     * @return null if the provider has no such document or the query failed
     */
    public static SafDirectoryEntry queryDocument(Context context, Uri documentUri) {
        try (Cursor cursor = context.getContentResolver().query(documentUri, PROJECTION, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            String documentId = cursor.getString(COLUMN_ID);
            return SafDirectoryEntry.fromProviderRow(
                    cursor.getString(COLUMN_NAME),
                    documentId != null ? documentId : DocumentsContract.getDocumentId(documentUri),
                    cursor.getString(COLUMN_MIME),
                    cursor.isNull(COLUMN_MODIFIED) ? 0L : cursor.getLong(COLUMN_MODIFIED),
                    cursor.isNull(COLUMN_SIZE) ? 0L : cursor.getLong(COLUMN_SIZE));
        } catch (Exception e) {
            YaaccLogger.w(SafDirectoryLister.class.getName(), "Query of " + documentUri + " failed: " + e);
            return null;
        }
    }

    /**
     * The document ids from the top of the tree down to the document (provider query), null if the provider cannot
     * tell ({@code DocumentsContract.findDocumentPath}, API 26).
     */
    public static List<String> findPath(Context context, Uri documentUri) {
        try {
            DocumentsContract.Path path = DocumentsContract.findDocumentPath(context.getContentResolver(), documentUri);
            return path == null ? null : path.getPath();
        } catch (Exception e) {
            YaaccLogger.d(SafDirectoryLister.class.getName(), "No document path for " + documentUri + ": " + e);
            return null;
        }
    }

    private static SafDirectory load(Context context, String folderUri) {
        DocumentFile folder;
        try {
            folder = DocumentFile.fromTreeUri(context, Uri.parse(folderUri));
        } catch (IllegalArgumentException e) {
            YaaccLogger.w(SafDirectoryLister.class.getName(), "Not a SAF tree uri: " + folderUri);
            return SafDirectory.notADirectory(null);
        }
        if (folder == null || !folder.isDirectory()) {
            return SafDirectory.notADirectory(folder == null ? null : folder.getUri().toString());
        }
        Uri documentUri = folder.getUri();
        boolean readable = folder.canRead();
        try {
            return SafDirectory.listed(documentUri.toString(), readable, queryChildren(context, documentUri));
        } catch (Exception e) {
            // same outcome as DocumentFile.listFiles(), which logs and returns nothing; not cached
            YaaccLogger.w(SafDirectoryLister.class.getName(), "Listing of " + folderUri + " failed: " + e);
            return SafDirectory.listingFailed(documentUri.toString(), readable);
        }
    }

    private static List<SafDirectoryEntry> queryChildren(Context context, Uri folderDocumentUri) {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                folderDocumentUri, DocumentsContract.getDocumentId(folderDocumentUri));
        try (Cursor cursor = context.getContentResolver().query(childrenUri, PROJECTION, null, null, null)) {
            if (cursor == null) {
                throw new IllegalStateException("provider returned no cursor");
            }
            List<SafDirectoryEntry> entries = new ArrayList<>(cursor.getCount());
            while (cursor.moveToNext()) {
                String documentId = cursor.getString(COLUMN_ID);
                if (documentId == null) {
                    continue;
                }
                entries.add(SafDirectoryEntry.fromProviderRow(
                        cursor.getString(COLUMN_NAME),
                        documentId,
                        cursor.getString(COLUMN_MIME),
                        cursor.isNull(COLUMN_MODIFIED) ? 0L : cursor.getLong(COLUMN_MODIFIED),
                        cursor.isNull(COLUMN_SIZE) ? 0L : cursor.getLong(COLUMN_SIZE)));
            }
            return entries;
        }
    }
}
