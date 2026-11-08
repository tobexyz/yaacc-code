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

/**
 * One child of a SAF folder as the document provider reported it in the single directory query
 * (see {@link SafDirectoryLister}): everything the ContentDirectory needs to filter, sort and page
 * a folder without asking the provider again per file. Immutable, no Android types, so the
 * filter/sort/page logic is testable on the plain JVM.
 *
 * @author Tobias Schoene (tobexyz)
 */
public final class SafDirectoryEntry {

    /** {@code DocumentsContract.Document.MIME_TYPE_DIR}, repeated here to stay free of Android types. */
    public static final String MIME_TYPE_DIR = "vnd.android.document/directory";

    /** Display name, may be null if the provider has none. */
    public final String name;
    /** The provider's document id; null for entries not read from a directory listing. */
    public final String documentId;
    /** The provider's raw mime type ({@link #MIME_TYPE_DIR} for a directory), may be null or empty. */
    public final String mimeType;
    /** Epoch millis, 0 if unknown. */
    public final long lastModified;
    /** Size in bytes, 0 if unknown. */
    public final long size;
    private final boolean directory;

    public SafDirectoryEntry(String name, String documentId, String mimeType, long lastModified, long size, boolean directory) {
        this.name = name;
        this.documentId = documentId;
        this.mimeType = mimeType;
        this.lastModified = lastModified;
        this.size = size;
        this.directory = directory;
    }

    /** An entry as one row of the directory query describes it: it is a directory if the mime type says so. */
    public static SafDirectoryEntry fromProviderRow(String name, String documentId, String mimeType, long lastModified, long size) {
        return new SafDirectoryEntry(name, documentId, mimeType, lastModified, size, MIME_TYPE_DIR.equals(mimeType));
    }

    public boolean isDirectory() {
        return directory;
    }
}
