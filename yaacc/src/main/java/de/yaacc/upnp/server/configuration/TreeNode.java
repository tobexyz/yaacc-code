/*
 *
 * Copyright (C) 2025 Tobias Schoene www.yaacc.de
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
/*
 *
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

import java.util.ArrayList;
import java.util.List;

/**
 * TreeNode is a container for the value to represent a node on the TreeView.
 * <p>
 * All methods are for the main thread. A folder's children are read on a worker thread and handed
 * over with {@link #finishLoading}; the load generation makes a result that arrives after the node
 * was collapsed (or loaded again) harmless.
 */
public class TreeNode {

    /** Whether the children of a folder were read: unknown, being read, or read (then possibly none). */
    public enum LoadState {NOT_LOADED, LOADING, LOADED}

    private final TreeNodeData value;
    private TreeNode parent;
    private final List<TreeNode> children;
    private final int layoutId;
    private int level;
    private boolean isExpanded;
    private boolean isSelected;
    private LoadState loadState = LoadState.NOT_LOADED;
    private int loadGeneration;

    public TreeNode(TreeNodeData value, int layoutId) {
        this.value = value;
        this.parent = null;
        this.children = new ArrayList<>();
        this.layoutId = layoutId;
        this.level = 0;
        this.isExpanded = false;
        this.isSelected = false;
    }

    public void addChild(TreeNode child) {
        child.setParent(this);
        child.setLevel(level + 1);
        children.add(child);
        updateNodeChildrenDepth(child);
    }

    /** Adds all children in one go (one array growth, no per-child list work). */
    public void addChildren(List<TreeNode> newChildren) {
        children.addAll(newChildren);
        for (TreeNode child : newChildren) {
            child.setParent(this);
            child.setLevel(level + 1);
            updateNodeChildrenDepth(child);
        }
    }

    public TreeNodeData getValue() {
        return value;
    }

    public void setParent(TreeNode parent) {
        this.parent = parent;
    }

    public TreeNode getParent() {
        return parent;
    }

    public List<TreeNode> getChildren() {
        return children;
    }

    public int getLayoutId() {
        return layoutId;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public int getLevel() {
        return level;
    }

    public void setExpanded(boolean expanded) {
        isExpanded = expanded;
    }

    public boolean isExpanded() {
        return isExpanded;
    }

    public void setSelected(boolean selected) {
        isSelected = selected;
    }

    public boolean isSelected() {
        return isSelected;
    }

    /**
     * Starts reading the children.
     *
     * @return the generation the result has to present to {@link #finishLoading} / {@link #failLoading}
     */
    public int startLoading() {
        loadGeneration++;
        loadState = LoadState.LOADING;
        return loadGeneration;
    }

    /**
     * Gives up a running load (the user collapsed the node): its result will be ignored.
     *
     * @return true if a load was running
     */
    public boolean cancelLoading() {
        if (loadState != LoadState.LOADING) {
            return false;
        }
        loadGeneration++;
        loadState = LoadState.NOT_LOADED;
        return true;
    }

    /**
     * Takes over the children a load has read.
     *
     * @return false (and nothing changed) if the load was cancelled, restarted or is already finished
     */
    public boolean finishLoading(int generation, List<TreeNode> loadedChildren) {
        if (loadState != LoadState.LOADING || generation != loadGeneration) {
            return false;
        }
        addChildren(loadedChildren);
        loadState = LoadState.LOADED;
        return true;
    }

    /**
     * A load could not read the folder: it stays unknown, so the next click asks again.
     *
     * @return false if the result was stale
     */
    public boolean failLoading(int generation) {
        if (loadState != LoadState.LOADING || generation != loadGeneration) {
            return false;
        }
        loadState = LoadState.NOT_LOADED;
        return true;
    }

    public boolean isLoading() {
        return loadState == LoadState.LOADING;
    }

    public boolean isLoaded() {
        return loadState == LoadState.LOADED;
    }

    /** The folder was read and has no children - the only case in which the expand symbol is hidden. */
    public boolean isKnownEmpty() {
        return loadState == LoadState.LOADED && children.isEmpty();
    }

    private void updateNodeChildrenDepth(TreeNode node) {
        if (node.getChildren().isEmpty()) return;
        for (TreeNode child : node.getChildren()) {
            child.setLevel(node.getLevel() + 1);
            updateNodeChildrenDepth(child);
        }
    }
}
