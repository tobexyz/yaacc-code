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

import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import android.content.Context;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import org.fourthline.cling.model.meta.Device;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import de.yaacc.upnp.UpnpClient;

/**
 * A tap on a server picks that server, also when the servers are only one part of the list: on the top-level page a header row comes
 * before them, so the position of the row in the list is not its position in the servers adapter.
 */
public class ServerListClickListenerTest {

    @Test
    public void theTappedServerIsPickedByItsPositionInTheServersAdapter() {
        Device<?, ?, ?> first = mock(Device.class);
        Device<?, ?, ?> second = mock(Device.class);
        BrowseDeviceAdapter adapter = mock(BrowseDeviceAdapter.class);
        doReturn(first).when(adapter).getItem(0);
        doReturn(second).when(adapter).getItem(1);
        RecyclerView list = mock(RecyclerView.class);
        View row = mock(View.class);
        RecyclerView.ViewHolder holder = mock(RecyclerView.ViewHolder.class);
        // one header row comes before the servers: the row is at position 1 of the list, but the first server of the adapter
        when(list.getChildAdapterPosition(row)).thenReturn(1);
        when(list.getChildViewHolder(row)).thenReturn(holder);
        when(holder.getBindingAdapterPosition()).thenReturn(0);
        UpnpClient client = mock(UpnpClient.class);
        Context host = mock(Context.class, withSettings().extraInterfaces(BrowserHost.class));

        new ServerListClickListener(list, adapter, client, host).onClick(row);

        ArgumentCaptor<Device<?, ?, ?>> picked = ArgumentCaptor.forClass(Device.class);
        verify(client).setProviderDevice(picked.capture());
        assertSame(first, picked.getValue());
        verify((BrowserHost) host).showContent();
    }

    @Test
    public void aRowThatIsBeingRemovedPicksNothing() {
        BrowseDeviceAdapter adapter = mock(BrowseDeviceAdapter.class);
        RecyclerView list = mock(RecyclerView.class);
        View row = mock(View.class);
        RecyclerView.ViewHolder holder = mock(RecyclerView.ViewHolder.class);
        when(list.getChildViewHolder(row)).thenReturn(holder);
        when(holder.getBindingAdapterPosition()).thenReturn(RecyclerView.NO_POSITION);
        UpnpClient client = mock(UpnpClient.class);
        Context host = mock(Context.class, withSettings().extraInterfaces(BrowserHost.class));

        new ServerListClickListener(list, adapter, client, host).onClick(row);

        verify(client, never()).setProviderDevice(any());
        verify((BrowserHost) host, never()).showContent();
    }
}
