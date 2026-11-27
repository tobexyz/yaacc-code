/*
 *
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

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import de.yaacc.R;
import de.yaacc.sendspin.player.DisplayLook;
import de.yaacc.sendspin.player.DisplaySnapshot;
import de.yaacc.sendspin.player.SendspinPlayerHost;
import de.yaacc.sendspin.player.SendspinPlayerRuntime;

/**
 * The display of this phone as a Sendspin player (issue #325, task 11.5): what a server shows on it through the metadata, artwork and
 * color roles, full screen: the cover, title, artist and album, the progress, and the colours of the server as the background (the
 * dark background colour and its light text colour; black and white until the server sends colours). The screen stays on while it is
 * open; a tap closes it. Shows what the server sends and nothing else; the image is decoded with a size cap.
 */
public class SendspinDisplayActivity extends AppCompatActivity {

    /** The longest side of the decoded cover: the server delivers 512 pixels, a larger image is subsampled. */
    private static final int MAX_SIDE = 1024;
    private static final long REFRESH_MILLIS = 500L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout root;
    private ImageView cover;
    private TextView title;
    private TextView artist;
    private TextView album;
    private TextView hint;
    private ProgressBar progress;
    private long shownVersion = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (24 * density);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.BLACK);
        cover = new ImageView(this);
        cover.setAdjustViewBounds(true);
        cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(cover, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        title = text(28f, true);
        artist = text(20f, false);
        album = text(16f, false);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        root.addView(title);
        root.addView(artist);
        root.addView(album);
        root.addView(progress, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        hint = text(14f, false);
        hint.setText(R.string.sendspin_display_waiting);
        root.addView(hint);
        root.setOnClickListener(v -> finish());
        setContentView(root);
        hideSystemBars();
    }

    private TextView text(float sp, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setTextColor(Color.WHITE);
        if (bold) {
            t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        }
        t.setPadding(0, (int) (6 * getResources().getDisplayMetrics().density), 0, 0);
        return t;
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        main.post(refresh);
    }

    @Override
    protected void onPause() {
        main.removeCallbacksAndMessages(null);
        super.onPause();
    }

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            SendspinPlayerHost host = SendspinPlayerRuntime.host();
            DisplaySnapshot snapshot = host == null ? null : host.displaySnapshot();
            draw(snapshot);
            main.postDelayed(this, REFRESH_MILLIS);
        }
    };

    private void draw(DisplaySnapshot s) {
        if (s == null || s.isEmpty()) {
            hint.setVisibility(View.VISIBLE);
            hint.setText(s == null ? R.string.sendspin_display_no_server : R.string.sendspin_display_waiting);
            title.setText("");
            artist.setText("");
            album.setText("");
            progress.setVisibility(View.INVISIBLE);
            cover.setImageDrawable(null);
            shownVersion = -1;
            root.setBackgroundColor(Color.BLACK);
            return;
        }
        hint.setVisibility(View.GONE);
        DisplayLook look = DisplayLook.of(s.color, s.showsContent());
        setFullBrightness(look.lightMode);
        root.setBackgroundColor(look.background);
        if (look.lightMode) {
            // only colours arrive: the screen is the colour of the server as it is, nothing else on it
            title.setText("");
            artist.setText("");
            album.setText("");
            progress.setVisibility(View.INVISIBLE);
            cover.setImageDrawable(null);
            shownVersion = -1;
            return;
        }
        int foreground = look.foreground;
        for (TextView t : new TextView[]{title, artist, album, hint}) {
            t.setTextColor(foreground);
        }
        title.setText(s.title == null ? "" : s.title);
        artist.setText(s.artist == null ? "" : s.artist);
        album.setText(s.album == null ? "" : s.album);
        if (s.durationMs > 0 && s.positionMs >= 0) {
            progress.setVisibility(View.VISIBLE);
            progress.setProgress((int) (1000L * Math.min(s.positionMs, s.durationMs) / s.durationMs));
        } else {
            progress.setVisibility(View.INVISIBLE);
        }
        if (s.artworkVersion != shownVersion) {
            shownVersion = s.artworkVersion;
            cover.setImageBitmap(decode(s.artwork));
        }
    }

    /** The window at full brightness while it is a light, so that the colour is shown as the server sent it; the phone's own setting otherwise. */
    private void setFullBrightness(boolean full) {
        WindowManager.LayoutParams params = getWindow().getAttributes();
        float wanted = full ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        if (params.screenBrightness != wanted) {
            params.screenBrightness = wanted;
            getWindow().setAttributes(params);
        }
    }

    /** Decodes the image of the server with a size cap; an image that is not one gives null (the cover stays empty). */
    private static Bitmap decode(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            int sample = 1;
            while (bounds.outWidth / sample > MAX_SIDE || bounds.outHeight / sample > MAX_SIDE) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } catch (RuntimeException | OutOfMemoryError broken) {
            return null;
        }
    }
}
