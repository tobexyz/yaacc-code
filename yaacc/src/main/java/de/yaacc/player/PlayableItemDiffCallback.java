package de.yaacc.player;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;

import java.util.List;

public class PlayableItemDiffCallback extends DiffUtil.Callback {

    private final List<PlayableItem> oldList;
    private final List<PlayableItem> newList;

    public PlayableItemDiffCallback(List<PlayableItem> oldList, List<PlayableItem> newList) {
        this.oldList = oldList;
        this.newList = newList;
    }

    @Override
    public int getOldListSize() {
        return oldList.size();
    }

    @Override
    public int getNewListSize() {
        return newList.size();
    }

    @Override
    public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
        return oldList.get(oldItemPosition).getId().equals(newList.get(
                newItemPosition).getId());
    }

    @Override
    public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
        final PlayableItem oldItem = oldList.get(oldItemPosition);
        final PlayableItem newItem = newList.get(newItemPosition);
        return oldItem.getId().equals(newItem.getId());
    }

    @Nullable
    @Override
    public Object getChangePayload(int oldItemPosition, int newItemPosition) {
        // Implement method if you're going to use ItemAnimator
        return super.getChangePayload(oldItemPosition, newItemPosition);
    }
}
