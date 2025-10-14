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


import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
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
    private final TextView fileName;
    private final ImageView fileStateIcon;
    private final ImageView fileTypeIcon;

    private final CheckBox fileCheckbox;

    public TreeViewHolder(@NonNull View itemView) {
        super(itemView);

        this.fileName = itemView.findViewById(R.id.file_name);
        this.fileStateIcon = itemView.findViewById(R.id.file_state_icon);
        this.fileTypeIcon = itemView.findViewById(R.id.file_type_icon);
        this.fileCheckbox = itemView.findViewById(R.id.file_checkbox);
    }


    public void bindTreeNode(TreeNode node) {
        int padding = node.getLevel() * nodePadding;
        itemView.setPadding(
                padding,
                itemView.getPaddingTop(),
                itemView.getPaddingRight(),
                itemView.getPaddingBottom());


        fileName.setText(node.getValue().getName());
        fileCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Set<String> pathes = MediaPathFilter.getMediaPathesRaw(fileCheckbox.getContext());
            if (isChecked) {
                pathes.add(node.getValue().getAbsolutePath());
            } else {
                pathes.remove(node.getValue().getAbsolutePath());
            }
            MediaPathFilter.saveMediaPaths(fileCheckbox.getContext(), pathes);
        });
        if (node.getValue() != null && node.getValue().isDirectory()) {
            Drawable icon = ThemeHelper.tintDrawable(fileTypeIcon.getContext().getDrawable(R.drawable.ic_baseline_folder_open_48), fileTypeIcon.getContext().getTheme());
            fileTypeIcon.setImageDrawable(icon);
            fileCheckbox.setChecked(MediaPathFilter.getMediaPathesRaw(fileCheckbox.getContext()).contains(node.getValue().getAbsolutePath()));
            fileCheckbox.setVisibility(View.VISIBLE);
        } else {
            fileCheckbox.setVisibility(View.INVISIBLE);
            Drawable icon = ThemeHelper.tintDrawable(fileTypeIcon.getContext().getDrawable(R.drawable.ic_baseline_file_48), fileTypeIcon.getContext().getTheme());
            fileTypeIcon.setImageDrawable(icon);
        }

        if (node.isSelected()) {
            TypedValue typedValue = new TypedValue();
            itemView.getContext().getTheme().resolveAttribute(android.R.attr.colorActivatedHighlight, typedValue, true);
            itemView.setBackgroundColor(typedValue.data);
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
        if (node.getValue() != null && node.getValue().isDirectory()) {
            if (node.getValue().listFiles() != null && node.getValue().listFiles().length > 0) {
                fileStateIcon.setVisibility(View.VISIBLE);

                int stateIcon = node.isExpanded() ? R.drawable.sharp_keyboard_arrow_down_24 : R.drawable.sharp_chevron_right_24;
                Drawable icon = ThemeHelper.tintDrawable(fileStateIcon.getContext().getDrawable(stateIcon), fileStateIcon.getContext().getTheme());
                fileStateIcon.setImageDrawable(icon);
            }
        }
    }

}
