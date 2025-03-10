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

        String fileNameStr = node.getValue().toString();
        fileName.setText(fileNameStr);

        int dotIndex = fileNameStr.indexOf('.');
        if (dotIndex == -1) {
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

        if (node.getChildren().isEmpty()) {
            fileStateIcon.setVisibility(View.INVISIBLE);
        } else {
            fileStateIcon.setVisibility(View.VISIBLE);
            int stateIcon = node.isExpanded() ? R.drawable.ic_baseline_download_48 : R.drawable.ic_baseline_double_arrow_24;
            fileStateIcon.setImageResource(stateIcon);
        }
    }

}
