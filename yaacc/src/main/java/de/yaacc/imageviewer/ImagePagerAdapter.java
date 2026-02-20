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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package de.yaacc.imageviewer;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import java.util.List;

import de.yaacc.util.YaaccLogger;

/**
 * ViewPager2 adapter for image slideshow.
 */
public class ImagePagerAdapter extends FragmentStateAdapter {
    private List<Uri> imageUris;

    public ImagePagerAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }

    public void setImageUris(List<Uri> uris) {
        this.imageUris = uris;
        // Don't call notifyDataSetChanged() - ViewPager2 handles updates
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        if (imageUris != null && position < imageUris.size()) {
            return ImageFragment.newInstance(imageUris.get(position));
        }
        return new ImageFragment();
    }

    @Override
    public int getItemCount() {
        return imageUris != null ? imageUris.size() : 0;
    }

    public Uri getItem(int position) {
        if (imageUris != null && position < imageUris.size()) {
            return imageUris.get(position);
        }
        return null;
    }
}
