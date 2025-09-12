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
package de.yaacc.upnp.server;


import java.io.File;
import java.util.LinkedList;

/**
 * TreeNode is a container for the value to represent a node on the TreeView
 */
public class TreeNode {

    private File value;
    private TreeNode parent;
    private LinkedList<TreeNode> children;
    private int layoutId;
    private int level;
    private boolean isExpanded;
    private boolean isSelected;

    public TreeNode(File value, int layoutId) {
        this.value = value;
        this.parent = null;
        this.children = new LinkedList<>();
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

    public void setValue(File value) {
        this.value = value;
    }

    public File getValue() {
        return value;
    }

    public void setParent(TreeNode parent) {
        this.parent = parent;
    }

    public TreeNode getParent() {
        return parent;
    }

    public LinkedList<TreeNode> getChildren() {
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

    private void updateNodeChildrenDepth(TreeNode node) {
        if (node.getChildren().isEmpty()) return;
        for (TreeNode child : node.getChildren()) {
            child.setLevel(node.getLevel() + 1);
            updateNodeChildrenDepth(child);
        }
    }
}

