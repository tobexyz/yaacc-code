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
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import de.yaacc.R;
import de.yaacc.util.ThemeHelper;

/**
 * The header row of a section of the top-level page ("Servers", "Receivers"): the icon of the kind of device in front of the label, the
 * label as an accessibility heading and, if the section offers it, the refresh button (the search for devices finds servers and
 * receivers alike, so both headers may carry it). One item.
 */
final class SectionHeaderAdapter extends RecyclerView.Adapter<SectionHeaderAdapter.Holder> {

    private final int labelRes;
    private final int iconRes;
    private final Runnable onRefresh;

    /**
     * @param labelRes  the label
     * @param iconRes   the icon (a drawable that {@link ThemeHelper#tintDrawable} can tint)
     * @param onRefresh what the refresh button does; null for a header without one
     */
    SectionHeaderAdapter(@StringRes int labelRes, @DrawableRes int iconRes, @Nullable Runnable onRefresh) {
        this.labelRes = labelRes;
        this.iconRes = iconRes;
        this.onRefresh = onRefresh;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.server_list_section_header, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.label.setText(labelRes);
        ViewCompat.setAccessibilityHeading(holder.label, true);
        android.content.Context context = holder.itemView.getContext();
        holder.icon.setImageDrawable(ThemeHelper.tintDrawable(
                ResourcesCompat.getDrawable(context.getResources(), iconRes, context.getTheme()), context.getTheme()));
        if (onRefresh == null) {
            holder.refresh.setVisibility(View.GONE);
            holder.refresh.setOnClickListener(null);
        } else {
            holder.refresh.setVisibility(View.VISIBLE);
            holder.refresh.setOnClickListener(v -> onRefresh.run());
        }
    }

    @Override
    public int getItemCount() {
        return 1;
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView label;
        final View refresh;

        Holder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.sectionHeaderIcon);
            label = itemView.findViewById(R.id.sectionHeaderLabel);
            refresh = itemView.findViewById(R.id.sectionHeaderRefresh);
        }
    }
}
