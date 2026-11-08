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

package de.yaacc.share;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The files of one picked folder (not its sub folders): turns the rows a documents provider
 * answered into {@link PickedQueue.Entry} values. The query itself (Android) sits behind
 * {@link RowSource}, so this logic needs no Android classes. At most {@link #MAX_ROWS} rows are
 * read, directories are skipped, text of the provider is cleaned.
 */
public final class FolderListing {

    /** The document type of a directory (the value of {@code Document.MIME_TYPE_DIR}). */
    public static final String MIME_TYPE_DIR = "vnd.android.document/directory";

    /** The most rows read from a folder (the listing of a big folder is slow). */
    public static final int MAX_ROWS = 5000;

    /** The longest document id that is kept (a longer one cannot be a real file; the row is dropped). */
    public static final int MAX_ID_CHARS = 1024;

    /** The longest display name that is kept (cut), as for a single picked file. */
    public static final int MAX_NAME_CHARS = 1024;

    private FolderListing() {
    }

    /** One child of the folder as the provider reports it. */
    public static final class Row {
        private final String documentId;
        private final String displayName;
        private final String mimeType;
        private final long sizeBytes;

        public Row(String documentId, String displayName, String mimeType, long sizeBytes) {
            this.documentId = documentId;
            this.displayName = displayName;
            this.mimeType = mimeType;
            this.sizeBytes = sizeBytes;
        }

        /**
         * A row from what a provider answered, with every text capped before it is kept: a document
         * id over {@link #MAX_ID_CHARS} or a type over {@link SharedContentPolicy#MAX_MIME_LENGTH}
         * characters is dropped (null, so the row is skipped or not a media file), a name is cut at
         * {@link #MAX_NAME_CHARS}. A hostile provider cannot make the listing keep megabytes.
         */
        public static Row capped(String documentId, String displayName, String mimeType, long sizeBytes) {
            String id = documentId != null && documentId.length() <= MAX_ID_CHARS ? documentId : null;
            String name = displayName != null && displayName.length() > MAX_NAME_CHARS
                    ? displayName.substring(0, MAX_NAME_CHARS) : displayName;
            String mime = mimeType != null && mimeType.length() <= SharedContentPolicy.MAX_MIME_LENGTH
                    ? mimeType : null;
            return new Row(id, name, mime, sizeBytes);
        }
    }

    /** Reads the children of the folder, at most {@code maxRows}; null when it could not be read. */
    public interface RowSource {
        List<Row> read(int maxRows);
    }

    /** Builds the uri of a child from its document id; null when it cannot be built. */
    public interface UriBuilder {
        String uriOf(String documentId);
    }

    public static final class Result {
        private final List<PickedQueue.Entry> entries;
        private final boolean truncated;
        private final boolean failed;

        Result(List<PickedQueue.Entry> entries, boolean truncated, boolean failed) {
            this.entries = Collections.unmodifiableList(entries);
            this.truncated = truncated;
            this.failed = failed;
        }

        public List<PickedQueue.Entry> entries() {
            return entries;
        }

        /** True when the folder has more rows than were read. */
        public boolean isTruncated() {
            return truncated;
        }

        /** True when the folder could not be read at all. */
        public boolean isFailed() {
            return failed;
        }
    }

    public static Result list(RowSource source, UriBuilder uris) {
        List<Row> rows;
        try {
            rows = source.read(MAX_ROWS + 1);
        } catch (RuntimeException e) {
            return new Result(new ArrayList<>(), false, true);
        }
        if (rows == null) {
            return new Result(new ArrayList<>(), false, true);
        }
        boolean truncated = rows.size() > MAX_ROWS;
        List<PickedQueue.Entry> entries = new ArrayList<>();
        int seen = 0;
        for (Row row : rows) {
            if (seen++ >= MAX_ROWS) {
                break;
            }
            if (row == null || row.documentId == null || row.documentId.isEmpty()
                    || MIME_TYPE_DIR.equals(row.mimeType)) {
                continue;
            }
            String uri;
            try {
                uri = uris.uriOf(row.documentId);
            } catch (RuntimeException e) {
                continue;
            }
            if (uri == null || uri.length() > SharedContentPolicy.MAX_URI_LENGTH) {
                continue;
            }
            entries.add(new PickedQueue.Entry(uri, row.displayName, row.mimeType, row.sizeBytes));
        }
        return new Result(entries, truncated, false);
    }
}
