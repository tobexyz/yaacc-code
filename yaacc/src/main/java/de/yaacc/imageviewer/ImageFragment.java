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

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.util.YaaccLogger;

/**
 * Fragment for displaying a single image in ViewPager2.
 */
public class ImageFragment extends Fragment {
    private static final String ARG_URI = "uri";
    private Uri uri;
    private ImageView imageView;

    public static ImageFragment newInstance(Uri uri) {
        ImageFragment fragment = new ImageFragment();
        Bundle args = new Bundle();
        args.putParcelable(ARG_URI, uri);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            uri = getArguments().getParcelable(ARG_URI);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_image, container, false);
        imageView = view.findViewById(R.id.imageView);
        
        // Add click listener to toggle controls
        imageView.setOnClickListener(v -> {
            if (getActivity() instanceof ImageViewerActivity) {
                ((ImageViewerActivity) getActivity()).toggleControlsFromFragment();
            }
        });
        
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (uri != null) {
            loadImage(uri);
        }
    }

    public void loadImage(Uri imageUri) {
        if (imageView == null || imageUri == null) return;

        YaaccLogger.d(getClass().getName(), "Loading image: " + imageUri);

        // Use existing ContentLoadExecutor
        Yaacc app = (Yaacc) requireActivity().getApplication();
        app.getContentLoadExecutor().execute(() -> {
            Bitmap bitmap = loadBitmap(imageUri);
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (imageView != null && bitmap != null) {
                        imageView.setImageBitmap(bitmap);
                    } else if (imageView != null) {
                        imageView.setImageResource(R.drawable.yaacc192_32);
                    }
                });
            }
        });
    }

    private Bitmap loadBitmap(Uri imageUri) {
        try {
            if (getActivity() != null) {
                String scheme = imageUri.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    // Download from HTTP
                    java.net.URL url = new java.net.URL(imageUri.toString());
                    return android.graphics.BitmapFactory.decodeStream(url.openStream());
                } else {
                    // Local file via ContentResolver
                    return android.graphics.BitmapFactory.decodeStream(
                            getActivity().getContentResolver().openInputStream(imageUri));
                }
            }
        } catch (Exception e) {
            YaaccLogger.e(getClass().getName(), "Failed to load image: " + imageUri, e);
        }
        return null;
    }

    public void setImageUri(Uri uri) {
        this.uri = uri;
        if (imageView != null) {
            loadImage(uri);
        }
    }
}
