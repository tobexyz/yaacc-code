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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class SafDocumentUrisTest {

    private static final String AUTH = "content://com.android.externalstorage.documents";
    private static final String TREE = AUTH + "/tree/primary%3ADCIM";
    private static final String ROOT = TREE + "/document/primary%3ADCIM";
    private static final String CAMERA = TREE + "/document/primary%3ADCIM%2FCamera";
    private static final String SUB = TREE + "/document/primary%3ADCIM%2FCamera%2F2026";
    private static final String FILE = TREE + "/document/primary%3ADCIM%2FCamera%2FIMG_1.jpg";

    // ---- object ids ----

    @Test
    public void shortIdIsWhatFollowsThePrefix() {
        String prefix = ContentDirectoryIDs.SAF_PREFIX.getId();
        assertEquals("17", SafDocumentUris.shortIdOf(prefix + "17"));
        assertEquals("t3", SafDocumentUris.shortIdOf(prefix + "t3"));
        assertEquals(prefix + "17", SafDocumentUris.objectIdOf("17"));
        assertTrue(SafDocumentUris.isSafObjectId(prefix + "1"));
    }

    @Test
    public void otherIdsHaveNoShortId() {
        assertNull("the SAF folder itself", SafDocumentUris.shortIdOf(ContentDirectoryIDs.SAF_FOLDER.getId()));
        assertNull("nothing behind the prefix", SafDocumentUris.shortIdOf(ContentDirectoryIDs.SAF_PREFIX.getId()));
        assertNull(SafDocumentUris.shortIdOf(ContentDirectoryIDs.ROOT.getId()));
        assertNull(SafDocumentUris.shortIdOf("0"));
        assertNull(SafDocumentUris.shortIdOf(null));
        assertFalse(SafDocumentUris.isSafObjectId(ContentDirectoryIDs.MUSIC_FOLDER.getId()));
    }

    @Test
    public void theShortIdIsNotBase64() {
        // the former browseMeta decoded it as Base64 of the uri: a numeric short id is just a number
        assertEquals("42", SafDocumentUris.shortIdOf(ContentDirectoryIDs.SAF_PREFIX.getId() + "42"));
    }

    // ---- document uris ----

    @Test
    public void parsesATreeDocumentUri() {
        SafDocumentUris.Parsed parsed = SafDocumentUris.parse(SUB);
        assertNotNull(parsed);
        assertEquals(TREE, parsed.treeUri);
        assertEquals("primary:DCIM", parsed.treeId);
        assertEquals("primary:DCIM/Camera/2026", parsed.documentId);
        assertFalse(parsed.isTreeRoot());
        assertTrue(SafDocumentUris.parse(ROOT).isTreeRoot());
    }

    @Test
    public void otherUrisAreNotTreeDocuments() {
        assertNull(SafDocumentUris.parse(null));
        assertNull("a plain tree uri", SafDocumentUris.parse(TREE));
        assertNull(SafDocumentUris.parse("content://media/external/images/media/5"));
        assertNull(SafDocumentUris.parse("file:///sdcard/x"));
        assertNull(SafDocumentUris.parse(TREE + "/document/"));
    }

    @Test
    public void parentOfPathLikeIdsIsTheFolderAbove() {
        assertEquals("primary:DCIM/Camera", SafDocumentUris.parentDocumentIdFromId(SafDocumentUris.parse(SUB)));
        assertEquals("primary:DCIM/Camera", SafDocumentUris.parentDocumentIdFromId(SafDocumentUris.parse(FILE)));
        assertEquals("a direct child of the granted folder", "primary:DCIM", SafDocumentUris.parentDocumentIdFromId(SafDocumentUris.parse(CAMERA)));
    }

    @Test
    public void theGrantedFolderHasNoParentInTheTree() {
        assertNull(SafDocumentUris.parentDocumentIdFromId(SafDocumentUris.parse(ROOT)));
    }

    @Test
    public void opaqueIdsNeedTheProvidersPath() {
        String opaque = AUTH + "/tree/1234/document/5678";
        SafDocumentUris.Parsed parsed = SafDocumentUris.parse(opaque);
        assertNull(SafDocumentUris.parentDocumentIdFromId(parsed));
        assertEquals("999", SafDocumentUris.parentFromPath(List.of("1234", "999", "5678"), "5678"));
        assertEquals("1234", SafDocumentUris.parentFromPath(List.of("1234", "5678"), "5678"));
        assertNull("the document is the top", SafDocumentUris.parentFromPath(List.of("5678"), "5678"));
        assertNull(SafDocumentUris.parentFromPath(List.of("1234"), "5678"));
        assertNull(SafDocumentUris.parentFromPath(null, "5678"));
    }

    @Test
    public void aParentUriIsBuiltLikeTheListingBuildsChildUris() {
        // short ids are keyed by the uri string, so the parent's uri must be written exactly as the listing wrote it
        SafDocumentUris.Parsed parsed = SafDocumentUris.parse(FILE);
        assertEquals(CAMERA, parsed.documentUri("primary:DCIM/Camera"));
        assertEquals(ROOT, parsed.documentUri("primary:DCIM"));
        assertEquals(SUB, parsed.documentUri("primary:DCIM/Camera/2026"));
    }

    @Test
    public void parentChainEndsAtTheGrantedFolder() {
        String uri = FILE;
        int steps = 0;
        while (true) {
            SafDocumentUris.Parsed parsed = SafDocumentUris.parse(uri);
            String parent = SafDocumentUris.parentDocumentIdFromId(parsed);
            if (parent == null) {
                assertTrue(parsed.isTreeRoot());
                break;
            }
            uri = parsed.documentUri(parent);
            steps++;
        }
        assertEquals("file -> Camera -> DCIM", 2, steps);
        assertEquals(ROOT, uri);
    }

    @Test
    public void aGrantedSubFolderIsItsOwnTree() {
        String tree = AUTH + "/tree/primary%3ADCIM%2FCamera";
        SafDocumentUris.Parsed root = SafDocumentUris.parse(tree + "/document/primary%3ADCIM%2FCamera");
        assertTrue(root.isTreeRoot());
        SafDocumentUris.Parsed sub = SafDocumentUris.parse(tree + "/document/primary%3ADCIM%2FCamera%2F2026");
        assertEquals("primary:DCIM/Camera", SafDocumentUris.parentDocumentIdFromId(sub));
    }

    // ---- percent encoding ----

    @Test
    public void encodesLikeAndroidUriEncode() {
        assertEquals("primary%3ADCIM%2FCamera", SafDocumentUris.encode("primary:DCIM/Camera"));
        assertEquals("a%20b%2Bc", SafDocumentUris.encode("a b+c"));
        assertEquals("%C3%A4%C3%B6", SafDocumentUris.encode("äö"));
        assertEquals("keep_-!.~'()*09azAZ", SafDocumentUris.encode("keep_-!.~'()*09azAZ"));
    }

    @Test
    public void decodeReversesEncode() {
        for (String value : new String[]{"primary:DCIM/Camera", "a b+c", "äöü.mp3", "100%", "x%2", "😀 smile"}) {
            assertEquals(value, SafDocumentUris.decode(SafDocumentUris.encode(value)));
        }
        assertEquals("malformed escape stays", "100%zz", SafDocumentUris.decode("100%zz"));
    }
}
