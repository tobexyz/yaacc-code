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

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Collection;

import de.yaacc.R;
import de.yaacc.upnp.UpnpClient;

/**
 * A line of text below a section header that is there only while the section is empty ("No receivers found yet."). One item, or none.
 * Main thread only.
 */
final class SectionHintAdapter extends RecyclerView.Adapter<SectionHintAdapter.Holder> {

    private boolean visible;

    /**
     * The list of receivers always holds this phone (the local renderer), so "no receiver found" means that no other receiver is listed.
     *
     * @param receiverIds the ids of the receivers listed
     */
    static boolean noRemoteReceiver(Collection<String> receiverIds) {
        for (String id : receiverIds) {
            if (!UpnpClient.LOCAL_UID.equals(id)) {
                return false;
            }
        }
        return true;
    }

    /** Shows or hides the line; nothing is notified when the state does not change. */
    void setVisible(boolean visible) {
        if (this.visible == visible) {
            return;
        }
        this.visible = visible;
        if (visible) {
            notifyItemInserted(0);
        } else {
            notifyItemRemoved(0);
        }
    }

    boolean isVisible() {
        return visible;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.server_list_section_hint, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        // the text is fixed in the layout
    }

    @Override
    public int getItemCount() {
        return visible ? 1 : 0;
    }

    static final class Holder extends RecyclerView.ViewHolder {
        Holder(@NonNull View itemView) {
            super(itemView);
        }
    }
}
