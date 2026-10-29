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
package de.yaacc.upnp.server.configuration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.yaacc.upnp.server.contentdirectory.SafDirectoryEntry;

/** Node data built from a SAF listing or from files: names, folder flags, uris and the order. Plain JVM. */
public class TreeNodeFactoryTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static SafDirectoryEntry entry(String name, String id, String mime) {
        return SafDirectoryEntry.fromProviderRow(name, id, mime, 0, 0);
    }

    @Test
    public void safChildrenAreOnlyTheFoldersInTheProvidersOrder() {
        List<SafDirectoryEntry> entries = Arrays.asList(
                entry("zeta.jpg", "primary:z", "image/jpeg"),
                entry("Beta", "primary:b", SafDirectoryEntry.MIME_TYPE_DIR),
                entry("Alpha", "primary:a", SafDirectoryEntry.MIME_TYPE_DIR),
                entry("beta.mp3", "primary:m", "audio/mpeg"));

        List<TreeNode> nodes = TreeNodeFactory.safChildren(entries, e -> "content://x/document/" + e.documentId, 7);

        assertEquals(2, nodes.size());
        assertEquals("Beta", nodes.get(0).getValue().getName());
        assertEquals("Alpha", nodes.get(1).getValue().getName());
        assertTrue(nodes.get(0).getValue().isDirectory());
        assertTrue(nodes.get(1).getValue().isDirectory());
        for (TreeNode node : nodes) {
            assertTrue(node.getValue().isSaf());
            assertEquals(7, node.getLayoutId());
        }
        assertEquals("content://x/document/primary:a", nodes.get(1).getValue().getPath());
    }

    @Test
    public void aSafFolderWithoutNameShowsItsUri() {
        String dir = SafDirectoryEntry.MIME_TYPE_DIR;
        List<TreeNode> nodes = TreeNodeFactory.safChildren(
                List.of(entry(null, "id", dir)), e -> "content://x/document/id", 1);
        assertEquals("content://x/document/id", nodes.get(0).getValue().getName());
        assertEquals("", TreeNodeFactory.safChildren(
                List.of(entry("", "id", dir)), e -> "content://x/document/id", 1).get(0).getValue().getName());
    }

    @Test
    public void thousandsOfFilesBecomeNoNodesAndTheFoldersAmongThemStay() {
        List<SafDirectoryEntry> entries = new ArrayList<>();
        for (int i = 0; i < 6000; i++) {
            entries.add(entry("f" + i, "id" + i, i % 1000 == 0 ? SafDirectoryEntry.MIME_TYPE_DIR : "image/jpeg"));
        }
        List<TreeNode> nodes = TreeNodeFactory.safChildren(entries, e -> "u/" + e.documentId, 1);
        assertEquals(6, nodes.size());
        assertEquals("f5000", nodes.get(5).getValue().getName());
    }

    @Test
    public void safRootShowsTheNameOrTheUri() {
        TreeNode named = TreeNodeFactory.safRoot("content://x/document/r", "DCIM", true, 1);
        assertEquals("DCIM", named.getValue().getName());
        assertEquals("content://x/document/r", named.getValue().getPath());
        assertTrue(named.getValue().isSaf());
        assertTrue(named.getValue().isDirectory());
        assertEquals("content://x/document/r", TreeNodeFactory.safRoot("content://x/document/r", null, true, 1).getValue().getName());
    }

    @Test
    public void fileChildrenAreOnlyTheExistingFoldersInTheOrderOfTheArray() throws Exception {
        File folder = temp.newFolder("sub");
        File other = temp.newFolder("another");
        File file = temp.newFile("a.txt");
        File missing = new File(temp.getRoot(), "gone");

        List<TreeNode> nodes = TreeNodeFactory.fileChildren(new File[]{file, missing, folder, null, other}, 3);

        assertEquals(2, nodes.size());
        TreeNodeData first = nodes.get(0).getValue();
        assertEquals("sub", first.getName());
        assertEquals(folder.getAbsolutePath(), first.getPath());
        assertFalse(first.isSaf());
        assertTrue(first.isDirectory());
        assertEquals("another", nodes.get(1).getValue().getName());
    }

    @Test
    public void aMissingFileHasNoNode() {
        assertNull(TreeNodeFactory.fileNode(new File(temp.getRoot(), "gone"), 1));
        assertNull(TreeNodeFactory.fileNode(null, 1));
    }
}
