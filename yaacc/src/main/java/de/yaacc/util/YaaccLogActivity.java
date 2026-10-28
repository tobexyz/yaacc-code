/*
 *
 * Copyright (C) 2014 Tobias Schoene www.yaacc.de
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
package de.yaacc.util;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import de.yaacc.R;

/**
 * Shows the system log of the app. The log is read off the main thread, refreshed every couple of
 * seconds while the screen is in front, and "Clear log" hides everything up to now.
 */
public class YaaccLogActivity extends AppCompatActivity {
    private static final String CLEARED_AT_KEY = "yaacc_log_cleared_at";
    private static final long REFRESH_INTERVAL_MS = 2000L;
    /** Pixels from the bottom within which the view keeps following new lines. */
    private static final int FOLLOW_THRESHOLD_PX = 48;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService reader;
    private TextView textView;
    private ScrollView scrollView;
    private String shownLog;
    private boolean resumed;
    /** The user paused the refresh, e.g. to select and copy text; nothing changes until it is resumed. */
    private boolean paused;
    private MenuItem pauseItem;
    /** Uptime of the last touch or scroll of the user, negative if none yet; see {@link LogRefreshGate}. */
    private long lastInteractionMillis = -1L;
    /** Set while this activity scrolls the view itself, so that its own scrolling is not taken for the user's. */
    private boolean scrollingByUs;
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            if (!resumed) {
                return;
            }
            if (!paused) {
                readLog();
            }
            handler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    public static void showLog(AppCompatActivity activity) {
        activity.startActivity(new Intent(activity, YaaccLogActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_yaacc_log);
        textView = findViewById(R.id.yaaccLog_content);
        textView.setTextIsSelectable(true);
        scrollView = findViewById(R.id.yaaccLog_scrollView);
        scrollView.setOnTouchListener((v, event) -> {
            lastInteractionMillis = SystemClock.uptimeMillis();
            return false;
        });
        scrollView.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            if (!scrollingByUs) {
                lastInteractionMillis = SystemClock.uptimeMillis();
            }
        });
        reader = Executors.newSingleThreadExecutor();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(refresh);
        if (reader != null) {
            reader.shutdownNow();
        }
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_yaacc_log, menu);
        pauseItem = menu.findItem(R.id.menu_pause_log);
        applyPauseState();
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_clear_log) {
            clearLog();
            return true;
        }
        if (item.getItemId() == R.id.menu_pause_log) {
            paused = !paused;
            applyPauseState();
            if (!paused) {
                readLog(); // catch up at once
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Icon and text of the pause item follow the state: pause while updating, resume while paused. */
    private void applyPauseState() {
        if (pauseItem == null) {
            return;
        }
        pauseItem.setIcon(paused ? R.drawable.ic_baseline_play_arrow_24 : R.drawable.ic_baseline_pause_24);
        pauseItem.setTitle(paused ? R.string.log_resume : R.string.log_pause);
    }

    private SharedPreferences preferences() {
        return PreferenceManager.getDefaultSharedPreferences(this);
    }

    /** Hides everything up to now and asks the system to empty its buffer as well (it may refuse). */
    private void clearLog() {
        long now = System.currentTimeMillis();
        preferences().edit().putLong(CLEARED_AT_KEY, now).apply();
        shownLog = null;
        textView.setText("");
        try {
            reader.execute(() -> {
                try {
                    Process process = Runtime.getRuntime().exec(LogCommand.clearBuffer());
                    process.waitFor();
                    process.destroy();
                } catch (IOException e) {
                    // the marker alone hides the old lines
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        } catch (RejectedExecutionException e) {
            // closing; nothing to do
        }
    }

    /** Reads the log on the reader thread and shows it on the main thread if it changed. */
    private void readLog() {
        String level = preferences().getString(getString(R.string.settings_log_level_key), "E");
        long clearedAt = preferences().getLong(CLEARED_AT_KEY, 0L);
        try {
            reader.execute(() -> {
                String log;
                try {
                    log = readLogcat(LogCommand.build(level, clearedAt, TimeZone.getDefault()));
                } catch (IOException e) {
                    log = "Error while reading log: " + e.getMessage();
                }
                String result = log;
                handler.post(() -> show(result));
            });
        } catch (RejectedExecutionException e) {
            // closing; nothing to do
        }
    }

    private static String readLogcat(String[] command) throws IOException {
        Process process = Runtime.getRuntime().exec(command);
        try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            StringBuilder log = new StringBuilder();
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                log.append(line).append('\n');
            }
            return log.toString();
        } finally {
            process.destroy();
        }
    }

    private void show(String log) {
        if (isFinishing() || isDestroyed() || paused
                || !LogRefreshGate.mayChangeText(SystemClock.uptimeMillis(), lastInteractionMillis)) {
            return; // the next read catches up
        }
        // while the user has text selected nothing is touched: the next read catches up
        LogDelta delta = LogDelta.between(shownLog, log, textView.hasSelection());
        if (delta.kind() == LogDelta.Kind.NONE) {
            return;
        }
        View content = scrollView.getChildAt(0);
        boolean atBottom = content == null
                || content.getBottom() - (scrollView.getScrollY() + scrollView.getHeight()) <= FOLLOW_THRESHOLD_PX;
        boolean first = shownLog == null;
        final int scrollY = scrollView.getScrollY();
        final boolean follow = first || (atBottom && delta.kind() == LogDelta.Kind.APPEND);
        shownLog = log;
        if (delta.kind() == LogDelta.Kind.APPEND) {
            // new lines at the end keep the selection
            textView.append(delta.text());
        } else {
            textView.setText(delta.text());
        }
        // A focused selectable text view scrolls its (empty) cursor at the top into view whenever its text
        // changes: that was the jump to the top. After the text has been laid out, before it is drawn, the
        // position is put back (or the view follows the end).
        scrollView.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                scrollView.getViewTreeObserver().removeOnPreDrawListener(this);
                scrollingByUs = true;
                try {
                    if (follow) {
                        scrollView.fullScroll(View.FOCUS_DOWN);
                    } else {
                        scrollView.scrollTo(0, scrollY);
                    }
                } finally {
                    scrollingByUs = false;
                }
                return true;
            }
        });
    }
}
