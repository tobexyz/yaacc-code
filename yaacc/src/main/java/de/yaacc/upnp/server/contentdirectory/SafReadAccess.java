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
 * The read permission of the children of ONE folder, asked from the system once per folder instead of once per child.
 * <p>
 * Why: {@code checkCallingOrSelfUriPermission} is a Binder call into the system server. The children of a folder are
 * addressed through the tree the folder is in ({@code content://<authority>/tree/<tree id>/document/<child id>}, see
 * {@link SafDirectoryLister#childDocumentUri}), and a grant the user gave with the system picker is a grant of the whole
 * tree: the system answers the same for the folder and for every document below it. So the answer for the folder's own
 * uri is the answer for all its children.
 * <p>
 * Assumption (documented in docs/tech.md, "Large SAF folders"): a granted folder implies granted children of the same tree.
 * It is only used in that direction. When the folder is NOT granted, or a child is not in the folder's tree (or the folder's
 * uri is not a tree document uri at all), the child is asked on its own, exactly as before, so the "[X]" flag of a child is
 * never more permissive than the system's answer for that child was. A child without a mime type is not readable
 * ({@code DocumentFile.canRead()}) whatever the permission says.
 * <p>
 * Not thread safe (one instance serves one Browse). No Android classes.
 */
final class SafReadAccess {

    /** Asks the system whether the app may read a document uri (one Binder call). */
    interface UriCheck {
        boolean granted(String documentUri);
    }

    private final String folderUri;
    /** {@code content://<authority>/tree/<tree id>/document/}, null if the folder's uri is not a tree document uri. */
    private final String childPrefix;
    private final UriCheck check;
    private Boolean folderGranted;
    private int folderChecks;
    private int entryChecks;

    SafReadAccess(String folderDocumentUri, UriCheck check) {
        this.folderUri = folderDocumentUri;
        this.check = check;
        SafDocumentUris.Parsed parsed = SafDocumentUris.parse(folderDocumentUri);
        this.childPrefix = parsed == null ? null : parsed.treeUri + "/document/";
    }

    /**
     * @param childDocumentUri the child's document uri
     * @param mimeType         the mime type the provider reported for it
     * @return what {@code DocumentFile.canRead()} would answer for the child
     */
    boolean canRead(String childDocumentUri, String mimeType) {
        if (mimeType == null || mimeType.isEmpty()) {
            return false;
        }
        if (childPrefix != null && childDocumentUri.startsWith(childPrefix) && folderGranted()) {
            return true;
        }
        entryChecks++;
        return check.granted(childDocumentUri);
    }

    private boolean folderGranted() {
        if (folderGranted == null) {
            folderChecks++;
            folderGranted = check.granted(folderUri);
        }
        return folderGranted;
    }

    /** Number of times the system was asked for the folder itself (at most 1). */
    int folderChecks() {
        return folderChecks;
    }

    /** Number of times the system was asked for a single child. */
    int entryChecks() {
        return entryChecks;
    }
}
