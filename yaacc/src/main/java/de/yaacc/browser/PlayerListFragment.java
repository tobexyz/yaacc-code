/*
 * Copyright (C) 2014 Tobias Schoene www.yaacc.de
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package de.yaacc.browser;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.fourthline.cling.model.meta.Device;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.UpnpClientListener;

/**
 * Activity for browsing devices and folders. Represents the entrypoint for the whole application.
 *
 * @author Tobias Schoene (the openbit)
 */
public class PlayerListFragment extends Fragment implements
        UpnpClientListener, OnBackPressedListener {

    protected RecyclerView contentList;
    private UpnpClient upnpClient = null;
    private PlayerListItemAdapter itemAdapter;

    private void init(Bundle savedInstanceState, View view) {
        upnpClient = ((Yaacc) requireActivity().getApplicationContext()).getUpnpClient();
        contentList = view.findViewById(R.id.playerList);
        contentList.setLayoutManager(new LinearLayoutManager(getActivity()));
        upnpClient.addUpnpClientListener(this);
        Thread thread = new Thread(this::populatePlayerList);
        thread.start();

    }

    @Override
    public void onResume() {
        super.onResume();
        Thread thread = new Thread(this::populatePlayerList);
        thread.start();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

    }

    /**
     * Selects the place in the UI where the items are shown and renders the
     * content directory
     */
    private void populatePlayerList() {

        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                if (contentList.getAdapter() == null) {
                    itemAdapter = new PlayerListItemAdapter(contentList, upnpClient.getCurrentPlayers());
                    contentList.setAdapter(itemAdapter);

                } else {
                    itemAdapter.setItems(upnpClient.getCurrentPlayers());
                }


            });
        }
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    public boolean onBackPressed() {
        Log.d(PlayerListFragment.class.getName(), "onBackPressed() CurrentPosition");
        if (requireActivity().getParent() instanceof TabBrowserActivity) {
            ((TabBrowserActivity) requireActivity().getParent()).setCurrentTab(BrowserTabs.RECEIVER);
        }
        return true;

    }


    @Override
    public void deviceAdded(Device<?, ?, ?> device) {

    }

    @Override
    public void deviceRemoved(Device<?, ?, ?> device) {

    }

    @Override
    public void deviceUpdated(Device<?, ?, ?> device) {

    }

    @Override
    public void receiverDeviceRemoved(Device<?, ?, ?> device) {

    }

    @Override
    public void receiverDeviceAdded(Device<?, ?, ?> device) {

    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        return inflater.inflate(R.layout.fragment_player_list, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        init(savedInstanceState, view);
    }
} 