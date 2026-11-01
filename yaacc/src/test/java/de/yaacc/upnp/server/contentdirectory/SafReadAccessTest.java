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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * {@link SafReadAccess}: one system call for the folder instead of one per child, and the old per child answer
 * wherever the folder's answer does not decide it. Plain JVM.
 */
public class SafReadAccessTest {

    private static final String TREE = "content://com.android.externalstorage.documents/tree/primary%3ADCIM";
    private static final String FOLDER = TREE + "/document/primary%3ADCIM%2F.thumbnails";

    private static String child(int i) {
        return TREE + "/document/primary%3ADCIM%2F.thumbnails%2F" + i;
    }

    /** The system: a set of granted uris (a tree grant covers the folder and everything below it), counting the calls. */
    private static final class FakeSystem implements SafReadAccess.UriCheck {
        final Set<String> grantedPrefixes = new HashSet<>();
        final Set<String> grantedExactly = new HashSet<>();
        int calls;

        @Override
        public boolean granted(String documentUri) {
            calls++;
            if (grantedExactly.contains(documentUri)) {
                return true;
            }
            for (String prefix : grantedPrefixes) {
                // a grant of a tree covers the paths below it, not another tree whose id starts the same
                if (documentUri.startsWith(prefix + "/")) {
                    return true;
                }
            }
            return false;
        }
    }

    @Test
    public void thousandsOfChildrenOfAGrantedFolderCostOneCheck() {
        FakeSystem system = new FakeSystem();
        system.grantedPrefixes.add(TREE);
        SafReadAccess access = new SafReadAccess(FOLDER, system);

        for (int i = 0; i < 5570; i++) {
            assertTrue(access.canRead(child(i), "image/jpeg"));
        }

        assertEquals(1, system.calls);
        assertEquals(1, access.folderChecks());
        assertEquals(0, access.entryChecks());
    }

    @Test
    public void aFolderThatIsNotGrantedAsksForEveryChildAsBefore() {
        FakeSystem system = new FakeSystem();
        system.grantedExactly.add(child(2));
        SafReadAccess access = new SafReadAccess(FOLDER, system);

        assertFalse(access.canRead(child(1), "image/jpeg"));
        assertTrue("a child that is granted on its own is readable", access.canRead(child(2), "image/jpeg"));
        assertFalse(access.canRead(child(3), "image/jpeg"));

        assertEquals("the folder once, then each child", 4, system.calls);
        assertEquals(1, access.folderChecks());
        assertEquals(3, access.entryChecks());
    }

    @Test
    public void aChildOutsideTheTreeOfTheFolderIsAskedOnItsOwn() {
        FakeSystem system = new FakeSystem();
        system.grantedPrefixes.add(TREE);
        SafReadAccess access = new SafReadAccess(FOLDER, system);
        String otherTree = "content://com.android.externalstorage.documents/tree/primary%3AMovies/document/primary%3AMovies%2Fa.mp4";

        assertTrue(access.canRead(child(1), "image/jpeg"));
        assertFalse("not covered by the grant of the folder's tree", access.canRead(otherTree, "video/mp4"));
        // a tree whose id merely starts like the folder's tree id is another tree
        assertFalse(access.canRead(TREE + "X/document/a", "image/jpeg"));

        assertEquals(1, access.folderChecks());
        assertEquals(2, access.entryChecks());
    }

    @Test
    public void aFolderUriThatIsNotATreeDocumentUriIsNeverUsedForTheChildren() {
        FakeSystem system = new FakeSystem();
        system.grantedPrefixes.add(TREE);
        SafReadAccess access = new SafReadAccess(TREE, system);

        assertTrue(access.canRead(child(1), "image/jpeg"));
        assertTrue(access.canRead(child(2), "image/jpeg"));

        assertEquals(0, access.folderChecks());
        assertEquals(2, access.entryChecks());
    }

    @Test
    public void aChildWithoutAMimeTypeIsNotReadableAndCostsNoCall() {
        FakeSystem system = new FakeSystem();
        system.grantedPrefixes.add(TREE);
        SafReadAccess access = new SafReadAccess(FOLDER, system);

        assertFalse(access.canRead(child(1), null));
        assertFalse(access.canRead(child(2), ""));

        assertEquals(0, system.calls);
    }

    @Test
    public void theAnswerIsTheSameAsAskingForEveryChildWhenTheGrantIsTheTree() {
        FakeSystem folderLevel = new FakeSystem();
        FakeSystem perChild = new FakeSystem();
        for (FakeSystem system : new FakeSystem[]{folderLevel, perChild}) {
            system.grantedPrefixes.add(TREE);
        }
        SafReadAccess access = new SafReadAccess(FOLDER, folderLevel);
        String[] mimes = {"image/jpeg", "", null, "application/octet-stream", "audio/mpeg"};

        for (int i = 0; i < 2000; i++) {
            String mime = mimes[i % mimes.length];
            boolean reference = perChild.granted(child(i)) && mime != null && !mime.isEmpty();
            assertEquals("child " + i + " with mime " + mime, reference, access.canRead(child(i), mime));
        }

        assertEquals(1, folderLevel.calls);
        assertEquals(2000, perChild.calls);
    }
}
