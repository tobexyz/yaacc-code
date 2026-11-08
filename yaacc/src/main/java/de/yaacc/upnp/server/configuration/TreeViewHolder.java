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


import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Set;

import de.yaacc.R;
import de.yaacc.upnp.server.contentdirectory.MediaPathFilter;
import de.yaacc.util.ThemeHelper;


public class TreeViewHolder extends RecyclerView.ViewHolder {


    /**
     * The default padding value for the TreeNode item
     */
    private int nodePadding = 50;
    private static final float LOADING_ALPHA = 0.38f;
    private final TextView fileName;
    private final ImageView fileStateIcon;
    private final ImageView fileTypeIcon;
    private final ImageButton fileRemoveButton;
    private final CheckBox fileCheckbox;

    protected TreeViewAdapter adapter;

    public TreeViewHolder(@NonNull View itemView) {
        super(itemView);

        this.fileName = itemView.findViewById(R.id.file_name);
        this.fileStateIcon = itemView.findViewById(R.id.file_state_icon);
        this.fileTypeIcon = itemView.findViewById(R.id.file_type_icon);
        this.fileCheckbox = itemView.findViewById(R.id.file_checkbox);
        this.fileRemoveButton = itemView.findViewById(R.id.file_remove);
    }


    private int resolveThemeColor(int attr) {
        TypedValue typedValue = new TypedValue();
        itemView.getContext().getTheme().resolveAttribute(attr, typedValue, true);
        return typedValue.data;
    }

    /**
     * Shows a node. Everything comes from the node's plain data (read on a worker thread): no file
     * system or document provider call happens here, as this runs for every row that scrolls in.
     */
    public void bindTreeNode(TreeNode node) {
        TreeNodeData data = node.getValue();
        int padding = node.getLevel() * nodePadding;
        itemView.setPadding(
                padding,
                itemView.getPaddingTop(),
                itemView.getPaddingRight(),
                itemView.getPaddingBottom());

        fileName.setText(data.getName());
        String absolutePath = data.getPath();
        boolean saf = data.isSaf();
        // Detached while the state is set: setChecked would otherwise run the listener of the row that
        // was shown here before and write the preferences for every row that scrolls in.
        fileCheckbox.setOnCheckedChangeListener(null);

        if (saf) {
            Drawable icon = ContextCompat.getDrawable(fileRemoveButton.getContext(), R.drawable.ic_baseline_delete_outline_32);
            icon = ThemeHelper.tintDrawable(icon, fileRemoveButton.getContext().getTheme());
            if (MediaPathFilter.getSafPathes(fileRemoveButton.getContext()).contains(absolutePath)) {
                fileRemoveButton.setVisibility(View.VISIBLE);
            } else {
                fileRemoveButton.setVisibility(View.INVISIBLE);
            }
            fileRemoveButton.setImageDrawable(icon);
            fileRemoveButton.setOnClickListener(v -> {
                Set<String> selectedPathes;
                Set<String> safPathes;
                selectedPathes = MediaPathFilter.getSelectedSafPathes(fileRemoveButton.getContext());
                safPathes = MediaPathFilter.getSafPathes(fileRemoveButton.getContext());
                selectedPathes.remove(absolutePath);
                safPathes.remove(absolutePath);
                MediaPathFilter.saveSelectedSafPathes(fileRemoveButton.getContext(), selectedPathes);
                MediaPathFilter.saveSafPathes(fileRemoveButton.getContext(), safPathes);
                adapter.removeNode(node);
            });
        } else {
            fileRemoveButton.setVisibility(View.INVISIBLE);
        }
        if (data.isDirectory()) {
            Drawable icon = saf ? ContextCompat.getDrawable(fileTypeIcon.getContext(), R.drawable.ic_baseline_bookmark_48) : ContextCompat.getDrawable(fileTypeIcon.getContext(), R.drawable.ic_baseline_folder_open_48);
            icon = ThemeHelper.tintDrawable(icon, fileTypeIcon.getContext().getTheme());
            fileTypeIcon.setImageDrawable(icon);
            fileCheckbox.setChecked(isSelected(absolutePath));
            fileCheckbox.setVisibility(View.VISIBLE);
        } else {
            fileCheckbox.setVisibility(View.INVISIBLE);
            Drawable icon = ThemeHelper.tintDrawable(ContextCompat.getDrawable(fileTypeIcon.getContext(), R.drawable.ic_baseline_file_48), fileTypeIcon.getContext().getTheme());
            fileTypeIcon.setImageDrawable(icon);
        }
        fileCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Set<String> pathes;
            if (saf) {
                pathes = MediaPathFilter.getSelectedSafPathes(fileCheckbox.getContext());
            } else {
                pathes = MediaPathFilter.getMediaPathesRaw(fileCheckbox.getContext());
            }
            if (isChecked) {
                pathes.add(absolutePath);
            } else {
                pathes.remove(absolutePath);
            }
            if (saf) {
                MediaPathFilter.saveSelectedSafPathes(fileCheckbox.getContext(), pathes);
            } else {
                MediaPathFilter.saveMediaPaths(fileCheckbox.getContext(), pathes);
            }
        });

        // Selected rows are a pill-like container with its own content color; the others take the page
        // background and the primary text color (both from the Material 3 gray scheme of the theme).
        if (node.isSelected()) {
            itemView.setBackgroundColor(resolveThemeColor(com.google.android.material.R.attr.colorSecondaryContainer));
            fileName.setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSecondaryContainer));
        } else {
            itemView.setBackgroundColor(resolveThemeColor(android.R.attr.colorBackground));
            fileName.setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurface));
        }
        fileStateIcon.setVisibility(View.INVISIBLE);

        // Every folder shows the expand symbol until its children were read and there are none; asking
        // the provider per row whether a folder is empty would list that folder for every scrolled row.
        if (data.isDirectory() && !node.isKnownEmpty()) {
            fileStateIcon.setVisibility(View.VISIBLE);
            int stateIcon = node.isExpanded() ? R.drawable.sharp_keyboard_arrow_down_24 : R.drawable.sharp_chevron_right_24;
            Drawable icon = ThemeHelper.tintDrawable(ContextCompat.getDrawable(fileStateIcon.getContext(), stateIcon), fileStateIcon.getContext().getTheme());
            fileStateIcon.setImageDrawable(icon);
        }
        // Dimmed while the children are being read.
        fileStateIcon.setAlpha(node.isLoading() ? LOADING_ALPHA : 1f);
    }

    private boolean isSelected(String absolutePath) {
        return MediaPathFilter.getMediaPathesRaw(fileCheckbox.getContext()).contains(absolutePath)
                || MediaPathFilter.getSelectedSafPathes(fileCheckbox.getContext()).contains(absolutePath);
    }
}
