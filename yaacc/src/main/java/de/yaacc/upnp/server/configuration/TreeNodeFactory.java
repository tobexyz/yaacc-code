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

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import de.yaacc.upnp.server.contentdirectory.SafDirectoryEntry;

/**
 * Builds the tree nodes of a folder from what a worker thread has read: a SAF listing (one provider
 * query, see {@code SafDirectoryLister}) or the files of a directory. The children keep the order
 * of the source, as the tree always showed them. Plain JVM; nothing here is for the main thread -
 * file system calls happen here, but only on the thread of the caller.
 *
 * @author Tobias Schoene (tobexyz)
 */
final class TreeNodeFactory {

    private TreeNodeFactory() {
    }

    /** @return the node of a file or folder, or null if it is gone */
    static TreeNode fileNode(File file, int layoutId) {
        if (file == null || !file.exists()) {
            return null;
        }
        return new TreeNode(new TreeNodeData(file.getName(), file.getAbsolutePath(), false, file.isDirectory()), layoutId);
    }

    /**
     * The nodes of the existing folders among {@code files}, in their order. Only folders can be
     * selected as a shared path, so files are not shown at all.
     */
    static List<TreeNode> fileChildren(File[] files, int layoutId) {
        List<TreeNode> nodes = new ArrayList<>(files.length);
        for (File file : files) {
            if (file == null || !file.isDirectory()) {
                continue;
            }
            TreeNode node = fileNode(file, layoutId);
            if (node != null) {
                nodes.add(node);
            }
        }
        return nodes;
    }

    /** The node of a SAF tree root; a root without a display name shows its uri. */
    static TreeNode safRoot(String documentUri, String displayName, boolean directory, int layoutId) {
        return new TreeNode(new TreeNodeData(displayName != null ? displayName : documentUri, documentUri, true, directory), layoutId);
    }

    /**
     * The nodes of the folders among the entries of a SAF listing, in the provider's order (files
     * are not shown: only folders can be selected as a shared path). The folder flag is the
     * listing's own (mime type), so no row asks the provider again; a child without a display name
     * shows its uri.
     *
     * @param childUri the document uri of an entry, as the persisted selection stores it
     */
    static List<TreeNode> safChildren(List<SafDirectoryEntry> entries, Function<SafDirectoryEntry, String> childUri, int layoutId) {
        List<TreeNode> nodes = new ArrayList<>(entries.size());
        for (SafDirectoryEntry entry : entries) {
            if (!entry.isDirectory()) {
                continue;
            }
            String uri = childUri.apply(entry);
            nodes.add(new TreeNode(new TreeNodeData(entry.name != null ? entry.name : uri, uri, true, entry.isDirectory()), layoutId));
        }
        return nodes;
    }
}
