/*
 * Copyright (C) 2026 Tobias Schoene www.yaacc.de
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package de.yaacc.player;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Typeface;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import de.yaacc.R;
import de.yaacc.util.ThemeHelper;
import de.yaacc.util.YaaccLogger;

/**
 * The playlist of a player in the order it plays: with shuffle the play order (the stored list is not
 * touched), otherwise the list order. A row is a position of {@link Player#getPlayOrder()}; the item in it
 * is {@code items.get(order[position])}. The adapter keeps a snapshot of both and reads the player again in
 * {@link #refresh}; edits go to the player, which decides (locked positions) and fires the change.
 */
public class PlaylistItemAdapter extends RecyclerView.Adapter<PlaylistItemAdapter.ViewHolder> {

    private List<PlayableItem> items = new ArrayList<>();
    private int[] order = new int[0];
    private final Context context;
    private final Player player;
    private final RecyclerView listView;
    private int selectedForMovePosition = -1; // -1 indicates no item is selected for move
    // True while this adapter edits the player: the change event of that edit is already shown
    private boolean editing;

    public PlaylistItemAdapter(Context ctx, RecyclerView listView, Player player) {
        super();
        this.player = player;
        context = ctx;
        this.listView = listView;
        takeSnapshot();
    }

    private void takeSnapshot() {
        if (player == null) {
            items = new ArrayList<>();
            order = new int[0];
            return;
        }
        List<PlayableItem> newItems = new ArrayList<>(player.getItems());
        int[] newOrder = player.getPlayOrder();
        if (newOrder.length != newItems.size()) {
            // The player changed between the two reads; the next change event reads them again
            newOrder = new int[newItems.size()];
            for (int i = 0; i < newOrder.length; i++) {
                newOrder[i] = i;
            }
        }
        items = newItems;
        order = newOrder;
    }

    /**
     * Shows the playlist as the player has it now (after a change of the list, the order or the play mode).
     * Nothing happens if the player has the playlist as it is shown. Main thread.
     *
     * @param stateChanged true if the current item or the playing state changed too: the rows are bound
     *                     again (bold marker, lock) even if the playlist is the same
     */
    @SuppressLint("NotifyDataSetChanged") // The order can change completely (shuffle), no precise update
    public void refresh(boolean stateChanged) {
        if (editing) {
            return;
        }
        List<PlayableItem> oldItems = items;
        int[] oldOrder = order;
        takeSnapshot();
        if (!sameOrder(oldItems, oldOrder, items, order)) {
            selectedForMovePosition = -1;
            notifyDataSetChanged();
        } else if (stateChanged) {
            notifyItemRangeChanged(0, order.length);
        }
    }

    private static boolean sameOrder(List<PlayableItem> oldItems, int[] oldOrder, List<PlayableItem> newItems, int[] newOrder) {
        if (oldOrder.length != newOrder.length) {
            return false;
        }
        for (int position = 0; position < oldOrder.length; position++) {
            if (oldItems.get(oldOrder[position]) != newItems.get(newOrder[position])) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int getItemCount() {
        return order.length;
    }


    /** @return the item shown at the position */
    public PlayableItem getItem(int position) {
        return items.get(order[position]);
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
    public void onBindViewHolder(final PlaylistItemAdapter.ViewHolder holder, final int position) {
        PlayableItem item = getItem(position);
        holder.name.setText(item.getTitle());
        // A locked position plays, is paused or came before the current item in the displayed order
        if (player.isPlaylistPositionLocked(position)) {
            holder.dragIcon.setImageDrawable(ThemeHelper.tintDrawable(ResourcesCompat.getDrawable(context.getResources(), R.drawable.ic_baseline_lock_32, context.getTheme()), context.getTheme()));
            holder.deleteIcon.setVisibility(View.GONE);
            holder.dragIcon.setFocusable(false); // Cannot move locked items
        } else {
            holder.dragIcon.setImageDrawable(ThemeHelper.tintDrawable(ResourcesCompat.getDrawable(context.getResources(), R.drawable.ic_baseline_drag_indicator_32, context.getTheme()), context.getTheme()));
            holder.deleteIcon.setVisibility(View.VISIBLE);
            holder.dragIcon.setFocusable(true);
        }
        // The position of the row changes with every move: ask the holder when it is clicked
        holder.deleteIcon.setOnClickListener(l -> removeItem(holder.getBindingAdapterPosition()));
        if (player.isPlaying() && player.getCurrentItemIndex() == order[position]) {
            holder.name.setTypeface(null, Typeface.BOLD);
            holder.name.setText(item.getTitle() + " ▶");
        } else {
            holder.name.setTypeface(null, Typeface.NORMAL); // Ensure non-playing items are not bold
        }

        if (position == selectedForMovePosition) {
            // Highlight the selected item (e.g., change background color or add a border)
            YaaccLogger.d(getClass().getName(), "Item selected for keyboard move: " + item.getTitle());
            holder.itemView.setBackgroundColor(context.getResources().getColor(R.color.gray_highlight));
        } else {
            holder.itemView.setBackgroundColor(context.getResources().getColor(android.R.color.transparent));
        }

        holder.dragIcon.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (selectedForMovePosition == holder.getBindingAdapterPosition()) {
                        // Deselect item
                        int previouslySelected = selectedForMovePosition;
                        selectedForMovePosition = -1;
                        notifyItemChanged(previouslySelected); // To remove highlight
                        return true;
                    } else if (selectedForMovePosition == -1 && !player.isPlaylistPositionLocked(holder.getBindingAdapterPosition())) {
                        // Select item for move
                        selectedForMovePosition = holder.getBindingAdapterPosition();
                        notifyItemChanged(selectedForMovePosition); // To add highlight
                        return true;
                    }
                } else if (selectedForMovePosition == holder.getBindingAdapterPosition()) {
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

    /**
     * Moves the item at a displayed position to another one: with shuffle in the play order, otherwise in
     * the list (the player decides which, and refuses locked positions).
     *
     * @return true if the item moved
     */
    public boolean moveItem(int fromPosition, int toPosition) {
        if (player == null || fromPosition < 0 || fromPosition >= order.length || toPosition < 0 || toPosition >= order.length) {
            return false;
        }
        editing = true;
        boolean moved;
        try {
            moved = player.moveInPlayOrder(fromPosition, toPosition);
        } finally {
            editing = false;
        }
        if (!moved) {
            return false;
        }
        takeSnapshot();
        notifyItemMoved(fromPosition, toPosition);

        // Update selectedForMovePosition if the moved item was the one selected for keyboard move
        if (selectedForMovePosition == fromPosition) {
            selectedForMovePosition = toPosition;
        }
        // No notifyItemChanged(fromPosition) or notifyItemChanged(toPosition) here

        if (listView != null) {
            listView.scrollToPosition(toPosition);
        }
        return true;
    }


    private void removeItem(int position) {
        if (player == null || position < 0 || position >= order.length) {
            return;
        }
        editing = true;
        boolean removed;
        try {
            removed = player.removeItem(order[position]);
        } finally {
            editing = false;
        }
        if (removed) {
            takeSnapshot();
            selectedForMovePosition = -1;
            notifyItemRemoved(position);
            // The rows behind move up; their delete action and lock state are bound again
            if (position < order.length) {
                notifyItemRangeChanged(position, order.length - position);
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
