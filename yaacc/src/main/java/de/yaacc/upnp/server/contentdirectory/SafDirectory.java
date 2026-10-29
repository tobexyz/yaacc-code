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

import java.util.Collections;
import java.util.List;

/**
 * The result of listing one SAF folder: whether the folder itself is usable plus its children
 * (see {@link SafDirectoryLister}). Immutable and shared between Browse requests through the
 * {@link SafListingCache}, so the entry list is unmodifiable.
 *
 * @author Tobias Schoene (tobexyz)
 */
public final class SafDirectory {

    /** The folder's document uri (tree uri plus document id) children uris are built from; null if unknown. */
    public final String rootUri;
    /** The folder exists and is a directory. */
    public final boolean directory;
    /** The app may read the folder (what {@code DocumentFile.canRead()} said). */
    public final boolean readable;
    /** The children in the order the provider returned them. */
    public final List<SafDirectoryEntry> entries;
    private final boolean failed;

    private SafDirectory(String rootUri, boolean directory, boolean readable, List<SafDirectoryEntry> entries, boolean failed) {
        this.rootUri = rootUri;
        this.directory = directory;
        this.readable = readable;
        this.entries = Collections.unmodifiableList(entries);
        this.failed = failed;
    }

    public static SafDirectory listed(String rootUri, boolean readable, List<SafDirectoryEntry> entries) {
        return new SafDirectory(rootUri, true, readable, entries, false);
    }

    /** The folder is there but the query for its children failed: shown as empty, never cached. */
    public static SafDirectory listingFailed(String rootUri, boolean readable) {
        return new SafDirectory(rootUri, true, readable, Collections.emptyList(), true);
    }

    /** No folder, or not a directory (uri not valid any more, permission lost): shown as empty, never cached. */
    public static SafDirectory notADirectory(String rootUri) {
        return new SafDirectory(rootUri, false, false, Collections.emptyList(), false);
    }

    /** Only a complete listing of a readable directory is worth keeping; everything else is asked again. */
    public boolean isCacheable() {
        return directory && readable && !failed;
    }

    /** Cost of keeping this listing in the cache: one per entry, so a folder of 50000 files weighs 50000. */
    public int weight() {
        return entries.size() + 1;
    }
}
