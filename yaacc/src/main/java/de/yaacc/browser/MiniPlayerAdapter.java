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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package de.yaacc.browser;

import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import de.yaacc.R;
import de.yaacc.player.Player;
import de.yaacc.util.ThemeHelper;
import de.yaacc.util.image.IconDownloadCacheHandler;
import de.yaacc.util.image.IconDownloadTask;

/**
 * Rows of the mini-player list ({@code mini_player_row.xml}), one per running player. Stable ids
 * are the player ids and the rows are diffed, so a poll that changes nothing rebinds nothing and
 * a changed title or play state rebinds only its own row. Main thread only.
 * <p>
 * Texts arrive cleaned ({@link MiniPlayerPresenter}) and go through {@code setText} only. Album
 * art goes through {@link IconDownloadTask} only, and only when the row's art key
 * ({@link MiniPlayerRows#artKey}: player id + art URI) is one this adapter has not seen for that
 * player yet; that is also the only moment {@code Player#getIcon()} is read (it can queue a
 * download of its own on every call). A view holder that is rebound to a key already seen (after
 * it was recycled) shows the placeholder or the one {@code getIcon()} result and then looks the
 * art up in the in-memory {@link IconDownloadCacheHandler}: no request.
 */
final class MiniPlayerAdapter extends RecyclerView.Adapter<MiniPlayerAdapter.RowHolder> {

    /** What a row's buttons ask for; the controller runs it. */
    enum Command {
        PREVIOUS, PLAY_PAUSE, NEXT, STOP, EXIT
    }

    /** Where taps go. Called on the main thread. */
    interface Actions {
        void onOpen(@NonNull Player player);

        void onCommand(@NonNull Player player, @NonNull Command command);
    }

    /** What one row shows: an immutable snapshot taken by the controller. */
    static final class Row {
        final int playerId;
        final Player player;
        final String title;
        final String receiver;
        final boolean playing;
        final String artUri;
        final String artKey;

        Row(int playerId, @NonNull Player player, @NonNull String title, @NonNull String receiver,
            boolean playing, String artUri) {
            this.playerId = playerId;
            this.player = player;
            this.title = title;
            this.receiver = receiver;
            this.playing = playing;
            this.artUri = artUri;
            this.artKey = MiniPlayerRows.artKey(playerId, artUri);
        }

        boolean sameContentAs(Row other) {
            return player == other.player && playing == other.playing
                    && title.equals(other.title) && receiver.equals(other.receiver)
                    && Objects.equals(artKey, other.artKey);
        }
    }

    private static final class RowDiff extends DiffUtil.Callback {
        private final List<Row> oldRows;
        private final List<Row> newRows;

        RowDiff(List<Row> oldRows, List<Row> newRows) {
            this.oldRows = oldRows;
            this.newRows = newRows;
        }

        @Override
        public int getOldListSize() {
            return oldRows.size();
        }

        @Override
        public int getNewListSize() {
            return newRows.size();
        }

        @Override
        public boolean areItemsTheSame(int oldPosition, int newPosition) {
            return oldRows.get(oldPosition).playerId == newRows.get(newPosition).playerId;
        }

        @Override
        public boolean areContentsTheSame(int oldPosition, int newPosition) {
            return oldRows.get(oldPosition).sameContentAs(newRows.get(newPosition));
        }
    }

    private final Actions actions;
    private final MiniPlayerArtKeys artKeys = new MiniPlayerArtKeys();
    // the one getIcon() result per player and key: restores the icon of a recycled holder
    private final SparseArray<Bitmap> icons = new SparseArray<>();
    private List<Row> rows = Collections.emptyList();

    MiniPlayerAdapter(@NonNull Actions actions) {
        this.actions = actions;
        setHasStableIds(true);
    }

    /** Shows {@code newRows} (distinct player ids, in display order), touching only what changed. */
    void submit(@NonNull List<Row> newRows) {
        List<Row> next = Collections.unmodifiableList(new ArrayList<>(newRows));
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new RowDiff(rows, next));
        rows = next;
        // players that are gone take their art key and icon with them: the maps follow the list
        List<Integer> ids = new ArrayList<>(next.size());
        for (Row row : next) {
            ids.add(row.playerId);
        }
        artKeys.retainOnly(ids);
        for (int i = icons.size() - 1; i >= 0; i--) {
            if (!ids.contains(icons.keyAt(i))) {
                icons.removeAt(i);
            }
        }
        diff.dispatchUpdatesTo(this);
    }

    /** Removes every row and forgets the art bookkeeping (the bar was unbound). */
    void clear() {
        submit(Collections.<Row>emptyList());
        artKeys.clear();
        icons.clear();
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    @Override
    public long getItemId(int position) {
        return rows.get(position).playerId;
    }

    @NonNull
    @Override
    public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.mini_player_row, parent, false);
        RowHolder holder = new RowHolder(view);
        holder.previous.setOnClickListener(v -> command(holder, Command.PREVIOUS));
        holder.playPause.setOnClickListener(v -> command(holder, Command.PLAY_PAUSE));
        holder.next.setOnClickListener(v -> command(holder, Command.NEXT));
        holder.stop.setOnClickListener(v -> command(holder, Command.STOP));
        holder.exit.setOnClickListener(v -> command(holder, Command.EXIT));
        view.setOnClickListener(v -> {
            Row row = holder.row;
            if (row != null) {
                actions.onOpen(row.player);
            }
        });
        ViewCompat.setAccessibilityDelegate(view, new AccessibilityDelegateCompat() {
            @Override
            public void onInitializeAccessibilityNodeInfo(@NonNull View host, @NonNull AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.addAction(new AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        AccessibilityNodeInfoCompat.ACTION_CLICK, host.getContext().getString(R.string.mini_player_open)));
            }
        });
        return holder;
    }

    private void command(RowHolder holder, Command command) {
        Row row = holder.row;
        if (row != null) {
            actions.onCommand(row.player, command);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RowHolder holder, int position) {
        Row row = rows.get(position);
        holder.row = row;
        holder.title.setText(row.title);
        holder.receiver.setText(row.receiver);
        holder.playPause.setImageResource(row.playing
                ? R.drawable.ic_baseline_pause_24 : R.drawable.ic_baseline_play_arrow_24);
        holder.playPause.setContentDescription(holder.itemView.getContext().getString(row.playing
                ? R.string.mini_player_pause : R.string.mini_player_play));
        bindArt(holder, row);
    }

    @Override
    public void onViewRecycled(@NonNull RowHolder holder) {
        // a blank holder: a late art result for the old key drops itself on the tag compare
        holder.row = null;
        holder.shownArtKey = null;
        holder.art.setTag(null);
    }

    private void bindArt(RowHolder holder, Row row) {
        if (row.artKey.equals(holder.shownArtKey)) {
            return; // this holder already shows (or is loading) this art
        }
        holder.shownArtKey = row.artKey;
        // IconDownloadTask drops its result when the view's tag is no longer this one
        holder.art.setTag(row.artKey);
        boolean firstTime = artKeys.isNewKey(row.playerId, row.artKey);
        if (firstTime) {
            Bitmap icon = row.player.getIcon();
            if (icon == null) {
                icons.remove(row.playerId);
            } else {
                icons.put(row.playerId, icon);
            }
        }
        Bitmap icon = icons.get(row.playerId);
        if (icon != null) {
            holder.art.setImageBitmap(icon);
        } else {
            showPlaceholder(holder, row.player);
        }
        if (row.artUri == null) {
            return;
        }
        Uri uri = Uri.parse(row.artUri);
        if (firstTime) {
            new IconDownloadTask(holder.art, row.artKey).execute(uri);
        } else {
            // seen before (the holder was recycled): the cache only, never a request
            Bitmap cached = IconDownloadCacheHandler.getInstance().getBitmap(uri, 48, 48);
            if (cached != null) {
                holder.art.setImageBitmap(cached);
            }
        }
    }

    private static void showPlaceholder(RowHolder holder, Player player) {
        ImageView art = holder.art;
        if (player.getIconResourceId() == R.drawable.yaacc48_24_png) {
            art.setImageResource(R.drawable.yaacc48_24_png);
            return;
        }
        Drawable placeholder = ResourcesCompat.getDrawable(
                art.getResources(), player.getIconResourceId(), art.getContext().getTheme());
        if (placeholder != null) {
            art.setImageDrawable(ThemeHelper.tintDrawable(placeholder, art.getContext().getTheme()));
        }
    }

    static final class RowHolder extends RecyclerView.ViewHolder {
        final ImageView art;
        final TextView title;
        final TextView receiver;
        final ImageButton previous;
        final ImageButton playPause;
        final ImageButton next;
        final ImageButton stop;
        final ImageButton exit;
        Row row;
        String shownArtKey;

        RowHolder(@NonNull View itemView) {
            super(itemView);
            art = itemView.findViewById(R.id.miniPlayerArt);
            title = itemView.findViewById(R.id.miniPlayerTitle);
            receiver = itemView.findViewById(R.id.miniPlayerReceiver);
            previous = itemView.findViewById(R.id.miniPlayerPrevious);
            playPause = itemView.findViewById(R.id.miniPlayerPlayPause);
            next = itemView.findViewById(R.id.miniPlayerNext);
            stop = itemView.findViewById(R.id.miniPlayerStop);
            exit = itemView.findViewById(R.id.miniPlayerExit);
        }
    }
}
