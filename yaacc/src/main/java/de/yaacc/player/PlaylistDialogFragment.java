/*
 * Copyright (C) 2024 Tobias Schoene www.yaacc.de
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
package de.yaacc.player;

import android.app.Dialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import java.beans.PropertyChangeListener;

import de.yaacc.R;
import de.yaacc.Yaacc;

public class PlaylistDialogFragment extends DialogFragment {
    public static final String TAG = "playlist_dialog";
    private Toolbar toolbar;
    private Player player;
    private PlaylistItemAdapter playlistItemAdapter;
    private PropertyChangeListener playerListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public PlaylistDialogFragment() {
    }

    private PlaylistDialogFragment(Player player) {
        this.player = player;
    }


    static PlaylistDialogFragment show(FragmentManager fragmentManager, Player player) {
        PlaylistDialogFragment fragment = new PlaylistDialogFragment(player);
        fragment.show(fragmentManager, TAG);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(DialogFragment.STYLE_NORMAL, R.style.AppTheme_FullScreenDialog);
    }


    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null) {
            int width = ViewGroup.LayoutParams.MATCH_PARENT;
            int height = ViewGroup.LayoutParams.MATCH_PARENT;
            dialog.getWindow().setLayout(width, height);
            dialog.getWindow().setWindowAnimations(R.style.AppTheme_Slide);
        }

    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        super.onSaveInstanceState(savedInstanceState);
        savedInstanceState.putInt("player", player.getId());

    }


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);
        if (savedInstanceState != null && getActivity() != null) {
            player = ((Yaacc) getActivity().getApplicationContext()).getUpnpClient().getPlayer(savedInstanceState.getInt("player")).orElse(null);
        }
        View view = inflater.inflate(R.layout.fragment_playlist_dialog, container, false);
        RecyclerView itemList = view.findViewById(R.id.playlistDialogList);
        // The adapter shows the playlist in the order it plays (list order, the shuffled play order with shuffle)
        playlistItemAdapter = new PlaylistItemAdapter(getActivity(), itemList, player);
        itemList.setAdapter(playlistItemAdapter);
        ItemTouchHelper itemTouchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                int fromPosition = viewHolder.getBindingAdapterPosition();
                int toPosition = target.getBindingAdapterPosition();
                return playlistItemAdapter.moveItem(fromPosition, toPosition);
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            }
        });
        itemTouchHelper.attachToRecyclerView(itemList);

        toolbar = view.findViewById(R.id.toolbar);

        if (player != null) {
            // Follow the player: shuffle switched (list <-> play order), items added, removed or moved,
            // another current item. Events come from any thread.
            playerListener = evt -> {
                String property = evt.getPropertyName();
                boolean playlist = Player.PROPERTY_PLAY_MODE.equals(property) || Player.PROPERTY_PLAYLIST.equals(property);
                boolean state = AbstractPlayer.PROPERTY_ITEM.equals(property) || "playing".equals(property);
                if (playlist || state) {
                    mainHandler.post(() -> {
                        if (playlistItemAdapter != null) {
                            playlistItemAdapter.refresh(state);
                        }
                    });
                }
            };
            player.addPropertyChangeListener(playerListener);
        }

        return view;
    }

    @Override
    public void onDestroyView() {
        if (player != null && playerListener != null) {
            player.removePropertyChangeListener(playerListener);
        }
        playerListener = null;
        playlistItemAdapter = null;
        super.onDestroyView();
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        toolbar.setNavigationOnClickListener(v -> dismiss());
        toolbar.setTitle(R.string.playlist);
        toolbar.setOnMenuItemClickListener(item -> {
            dismiss();
            return true;
        });
    }


}
