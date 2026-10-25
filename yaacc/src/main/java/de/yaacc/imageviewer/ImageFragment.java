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
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;

import de.yaacc.share.ShareLogRedactor;
import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.util.YaaccLogger;
import de.yaacc.util.image.BitmapSampling;

/**
 * Fragment for displaying a single image in ViewPager2.
 */
public class ImageFragment extends Fragment {
    private static final String ARG_URI = "uri";
    private Uri uri;
    private ImageView imageView;
    private ProgressBar progressBar;

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
        progressBar = view.findViewById(R.id.progressBar);
        
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

        YaaccLogger.d(getClass().getName(), "Loading image: " + ShareLogRedactor.redact(String.valueOf(imageUri)));

        // Show loading indicator
        if (progressBar != null) {
            progressBar.setVisibility(View.VISIBLE);
        }
        imageView.setImageBitmap(null);

        // Use existing ContentLoadExecutor
        Yaacc app = (Yaacc) requireActivity().getApplication();
        app.getContentLoadExecutor().execute(() -> {
            Bitmap bitmap = loadBitmap(imageUri);
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    // Hide loading indicator
                    if (progressBar != null) {
                        progressBar.setVisibility(View.GONE);
                    }
                    if (imageView != null && bitmap != null) {
                        imageView.setImageBitmap(bitmap);
                    } else if (imageView != null) {
                        imageView.setImageResource(R.drawable.yaacc192_32);
                    }
                });
            }
        });
    }

    /**
     * Upper bound (per axis) for the decoded bitmap, regardless of screen
     * size. Keeps memory/time bounded for very high-resolution photos while
     * still giving the PhotoView pinch-zoom viewer real detail to zoom into
     * -- also comfortably under the ~4096px texture-size limit most GPUs
     * enforce (avoids a "Canvas: trying to draw too large bitmap" crash on
     * some devices for extreme-resolution sources).
     */
    private static final int MAX_DECODE_DIMENSION_PX = 4096;

    /**
     * Loads the full image into memory once (as compressed bytes, not a
     * decoded bitmap) and decodes it downsampled to a size based on the
     * screen -- previously this decoded the bitmap at full native
     * resolution with no size hint at all, which for a 20-50MP photo means
     * allocating/decoding a 100MB+ bitmap just to display it shrunk to fit
     * a phone screen. The compressed bytes are far smaller than even a
     * downsampled bitmap, so buffering them first is a large net memory
     * win, not a cost, versus the previous direct-decode-from-stream
     * approach.
     */
    private Bitmap loadBitmap(Uri imageUri) {
        try {
            if (getActivity() != null) {
                String scheme = imageUri.getScheme();
                byte[] imageBytes;
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    // No EXIF support for remote images (pre-existing
                    // limitation, unrelated to this fix).
                    try (InputStream in = new java.net.URL(imageUri.toString()).openStream()) {
                        imageBytes = BitmapSampling.readAllBytes(in);
                    }
                    return decodeSampledBitmap(imageBytes);
                } else {
                    try (InputStream in = getActivity().getContentResolver().openInputStream(imageUri)) {
                        if (in == null) return null;
                        imageBytes = BitmapSampling.readAllBytes(in);
                    }
                    Bitmap bitmap = decodeSampledBitmap(imageBytes);
                    return applyExifRotation(bitmap, imageBytes);
                }
            }
        } catch (Exception e) {
            YaaccLogger.e(getClass().getName(), "Failed to load image: " + ShareLogRedactor.redact(String.valueOf(imageUri)), e);
        }
        return null;
    }

    /**
     * Two-pass decode: first pass reads only the image's dimensions
     * ({@code inJustDecodeBounds}, no pixel memory allocated), then the
     * real decode requests the smallest power-of-two {@code inSampleSize}
     * that still covers the target size -- the decoder itself skips pixels
     * while decoding, rather than decoding every pixel and scaling down
     * afterward (the standard Android pattern for this; see
     * "Loading Large Bitmaps Efficiently" in the Android developer guide).
     */
    private Bitmap decodeSampledBitmap(byte[] imageBytes) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int target = Math.min(Math.max(metrics.widthPixels, metrics.heightPixels) * 2, MAX_DECODE_DIMENSION_PX);

        BitmapFactory.Options boundsOptions = new BitmapFactory.Options();
        boundsOptions.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, boundsOptions);

        BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
        decodeOptions.inSampleSize = BitmapSampling.computeInSampleSizeForMaxDimension(boundsOptions, target);
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, decodeOptions);
    }

    private Bitmap applyExifRotation(Bitmap bitmap, byte[] imageBytes) {
        if (bitmap == null) return null;
        try (InputStream in = new ByteArrayInputStream(imageBytes)) {
            ExifInterface exif = new ExifInterface(in);
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int degrees = 0;
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:  degrees = 90;  break;
                case ExifInterface.ORIENTATION_ROTATE_180: degrees = 180; break;
                case ExifInterface.ORIENTATION_ROTATE_270: degrees = 270; break;
                default: return bitmap; // No rotation needed
            }
            Matrix matrix = new Matrix();
            matrix.postRotate(degrees);
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            bitmap.recycle();
            return rotated;
        } catch (Exception e) {
            YaaccLogger.e(getClass().getName(), "Failed to read EXIF orientation", e);
            return bitmap;
        }
    }

    public void setImageUri(Uri uri) {
        this.uri = uri;
        if (imageView != null) {
            loadImage(uri);
        }
    }
}
