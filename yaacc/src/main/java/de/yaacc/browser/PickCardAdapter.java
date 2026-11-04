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

import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import de.yaacc.R;

/**
 * The one card "Play from this device" below the servers of the server list: two buttons start the
 * system picker for files or for a folder. It has no server behind it and nothing is browsed.
 */
final class PickCardAdapter extends RecyclerView.Adapter<PickCardAdapter.Holder> {

    private final Runnable pickFiles;
    private final Runnable pickFolder;

    PickCardAdapter(Runnable pickFiles, Runnable pickFolder) {
        this.pickFiles = pickFiles;
        this.pickFolder = pickFolder;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.browse_pick_item, parent, false);
        Holder holder = new Holder(view);
        // a tap on the card is the default action: pick files (the folder has its own button)
        view.setOnClickListener(v -> pickFiles.run());
        view.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    v.performClick();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    // as on the server cards: on to the first action button
                    return holder.files.requestFocus();
                default:
                    return false;
            }
        });
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.files.setOnClickListener(v -> pickFiles.run());
        holder.folder.setOnClickListener(v -> pickFolder.run());
    }

    @Override
    public int getItemCount() {
        return 1;
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View files;
        final View folder;

        Holder(View itemView) {
            super(itemView);
            files = itemView.findViewById(R.id.browsePickFiles);
            folder = itemView.findViewById(R.id.browsePickFolder);
        }
    }
}
