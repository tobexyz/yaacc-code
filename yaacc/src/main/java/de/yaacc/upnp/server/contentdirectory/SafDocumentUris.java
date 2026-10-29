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
final class SafDocumentUris {

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
