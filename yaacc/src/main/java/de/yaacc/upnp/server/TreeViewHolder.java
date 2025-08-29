package de.yaacc.upnp.server;


import android.util.TypedValue;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import de.yaacc.R;


public class TreeViewHolder extends RecyclerView.ViewHolder {


    /**
     * The default padding value for the TreeNode item
     */
    private int nodePadding = 50;
    private final TextView fileName;
    private final ImageView fileStateIcon;
    private final ImageView fileTypeIcon;

    public TreeViewHolder(@NonNull View itemView) {
        super(itemView);

        this.fileName = itemView.findViewById(R.id.file_name);
        this.fileStateIcon = itemView.findViewById(R.id.file_state_icon);
        this.fileTypeIcon = itemView.findViewById(R.id.file_type_icon);
    }


    public void bindTreeNode(TreeNode node) {
        int padding = node.getLevel() * nodePadding;
        itemView.setPadding(
                padding,
                itemView.getPaddingTop(),
                itemView.getPaddingRight(),
                itemView.getPaddingBottom());


        fileName.setText(node.getValue().getName());

        if (node.getValue() != null && node.getValue().isDirectory()) {
            fileTypeIcon.setImageResource(R.drawable.ic_baseline_folder_open_48);
        } else {
            fileTypeIcon.setImageResource(R.drawable.ic_baseline_file_48);
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
            if (node.getValue().listFiles().length > 0) {
                fileStateIcon.setVisibility(View.VISIBLE);
                int stateIcon = node.isExpanded() ? R.drawable.sharp_keyboard_arrow_down_24 : R.drawable.sharp_chevron_right_24;
                fileStateIcon.setImageResource(stateIcon);
            }
        }
    }

}
