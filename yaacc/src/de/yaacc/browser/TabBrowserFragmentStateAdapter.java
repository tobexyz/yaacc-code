package de.yaacc.browser;

import android.os.Bundle;

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
                //FIXMEtabLayout.setSelectedTabIndicator(ContextCompat.getDrawable(TabBrowserActivity.this, R.drawable.device_48_48)).setContent(new Intent(this, ContentListActivity.class));
                //FIXMEtabLayout.setCurrentTab(Tabs.CONTENT.ordinal());
                break;
            }
            case SERVER: {
                //FIXMEtabLayout.setCurrentTab(Tabs.SERVER.ordinal());
                break;
            }
            case PLAYER: {
                //FIXMEtabLayout.setCurrentTab(Tabs.PLAYER.ordinal());
                break;
            }
            case RECEIVER: {
                //FIXMEtabLayout.setCurrentTab(Tabs.RECEIVER.ordinal());
                break;
            }
        }

        return DemoObjectFragment.newInstance(position + 1);


    }

    @Override
    public int getItemCount() {
        return BrowserTabs.values().length;
    }
}
