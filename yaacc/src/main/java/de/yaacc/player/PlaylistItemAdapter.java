package de.yaacc.player;

import android.content.Context;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import de.yaacc.R;
import de.yaacc.util.ThemeHelper;

public class PlaylistItemAdapter extends RecyclerView.Adapter<PlaylistItemAdapter.ViewHolder> {

    private final List<PlayableItem> items;
    private final Context context;
    private final Player player;
    private RecyclerView listView;

    public PlaylistItemAdapter(Context ctx, RecyclerView listView, Player player) {
        super();
        this.player = player;
        this.items = player != null ? new ArrayList<>(player.getItems()) : new ArrayList<>();
        context = ctx;
        this.listView = listView;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }


    public PlayableItem getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public PlaylistItemAdapter.ViewHolder onCreateViewHolder(ViewGroup parent,
                                                             int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.playlist_item, parent, false);

        return new PlaylistItemAdapter.ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(final PlaylistItemAdapter.ViewHolder holder, final int listPosition) {
        PlayableItem item = getItem(listPosition);
        holder.name.setText(item.getTitle());
        if (player.isPlaying() && listPosition <= player.getCurrentItemIndex()) {
            holder.dragIcon.setImageDrawable(ThemeHelper.tintDrawable(context.getResources().getDrawable(R.drawable.ic_baseline_lock_32, context.getTheme()), context.getTheme()));
            holder.deleteIcon.setVisibility(View.GONE);
        } else {
            holder.dragIcon.setImageDrawable(ThemeHelper.tintDrawable(context.getResources().getDrawable(R.drawable.ic_baseline_drag_indicator_32, context.getTheme()), context.getTheme()));
            holder.deleteIcon.setVisibility(View.VISIBLE);
        }
        holder.deleteIcon.setOnClickListener(l -> removeItem(listPosition));
        if (player.isPlaying() && player.getCurrentItemIndex() == listPosition) {
            holder.name.setTypeface(null, Typeface.BOLD);
            holder.name.setText(item.getTitle() + " ▶");
        }
    }

    private void removeItem(int listPosition) {
        if (player.getItems().size() > listPosition && listPosition > 0) {
            player.getItems().remove(listPosition);
            setItems(player.getItems());
        }
    }

    public void setItems(List<PlayableItem> items) {
        final DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new PlayableItemDiffCallback(this.items, items));
        this.items.clear();
        this.items.addAll(items);
        diffResult.dispatchUpdatesTo(this);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageButton deleteIcon;
        ImageView dragIcon;
        TextView name;


        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            dragIcon = itemView.findViewById(R.id.playlistItemDragIcon);
            deleteIcon = itemView.findViewById(R.id.playlistItemDeleteIcon);
            name = itemView.findViewById(R.id.playlistItemName);
        }
    }
}
