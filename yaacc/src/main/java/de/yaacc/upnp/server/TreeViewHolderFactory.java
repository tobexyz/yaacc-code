package de.yaacc.upnp.server;

import android.view.View;

/**
 * TreeViewHolder Factory class to get TreeViewHolder instance for the current view
 */
public interface TreeViewHolderFactory {

    /**
     * Provide a TreeViewHolder class depend on the current view
     *
     * @param view   The list item view
     * @param layout The layout xml file id for current view
     * @return A TreeViewHolder instance
     */
    TreeViewHolder getTreeViewHolder(View view, int layout);
}