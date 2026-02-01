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

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * Custom RecyclerView.Adapter used to provide a tree view features on any RecyclerView
 */
public class TreeViewAdapter extends RecyclerView.Adapter<TreeViewHolder> {

    /**
     * Interface definition for a callback to be invoked when a TreeNode has been clicked and held.
     */
    public interface OnTreeNodeClickListener {
        /**
         * Called when a TreeNode has been clicked.
         *
         * @param treeNode The current clicked node
         * @param view     The view that was clicked and held.
         */
        void onTreeNodeClick(TreeNode treeNode, View view);
    }

    /**
     * Interface definition for a callback to be invoked when a TreeNode has been clicked and held.
     */
    public interface OnTreeNodeLongClickListener {
        /**
         * Called when a TreeNode has been clicked and held.
         *
         * @param treeNode The current clicked node
         * @param view     The view that was clicked and held.
         * @return true if the callback consumed the long click, false otherwise.
         */
        boolean onTreeNodeLongClick(TreeNode treeNode, View view);
    }

    /**
     * Manager class for TreeNodes to easily apply operations on them
     * and to make it easy for testing and extending
     */
    private final TreeNodeManager treeNodeManager;

    /**
     * A ViewHolder Factory to get TreeViewHolder object that mapped with layout
     */
    private final TreeViewHolderFactory treeViewHolderFactory;

    /**
     * The current selected Tree Node
     */
    private TreeNode currentSelectedNode;

    /**
     * The current selected Tree Node position to be used in notify changes
     */
    private int currentSelectedNodePosition = -1;

    /**
     * Custom OnClickListener to be invoked when a TreeNode has been clicked.
     */
    private OnTreeNodeClickListener treeNodeClickListener;

    /**
     * Custom OnLongClickListener to be invoked when a TreeNode has been clicked and hold.
     */
    private OnTreeNodeLongClickListener treeNodeLongClickListener;

    /**
     * Simple constructor
     *
     * @param factory a View Holder Factory mapped with layout id's
     */
    public TreeViewAdapter(TreeViewHolderFactory factory) {
        this.treeViewHolderFactory = factory;
        this.treeNodeManager = new TreeNodeManager();
    }

    /**
     * Constructor used to accept user custom TreeNodeManager class
     *
     * @param factory a View Holder Factory mapped with layout id's
     * @param manager a custom tree node manager class
     */
    public TreeViewAdapter(TreeViewHolderFactory factory, TreeNodeManager manager) {
        this.treeViewHolderFactory = factory;
        this.treeNodeManager = manager;
    }

    @NonNull
    @Override
    public TreeViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int layoutId) {
        View view = LayoutInflater.from(parent.getContext()).inflate(layoutId, parent, false);
        return treeViewHolderFactory.getTreeViewHolder(view, layoutId);
    }

    @Override
    public void onBindViewHolder(@NonNull TreeViewHolder holder, @SuppressLint("RecyclerView") int position) {
        TreeNode newSelectedNode = treeNodeManager.get(position);
        holder.adapter = this;
        holder.bindTreeNode(newSelectedNode);
        holder.itemView.setOnClickListener(v -> {
            // Handle TreeNode click listener event
            if (treeNodeClickListener != null) {
                treeNodeClickListener.onTreeNodeClick(newSelectedNode, v);
            }
            // Handle node selection
            if (newSelectedNode == currentSelectedNode) {
                boolean isNodeSelected = !currentSelectedNode.isSelected();
                currentSelectedNode.setSelected(isNodeSelected);
                notifyItemChanged(currentSelectedNodePosition);

                // Un track this node as selected one
                if (!isNodeSelected) {
                    currentSelectedNode = null;
                    currentSelectedNodePosition = -1;
                }
            } else {
                // Un selected the previous selected tree node
                if (currentSelectedNode != null) {
                    currentSelectedNode.setSelected(false);
                    notifyItemChanged(currentSelectedNodePosition);
                }

                // Mark the current node as selected
                newSelectedNode.setSelected(true);
                notifyItemChanged(position);

                // Update tracking current node value and position
                currentSelectedNode = newSelectedNode;
                currentSelectedNodePosition = position;
            }


            // Handle node expand and collapse event
            if (!newSelectedNode.getChildren().isEmpty()) {
                boolean isNodeExpanded = newSelectedNode.isExpanded();
                if (isNodeExpanded) collapseNode(newSelectedNode);
                else expandNode(newSelectedNode);
                newSelectedNode.setExpanded(!isNodeExpanded);

                // Only children after this position will be inserted (Expanding) or deleted (Collapsing)
                notifyItemRangeChanged(position, getItemCount() - position);
            }


        });

        // Handle TreeNode long click listener event
        holder.itemView.setOnLongClickListener(v -> {
            if (treeNodeLongClickListener != null) {
                return treeNodeLongClickListener.onTreeNodeLongClick(newSelectedNode, v);
            }
            return true;
        });
    }

    @Override
    public int getItemViewType(int position) {
        return treeNodeManager.get(position).getLayoutId();
    }

    @Override
    public int getItemCount() {
        return treeNodeManager.size();
    }

    /**
     * Collapsing node and all of his children
     *
     * @param node The node to collapse it
     */
    public void collapseNode(TreeNode node) {
        int position = treeNodeManager.collapseNode(node);
        if (position != -1) {
            notifyDataSetChanged();
        }
    }

    /**
     * Expanding node and all of his children
     *
     * @param node The node to expand it
     */
    public void expandNode(TreeNode node) {
        int position = treeNodeManager.expandNode(node);
        if (position != -1) {
            notifyDataSetChanged();
        }
    }


    /**
     * Update the list of tree nodes
     *
     * @param treeNodes The new tree nodes
     */
    public void updateTreeNodes(List<TreeNode> treeNodes) {
        treeNodeManager.updateNodes(treeNodes);
        notifyDataSetChanged();
    }


    /**
     * Register a callback to be invoked when this TreeNode is clicked
     *
     * @param listener The callback that will run
     */
    public void setTreeNodeClickListener(OnTreeNodeClickListener listener) {
        this.treeNodeClickListener = listener;
    }

    /**
     * Remove a node and its children from the tree
     * @param node The node to remove
     */
    public void removeNode(TreeNode node) {
        treeNodeManager.collapseNode(node);
        if (treeNodeManager.removeNode(node)) {
            notifyDataSetChanged();
        }
    }
}