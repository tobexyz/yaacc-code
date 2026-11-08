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

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import de.yaacc.R;

/**
 * A {@link RecyclerView} that wraps its content up to a cap and scrolls inside beyond it: the
 * smaller of {@code R.dimen.mini_player_list_max_height} (about three mini-player rows) and
 * {@value #MAX_SHARE_OF_AVAILABLE_PERCENT} percent of the height the parent offers (so a
 * landscape phone keeps room for the content). With fewer rows than the cap it is exactly as
 * high as its rows: no empty space.
 */
public class MaxHeightRecyclerView extends RecyclerView {

    /** Share of the offered height the list may take, in percent. */
    static final int MAX_SHARE_OF_AVAILABLE_PERCENT = 40;

    private final int maxHeightPx;

    public MaxHeightRecyclerView(@NonNull Context context) {
        this(context, null);
    }

    public MaxHeightRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MaxHeightRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        maxHeightPx = context.getResources().getDimensionPixelSize(R.dimen.mini_player_list_max_height);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int mode = View.MeasureSpec.getMode(heightSpec);
        int offered = View.MeasureSpec.getSize(heightSpec);
        int cap = maxHeightPx;
        if (mode != View.MeasureSpec.UNSPECIFIED) {
            cap = Math.min(cap, offered * MAX_SHARE_OF_AVAILABLE_PERCENT / 100);
        }
        // EXACTLY is the layout's own decision (e.g. match_parent); only wrap_content is capped
        if (mode != View.MeasureSpec.EXACTLY) {
            heightSpec = View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST);
        }
        super.onMeasure(widthSpec, heightSpec);
    }
}
