package de.yaacc.browser;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

public class TabBrowserFragmentStateAdapter extends FragmentStateAdapter {

    public TabBrowserFragmentStateAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        BrowserTabs content = BrowserTabs.valueOf(position);
        switch (content) {
            case CONTENT: {
                return new ContentListFragment();
            }
            case SERVER: {
                return new ServerListFragment();
            }
            case PLAYER: {
                return new PlayerListFragment();

            }
            case RECEIVER: {
                return new ReceiverListFragment();
            }
            default: {
                return new ContentListFragment();
            }
        }


    }

    @Override
    public int getItemCount() {
        return BrowserTabs.values().length;
    }
}
