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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Object ids and document uris of the SAF content, as plain strings, so the id handling of
 * {@link SafFolderBrowser#browseMeta} is testable on the JVM.
 * <p>
 * An object id below the SAF folder is {@code SAF_PREFIX + shortId}; the short id maps to a document uri
 * through {@code SAFCacheManager.getUriForShortId} (it is NOT an encoding of the uri). A document uri inside a
 * granted tree has the form {@code content://<authority>/tree/<tree id>/document/<document id>} (both ids
 * percent-encoded); the granted folder itself is the document whose id equals the tree id.
 */
public final class SafDocumentUris {

    /** A tree uri, or a document uri in a tree: the authority, the tree id and (optional) the document id. */
    private static final Pattern TREE_BASED_URI = Pattern.compile("^content://([^/?#]+)/tree/([^/?#]+)(?:/document/([^/?#]+))?$");

    private static final Pattern DOCUMENT_URI = Pattern.compile("^(content://[^/?#]+/tree/([^/?#]+))/document/([^/?#]+)$");

    private SafDocumentUris() {
    }

    /** A document uri taken apart. */
    static final class Parsed {
        /** {@code content://<authority>/tree/<tree id>} as written (encoded). */
        final String treeUri;
        final String treeId;
        final String documentId;

        Parsed(String treeUri, String treeId, String documentId) {
            this.treeUri = treeUri;
            this.treeId = treeId;
            this.documentId = documentId;
        }

        /** The granted folder itself. */
        boolean isTreeRoot() {
            return treeId.equals(documentId);
        }

        /** The uri of another document of the same tree, written like {@code DocumentsContract.buildDocumentUriUsingTree}. */
        String documentUri(String otherDocumentId) {
            return treeUri + "/document/" + encode(otherDocumentId);
        }
    }

    /** Asks the document provider whether a document is somewhere below another one (for ids that are not paths). */
    public interface ChildCheck {
        boolean isChild(String parentDocumentUri, String childDocumentUri);
    }

    /**
     * Whether a document is in one of the folders the user selected for the server. The selection holds the uri of the ticked folder
     * (a granted tree, or a folder of a tree); a short id of the SAF cache stays valid after its folder was deselected (and the
     * permission of the app for the tree stays), so the id alone says nothing about what may be shown.
     * <p>
     * A document is in a selected folder when it is that folder, or its id continues the folder's id with {@code /} (the ids of the
     * external storage are paths), or the whole tree is selected, or the provider says it is a child ({@code check}, for ids that are
     * no paths; without it such a document is only found when the whole tree is selected).
     *
     * @param documentUri      a tree uri or a document uri in a tree ({@code content://authority/tree/id[/document/id]})
     * @param selectedFolders  the folders of the server settings, in the same forms
     * @param check            the provider's answer for ids that are no paths, may be null
     * @return false for anything else, a uri that is no tree uri included
     */
    public static boolean isInSelectedTrees(String documentUri, java.util.Collection<String> selectedFolders, ChildCheck check) {
        Matcher document = documentUri == null ? null : TREE_BASED_URI.matcher(documentUri);
        if (document == null || !document.matches() || selectedFolders == null) {
            return false;
        }
        String treeId = decode(document.group(2));
        String documentId = document.group(3) == null ? treeId : decode(document.group(3));
        for (String selected : selectedFolders) {
            Matcher folder = selected == null ? null : TREE_BASED_URI.matcher(selected);
            if (folder == null || !folder.matches() || !folder.group(1).equalsIgnoreCase(document.group(1))
                    || !decode(folder.group(2)).equals(treeId)) {
                continue;
            }
            String folderId = folder.group(3) == null ? treeId : decode(folder.group(3));
            if (folderId.equals(treeId) // the whole tree
                    || documentId.equals(folderId) || documentId.startsWith(folderId + "/")
                    || (check != null && check.isChild(selected, documentUri))) {
                return true;
            }
        }
        return false;
    }

    /** Whether the document is itself one of the selected folders (the top of a selection: its parent is the SAF folder, not a document). */
    public static boolean isSelectedFolder(String documentUri, java.util.Collection<String> selectedFolders) {
        Matcher document = documentUri == null ? null : TREE_BASED_URI.matcher(documentUri);
        if (document == null || !document.matches() || selectedFolders == null) {
            return false;
        }
        String treeId = decode(document.group(2));
        String documentId = document.group(3) == null ? treeId : decode(document.group(3));
        for (String selected : selectedFolders) {
            Matcher folder = selected == null ? null : TREE_BASED_URI.matcher(selected);
            if (folder != null && folder.matches() && folder.group(1).equalsIgnoreCase(document.group(1))
                    && decode(folder.group(2)).equals(treeId)
                    && (folder.group(3) == null ? treeId : decode(folder.group(3))).equals(documentId)) {
                return true;
            }
        }
        return false;
    }

    // ---- object ids ----

    /** @return true if the object id is one of a SAF folder or item (below the SAF folder), not the SAF folder itself */
    static boolean isSafObjectId(String objectId) {
        return shortIdOf(objectId) != null;
    }

    /** @return the short id of an object id {@code SAF_PREFIX + shortId}; null for anything else (also an empty short id) */
    static String shortIdOf(String objectId) {
        String prefix = ContentDirectoryIDs.SAF_PREFIX.getId();
        if (objectId == null || !objectId.startsWith(prefix) || objectId.length() == prefix.length()) {
            return null;
        }
        return objectId.substring(prefix.length());
    }

    static String objectIdOf(String shortId) {
        return ContentDirectoryIDs.SAF_PREFIX.getId() + shortId;
    }

    // ---- document uris ----

    /** @return the parts of a tree based document uri, null if it is another kind of uri (a plain tree uri, a file) */
    static Parsed parse(String documentUri) {
        if (documentUri == null) {
            return null;
        }
        Matcher matcher = DOCUMENT_URI.matcher(documentUri);
        if (!matcher.matches()) {
            return null;
        }
        return new Parsed(matcher.group(1), decode(matcher.group(2)), decode(matcher.group(3)));
    }

    /**
     * The parent folder's document id as far as the ids themselves tell: path like ids ({@code primary:DCIM/Camera}
     * below the tree {@code primary:DCIM}) name their parent. Null for the granted folder (it has no parent in the tree)
     * and for ids that are not path like (ask the provider then, see {@link #parentFromPath}).
     */
    static String parentDocumentIdFromId(Parsed document) {
        if (document.isTreeRoot()) {
            return null;
        }
        String treeId = document.treeId;
        String id = document.documentId;
        if (id.startsWith(treeId + "/")) {
            return id.substring(0, id.lastIndexOf('/'));
        }
        return null;
    }

    /**
     * The parent from the provider's path of the document ({@code DocumentsContract.findDocumentPath}: document ids
     * from the top of the tree down to the document), null if the path does not contain the document behind another one.
     */
    static String parentFromPath(List<String> path, String documentId) {
        if (path == null || documentId == null) {
            return null;
        }
        int index = path.lastIndexOf(documentId);
        return index > 0 ? path.get(index - 1) : null;
    }

    // ---- percent encoding as android.net.Uri.encode(s) does it ----

    /** Letters, digits and {@code _-!.~'()*} stay, everything else becomes UTF-8 bytes as upper case %XX. */
    static String encode(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "_-!.~'()*".indexOf(c) >= 0) {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16))).append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return out.toString();
    }

    /** UTF-8 percent decoding; a malformed escape stays as written. */
    static String decode(String value) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(value.length());
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length() && isHex(value.charAt(i + 1)) && isHex(value.charAt(i + 2))) {
                bytes.write(Integer.parseInt(value.substring(i + 1, i + 3), 16));
                i += 3;
            } else {
                int codePoint = value.codePointAt(i);
                byte[] utf8 = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8);
                bytes.write(utf8, 0, utf8.length);
                i += Character.charCount(codePoint);
            }
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
