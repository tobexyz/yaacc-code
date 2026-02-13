package de.yaacc.player;

import android.content.Context;
import android.graphics.Typeface;
import de.yaacc.util.YaaccLogger;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.yaacc.R;
import de.yaacc.util.ThemeHelper;

public class PlaylistItemAdapter extends RecyclerView.Adapter<PlaylistItemAdapter.ViewHolder> {

    private final List<PlayableItem> items;
    private final Context context;
    private final Player player;
    private RecyclerView listView;
    private int selectedForMovePosition = -1; // -1 indicates no item is selected for move

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
            holder.dragIcon.setFocusable(false); // Cannot move locked items
        } else {
            holder.dragIcon.setImageDrawable(ThemeHelper.tintDrawable(context.getResources().getDrawable(R.drawable.ic_baseline_drag_indicator_32, context.getTheme()), context.getTheme()));
            holder.deleteIcon.setVisibility(View.VISIBLE);
            holder.dragIcon.setFocusable(true);
        }
        holder.deleteIcon.setOnClickListener(l -> removeItem(listPosition));
        if (player.isPlaying() && player.getCurrentItemIndex() == listPosition) {
            holder.name.setTypeface(null, Typeface.BOLD);
            holder.name.setText(item.getTitle() + " ▶");
        } else {
            holder.name.setTypeface(null, Typeface.NORMAL); // Ensure non-playing items are not bold
        }

        if (listPosition == selectedForMovePosition) {
            // Highlight the selected item (e.g., change background color or add a border)
            YaaccLogger.d(getClass().getName(), "Item selected for keyboard move: " + item.getTitle());
            holder.itemView.setBackgroundColor(context.getResources().getColor(R.color.design_default_color_secondary));
        } else {
            holder.itemView.setBackgroundColor(context.getResources().getColor(android.R.color.transparent));
        }

        holder.dragIcon.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (selectedForMovePosition == holder.getAdapterPosition()) { // Use holder.getAdapterPosition() for safety
                        // Deselect item
                        int previouslySelected = selectedForMovePosition;
                        selectedForMovePosition = -1;
                        notifyItemChanged(previouslySelected); // To remove highlight
                        return true;
                    } else if (selectedForMovePosition == -1 && !(player.isPlaying() && holder.getAdapterPosition() <= player.getCurrentItemIndex())) {
                        // Select item for move
                        selectedForMovePosition = holder.getAdapterPosition();
                        notifyItemChanged(selectedForMovePosition); // To add highlight
                        return true;
                    }
                } else if (selectedForMovePosition == holder.getAdapterPosition()) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        moveItem(selectedForMovePosition, selectedForMovePosition - 1);
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        moveItem(selectedForMovePosition, selectedForMovePosition + 1);
                        return true;
                    }
                }
            }
            return false;
        });
    }

    public boolean moveItem(int fromPosition, int toPosition) {
        if (player.isPlaying() && (fromPosition <= player.getCurrentItemIndex() || toPosition <= player.getCurrentItemIndex())) {
            return false;
        }
        if (fromPosition < 0 || fromPosition >= items.size() || toPosition < 0 || toPosition >= items.size()) {
            return false;
        }

        Collections.swap(player.getItems(), fromPosition, toPosition);
        Collections.swap(items, fromPosition, toPosition);
        notifyItemMoved(fromPosition, toPosition);

        // Update selectedForMovePosition if the moved item was the one selected for keyboard move
        if (selectedForMovePosition == fromPosition) {
            selectedForMovePosition = toPosition;
        }
        // No notifyItemChanged(fromPosition) or notifyItemChanged(toPosition) here

        if (listView != null) {
            listView.scrollToPosition(toPosition);
        }
        
        // Sync playlist to ExoPlayer if it's LocalMediaSessionPlayer
        if (player instanceof de.yaacc.player.LocalMediaSessionPlayer) {
            ((de.yaacc.player.LocalMediaSessionPlayer) player).syncPlaylistToExoPlayer();
        }
        
        return true;
    }


    private void removeItem(int listPosition) {
        if (player.getItems().size() > listPosition && listPosition >= 0 && !(player.isPlaying() && listPosition <= player.getCurrentItemIndex())) {
            player.getItems().remove(listPosition);
            
            // Sync playlist to ExoPlayer if it's LocalMediaSessionPlayer
            if (player instanceof de.yaacc.player.LocalMediaSessionPlayer) {
                ((de.yaacc.player.LocalMediaSessionPlayer) player).syncPlaylistToExoPlayer();
            }
            // Update local items list to reflect removal before notifying adapter
            PlayableItem removedItem = items.remove(listPosition);
            notifyItemRemoved(listPosition);
            // notifyItemRangeChanged is important if item positions change relative to others
            if (listPosition < items.size()) {
                notifyItemRangeChanged(listPosition, items.size() - listPosition);
            }
        }
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageButton deleteIcon;
        ImageView dragIcon;
        TextView name;


        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            dragIcon = itemView.findViewById(R.id.playlistItemDragIcon);
            dragIcon.setFocusable(true); // Make the drag icon focusable
            deleteIcon = itemView.findViewById(R.id.playlistItemDeleteIcon);
            name = itemView.findViewById(R.id.playlistItemName);
        }
    }
}
