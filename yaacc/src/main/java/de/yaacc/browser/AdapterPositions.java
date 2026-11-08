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

import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Positions of rows in their own adapter. A list may be built from several adapters ({@code ConcatAdapter}: the top-level page has a
 * section header before the servers), then {@link RecyclerView#getChildAdapterPosition} gives the position in the whole list, which
 * is not the position in the adapter that owns the row.
 */
final class AdapterPositions {
    private AdapterPositions() {
    }

    /**
     * @return the position of the row in the adapter that bound it, {@link RecyclerView#NO_POSITION} if the row is being removed or
     * is not a row of the list
     */
    static int bindingPosition(RecyclerView list, View row) {
        RecyclerView.ViewHolder holder = list.getChildViewHolder(row);
        return holder == null ? RecyclerView.NO_POSITION : holder.getBindingAdapterPosition();
    }

    /**
     * The rows of one adapter that are laid out right now, by their position in that adapter (rows of other adapters of the list, rows
     * of another type and rows that are being removed are left out).
     */
    static <H extends RecyclerView.ViewHolder> Map<Integer, H> laidOutRows(RecyclerView list, RecyclerView.Adapter<?> owner, Class<H> type) {
        Map<Integer, H> rows = new LinkedHashMap<>();
        for (int i = 0; i < list.getChildCount(); i++) {
            RecyclerView.ViewHolder holder = list.getChildViewHolder(list.getChildAt(i));
            if (type.isInstance(holder) && holder.getBindingAdapter() == owner) {
                int position = holder.getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    rows.put(position, type.cast(holder));
                }
            }
        }
        return rows;
    }
}
