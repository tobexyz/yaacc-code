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

import java.util.LinkedList;
import java.util.List;

/**
 * Manager class for TreeNodes to easily apply operations on them
 * and to make it easy for testing and extending
 */
public class TreeNodeManager {

    /**
     * Collection to save the current tree nodes
     */
    private final LinkedList<TreeNode> rootsNodes;

    /**
     * Simple constructor
     */
    public TreeNodeManager() {
        this.rootsNodes = new LinkedList<>();
    }

    /**
     * Set the current visible tree nodes
     *
     * @param treeNodes New tree nodes
     */
    public void setTreeNodes(List<TreeNode> treeNodes) {
        rootsNodes.clear();
        rootsNodes.addAll(treeNodes);
    }

    /**
     * Get the Current visible Tree nodes
     *
     * @return The visible Tree nodes main
     */
    public List<TreeNode> getTreeNodes() {
        return rootsNodes;
    }

    /**
     * Get TreeNode from the current nodes by index
     *
     * @param index of node to get it
     * @return TreeNode from by index from current tree nodes if exists
     */
    public TreeNode get(int index) {
        return rootsNodes.get(index);
    }

    /**
     * Add new node to the current tree nodes
     *
     * @param node to add it to the current tree nodes
     * @return true of this node is added
     */
    public boolean addNode(TreeNode node) {
        return rootsNodes.add(node);
    }

    /**
     * Clear the current nodes and insert new nodes
     *
     * @param newNodes to update the current nodes with them
     */
    public void updateNodes(List<TreeNode> newNodes) {
        rootsNodes.clear();
        rootsNodes.addAll(newNodes);
    }

    /**
     * Delete one node from the visible nodes
     *
     * @param node to delete it from the current nodes
     * @return true of this node is deleted
     */
    public boolean removeNode(TreeNode node) {
        return rootsNodes.remove(node);
    }

    /**
     * Clear the current nodes
     */
    public void clearNodes() {
        rootsNodes.clear();
    }

    /**
     * Get the current number of visible nodes
     *
     * @return the size of visible nodes
     */
    public int size() {
        return rootsNodes.size();
    }

    /**
     * Collapsing node and all of his children
     *
     * @param node The node to collapse it
     * @return the index of this node if it exists in the list
     */
    public int collapseNode(TreeNode node) {
        int position = rootsNodes.indexOf(node);
        if (position != -1 && node.isExpanded()) {
            node.setExpanded(false);
            LinkedList<TreeNode> deletedParents = new LinkedList<>(node.getChildren());
            rootsNodes.removeAll(node.getChildren());
            for (int i = position + 1; i < rootsNodes.size(); i++) {
                TreeNode iNode = rootsNodes.get(i);
                if (deletedParents.contains(iNode.getParent())) {
                    deletedParents.add(iNode);
                    deletedParents.addAll(iNode.getChildren());
                }
            }
            rootsNodes.removeAll(deletedParents);
        }
        return position;
    }

    /**
     * Expanding node and all of his children
     *
     * @param node The node to expand it
     * @return the index of this node if it exists in the list
     */
    public int expandNode(TreeNode node) {
        int position = rootsNodes.indexOf(node);
        if (position != -1 && !node.isExpanded()) {
            node.setExpanded(true);
            rootsNodes.addAll(position + 1, node.getChildren());
            for (TreeNode child : node.getChildren()) {
                if (child.isExpanded()) updateExpandedNodeChildren(child);
            }
        }
        return position;
    }

    /**
     * Update the list for expanded node
     * to expand any child of his children that is already expanded before
     *
     * @param node that just expanded now
     */
    private void updateExpandedNodeChildren(TreeNode node) {
        int position = rootsNodes.indexOf(node);
        if (position != -1 && node.isExpanded()) {
            rootsNodes.addAll(position + 1, node.getChildren());
            for (TreeNode child : node.getChildren()) {
                if (child.isExpanded()) updateExpandedNodeChildren(child);
            }
        }
    }

    /**
     * @param node The node to collapse the branch of it
     * @return the index of this node if it exists in the list
     */
    public int collapseNodeBranch(TreeNode node) {
        int position = rootsNodes.indexOf(node);
        if (position != -1 && node.isExpanded()) {
            node.setExpanded(false);
            for (TreeNode child : node.getChildren()) {
                if (!child.getChildren().isEmpty()) collapseNodeBranch(child);
                rootsNodes.remove(child);
            }
        }
        return position;
    }

    /**
     * Expanding node full branches
     *
     * @param node The node to expand the branch of it
     * @return the index of this node if it exists in the list
     */
    public int expandNodeBranch(TreeNode node) {
        int position = rootsNodes.indexOf(node);
        if (position != -1 && !node.isExpanded()) {
            node.setExpanded(true);
            int index = position + 1;
            for (TreeNode child : node.getChildren()) {
                int before = rootsNodes.size();
                rootsNodes.add(index, child);
                expandNodeBranch(child);
                int after = rootsNodes.size();
                int diff = after - before;
                index += diff;
            }
        }
        return position;
    }

    /**
     * Expanding one node branch to until specific level
     *
     * @param node  to expand branch of it until level
     * @param level to expand node branches to it
     */
    public void expandNodeToLevel(TreeNode node, int level) {
        if (node.getLevel() <= level) expandNode(node);
        for (TreeNode child : node.getChildren()) {
            expandNodeToLevel(child, level);
        }
    }

    /**
     * Expanding all tree nodes branches to until specific level
     *
     * @param level to expand all nodes branches to it
     */
    public void expandNodesAtLevel(int level) {
        for (int i = 0; i < rootsNodes.size(); i++) {
            TreeNode node = rootsNodes.get(i);
            expandNodeToLevel(node, level);
        }
    }

    /**
     * Collapsing all nodes in the tree with their children
     */
    public void collapseAll() {
        List<TreeNode> treeNodes = new LinkedList<>();
        for (int i = 0; i < rootsNodes.size(); i++) {
            TreeNode root = rootsNodes.get(i);
            if (root.getLevel() == 0) {
                collapseNodeBranch(root);
                treeNodes.add(root);
            } else {
                root.setExpanded(false);
            }
        }
        updateNodes(treeNodes);
    }

    /**
     * Expanding all nodes in the tree with their children
     */
    public void expandAll() {
        for (int i = 0; i < rootsNodes.size(); i++) {
            TreeNode root = rootsNodes.get(i);
            expandNodeBranch(root);
        }
    }
}
