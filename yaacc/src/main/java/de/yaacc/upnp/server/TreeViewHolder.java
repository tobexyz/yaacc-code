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


import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.Set;

import de.yaacc.R;
import de.yaacc.upnp.server.contentdirectory.MediaPathFilter;
import de.yaacc.util.ThemeHelper;


public class TreeViewHolder extends RecyclerView.ViewHolder {


    /**
     * The default padding value for the TreeNode item
     */
    private int nodePadding = 50;
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


    public void bindTreeNode(TreeNode node) {
        int padding = node.getLevel() * nodePadding;
        itemView.setPadding(
                padding,
                itemView.getPaddingTop(),
                itemView.getPaddingRight(),
                itemView.getPaddingBottom());

        String name = getName(node);
        fileName.setText(name);
        fileCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Set<String> pathes;
            String absolutePath = getAbsolutePath(node);
            if (isSafNode(node)) {
                pathes = MediaPathFilter.getSelectedSafPathes(fileCheckbox.getContext());
            } else {
                pathes = MediaPathFilter.getMediaPathesRaw(fileCheckbox.getContext());
            }
            if (isChecked) {
                pathes.add(absolutePath);
            } else {
                pathes.remove(absolutePath);
            }
            if (isSafNode(node)) {
                MediaPathFilter.saveSelectedSafPathes(fileCheckbox.getContext(), pathes);
            } else {
                MediaPathFilter.saveMediaPaths(fileCheckbox.getContext(), pathes);
            }
        });

        if (isSafNode(node)) {
            String absolutePath = getAbsolutePath(node);
            Drawable icon = fileRemoveButton.getContext().getDrawable(R.drawable.ic_baseline_delete_outline_32);
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
        if (isDirectory(node)) {
            Drawable icon = isSafNode(node) ? fileTypeIcon.getContext().getDrawable(R.drawable.ic_baseline_bookmark_48) : fileTypeIcon.getContext().getDrawable(R.drawable.ic_baseline_folder_open_48);
            icon = ThemeHelper.tintDrawable(icon, fileTypeIcon.getContext().getTheme());
            fileTypeIcon.setImageDrawable(icon);
            String absolutePath = getAbsolutePath(node);
            fileCheckbox.setChecked(isSelected(absolutePath));
            fileCheckbox.setVisibility(View.VISIBLE);
        } else {
            fileCheckbox.setVisibility(View.INVISIBLE);
            Drawable icon = ThemeHelper.tintDrawable(fileTypeIcon.getContext().getDrawable(R.drawable.ic_baseline_file_48), fileTypeIcon.getContext().getTheme());
            fileTypeIcon.setImageDrawable(icon);
        }

        if (node.isSelected()) {
            itemView.setBackgroundColor(Color.LTGRAY);
            TypedValue typedValue = new TypedValue();
            itemView.getContext().getTheme().resolveAttribute(android.R.attr.colorPrimaryDark, typedValue, true);
            fileName.setTextColor(typedValue.data);
        } else {
            TypedValue typedValue = new TypedValue();
            itemView.getContext().getTheme().resolveAttribute(android.R.attr.colorBackground, typedValue, true);
            itemView.setBackgroundColor(typedValue.data);
            itemView.getContext().getTheme().resolveAttribute(android.R.attr.colorForeground, typedValue, true);
            fileName.setTextColor(typedValue.data);
        }
        fileStateIcon.setVisibility(View.INVISIBLE);

        if (isDirectory(node)) {
            if (isDirectoryNotEmpty(node)) {
                fileStateIcon.setVisibility(View.VISIBLE);
                int stateIcon = node.isExpanded() ? R.drawable.sharp_keyboard_arrow_down_24 : R.drawable.sharp_chevron_right_24;
                Drawable icon = ThemeHelper.tintDrawable(fileStateIcon.getContext().getDrawable(stateIcon), fileStateIcon.getContext().getTheme());
                fileStateIcon.setImageDrawable(icon);
            }
        }
    }

    private static boolean isSafNode(TreeNode node) {
        return node.getValue() instanceof DocumentFile;
    }

    private boolean isSelected(String absolutePath) {
        return MediaPathFilter.getMediaPathesRaw(fileCheckbox.getContext()).contains(absolutePath)
                || MediaPathFilter.getSelectedSafPathes(fileCheckbox.getContext()).contains(absolutePath);
    }

    @NonNull
    private static String getAbsolutePath(TreeNode node) {
        return node.getValue() instanceof File ? ((File) node.getValue()).getAbsolutePath() : ((DocumentFile) node.getValue()).getUri().toString();
    }

    private static boolean isDirectoryNotEmpty(TreeNode node) {
        if (node.getValue() != null) {
            if (node.getValue() instanceof File) {
                File[] elements = ((File) node.getValue()).listFiles();
                return elements != null && elements.length > 0;
            } else if (node.getValue() instanceof DocumentFile) {
                DocumentFile[] elements = ((DocumentFile) node.getValue()).listFiles();
                return elements.length > 0;
            }
        }
        return true;
    }

    private static boolean isDirectory(TreeNode node) {
        if (node.getValue() != null) {
            if (node.getValue() instanceof File) {
                return ((File) node.getValue()).isDirectory();
            } else if (node.getValue() instanceof DocumentFile) {
                return ((DocumentFile) node.getValue()).isDirectory();
            }
        }
        return false;
    }

    @Nullable
    private static String getName(TreeNode node) {
        if (isSafNode(node)) {
            String result = ((DocumentFile) node.getValue()).getName();
            if (result == null) {
                result = ((DocumentFile) node.getValue()).getUri().toString();
            }
            return result;
        }
        return ((File) node.getValue()).getName();
    }

}
