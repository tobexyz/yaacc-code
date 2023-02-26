/*
 * Copyright (C) 2023 Tobias Schoene www.yaacc.de
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

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

public class TabBrowserFragmentStateAdapter extends FragmentStateAdapter {

    private ContentListFragment contentListFragment;
    private ServerListFragment serverListFragment;
    private PlayerListFragment playerListFragment;
    private ReceiverListFragment receiverListFragment;


    public TabBrowserFragmentStateAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        BrowserTabs content = BrowserTabs.valueOf(position);
        if (content == null) {

            return getContentListFragment();
        }
        switch (content) {
            case CONTENT: {
                return getContentListFragment();
            }
            case SERVER: {
                return getServerListFragment();
            }
            case PLAYER: {
                return getPlayerListFragment();

            }
            case RECEIVER: {
                return getReceiverListFragment();
            }
        }
        return getContentListFragment();

    }

    private Fragment getReceiverListFragment() {
        if (receiverListFragment == null) {
            receiverListFragment = new ReceiverListFragment();
        }
        return receiverListFragment;
    }

    private Fragment getPlayerListFragment() {
        if (playerListFragment == null) {
            playerListFragment = new PlayerListFragment();
        }
        return playerListFragment;
    }

    private Fragment getServerListFragment() {
        if (serverListFragment == null) {
            serverListFragment = new ServerListFragment();
        }
        return serverListFragment;
    }

    private Fragment getContentListFragment() {
        if (contentListFragment == null) {
            contentListFragment = new ContentListFragment();
        }
        return contentListFragment;
    }

    @Override
    public int getItemCount() {
        return BrowserTabs.values().length;
    }
}
