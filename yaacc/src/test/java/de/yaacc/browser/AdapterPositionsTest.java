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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import org.junit.Test;

import java.util.Map;

/**
 * The position of a row in its own adapter: one part of a {@code ConcatAdapter} has other rows (a section header) before it, so the
 * position in the list is not the position in the adapter.
 */
public class AdapterPositionsTest {

    @Test
    public void theBindingPositionIsNotThePositionInTheList() {
        RecyclerView list = mock(RecyclerView.class);
        View row = mock(View.class);
        RecyclerView.ViewHolder holder = mock(RecyclerView.ViewHolder.class);
        when(list.getChildAdapterPosition(row)).thenReturn(5);
        when(list.getChildViewHolder(row)).thenReturn(holder);
        when(holder.getBindingAdapterPosition()).thenReturn(2);

        assertEquals(2, AdapterPositions.bindingPosition(list, row));
    }

    @Test
    public void aRowThatIsBeingRemovedHasNoPosition() {
        RecyclerView list = mock(RecyclerView.class);
        View row = mock(View.class);
        RecyclerView.ViewHolder holder = mock(RecyclerView.ViewHolder.class);
        when(list.getChildViewHolder(row)).thenReturn(holder);
        when(holder.getBindingAdapterPosition()).thenReturn(RecyclerView.NO_POSITION);

        assertEquals(RecyclerView.NO_POSITION, AdapterPositions.bindingPosition(list, row));
    }

    @Test
    public void aViewWithoutHolderHasNoPosition() {
        RecyclerView list = mock(RecyclerView.class);
        View row = mock(View.class);

        assertEquals(RecyclerView.NO_POSITION, AdapterPositions.bindingPosition(list, row));
    }

    // ---- the rows of one adapter that are laid out

    /** A holder of the given type that was bound by {@code owner} at {@code position}. */
    private static <H extends RecyclerView.ViewHolder> H holder(Class<H> type, RecyclerView.Adapter<?> owner, int position) {
        H holder = mock(type);
        when(holder.getBindingAdapter()).thenAnswer(invocation -> owner);
        when(holder.getBindingAdapterPosition()).thenReturn(position);
        return holder;
    }

    private static RecyclerView listWith(RecyclerView.ViewHolder... holders) {
        RecyclerView list = mock(RecyclerView.class);
        when(list.getChildCount()).thenReturn(holders.length);
        for (int i = 0; i < holders.length; i++) {
            View child = mock(View.class);
            when(list.getChildAt(i)).thenReturn(child);
            when(list.getChildViewHolder(child)).thenReturn(holders[i]);
        }
        return list;
    }

    /** Two kinds of holder, to tell the types apart. */
    static class RowHolder extends RecyclerView.ViewHolder {
        RowHolder(View itemView) {
            super(itemView);
        }
    }

    static class OtherHolder extends RecyclerView.ViewHolder {
        OtherHolder(View itemView) {
            super(itemView);
        }
    }

    @Test
    public void theRowsOfOneAdapterAreFoundByTheirPositionInThatAdapter() {
        RecyclerView.Adapter<?> servers = mock(RecyclerView.Adapter.class);
        RecyclerView.Adapter<?> receivers = mock(RecyclerView.Adapter.class);
        RowHolder header = holder(RowHolder.class, servers, 0);
        RowHolder second = holder(RowHolder.class, receivers, 1);
        RowHolder first = holder(RowHolder.class, receivers, 0);
        RecyclerView list = listWith(header, second, first);

        Map<Integer, RowHolder> rows = AdapterPositions.laidOutRows(list, receivers, RowHolder.class);

        assertEquals(2, rows.size());
        assertSame(first, rows.get(0));
        assertSame(second, rows.get(1));
    }

    @Test
    public void otherTypesAndRowsBeingRemovedAreLeftOut() {
        RecyclerView.Adapter<?> receivers = mock(RecyclerView.Adapter.class);
        RowHolder removed = holder(RowHolder.class, receivers, RecyclerView.NO_POSITION);
        OtherHolder session = holder(OtherHolder.class, receivers, 2);
        RowHolder row = holder(RowHolder.class, receivers, 1);
        RecyclerView list = listWith(removed, session, row);

        Map<Integer, RowHolder> rows = AdapterPositions.laidOutRows(list, receivers, RowHolder.class);

        assertEquals(1, rows.size());
        assertSame(row, rows.get(1));
    }

    @Test
    public void aListWithoutRowsHasNone() {
        RecyclerView list = listWith();

        assertEquals(0, AdapterPositions.laidOutRows(list, mock(RecyclerView.Adapter.class), RowHolder.class).size());
    }
}
