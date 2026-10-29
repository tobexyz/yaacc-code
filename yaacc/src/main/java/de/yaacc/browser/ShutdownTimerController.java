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

import static com.google.android.material.timepicker.MaterialTimePicker.INPUT_MODE_KEYBOARD;

import android.content.SharedPreferences;

import androidx.fragment.app.FragmentManager;

import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

import de.yaacc.Yaacc;
import de.yaacc.util.ShutdownTimerListener;
import de.yaacc.util.YaaccLogger;

/**
 * Drives the shutdown timer UI (switch, remaining time, duration picker) for whoever shows it.
 * The timer itself lives in {@link Yaacc}; this class keeps the configured duration in the
 * preferences and tells a {@link Display} what to show.
 * <p>
 * The owner calls {@link #attach(Display)} when its UI starts and {@link #detach()} when it
 * stops, so the application never keeps a stopped UI as a listener. Main thread only. The
 * controller holds the application, the preferences and the display between attach and detach,
 * never a Context or View of its own.
 */
public final class ShutdownTimerController implements ShutdownTimerListener {

    private static final String PICKER_TAG = "CountdownTimer";

    /** Shows the timer state. Called on the main thread. */
    public interface Display {
        /**
         * @param millis  time left while running, else the configured duration
         * @param running whether a timer is counting down
         */
        void showRemaining(long millis, boolean running);
    }

    private final Yaacc app;
    private final SharedPreferences preferences;
    private final String preferenceKey;
    private Display display;

    /**
     * @param app           the application, owner of the timer
     * @param preferences   where the configured duration (millis, a long) is stored
     * @param preferenceKey the key of the configured duration
     */
    public ShutdownTimerController(Yaacc app, SharedPreferences preferences, String preferenceKey) {
        this.app = app;
        this.preferences = preferences;
        this.preferenceKey = preferenceKey;
    }

    /** Starts listening for ticks and shows the current state right away. */
    public void attach(Display display) {
        this.display = display;
        app.addShutdownTimerListener(this);
        publishState();
    }

    /** Stops listening and drops the display. Safe to call without a previous attach. */
    public void detach() {
        app.removeShutdownTimerListener(this);
        display = null;
    }

    public boolean isRunning() {
        return app.isShutdownTimerRunning();
    }

    /** @return the configured duration in milliseconds, 0 if none was set */
    public long configuredMillis() {
        return preferences.getLong(preferenceKey, 0L);
    }

    /**
     * Stores the configured duration. A running timer is restarted with it.
     */
    public void setConfiguredMillis(long millis) {
        preferences.edit().putLong(preferenceKey, millis).apply();
        if (app.isShutdownTimerRunning()) {
            app.startShutdownTimer(millis);
        }
        publishState();
    }

    /** Starts the timer with the configured duration. */
    public void start() {
        app.startShutdownTimer(configuredMillis());
        publishState();
    }

    /** Stops the timer. */
    public void stop() {
        app.stopShutdownTimer();
        publishState();
    }

    /**
     * Shows a time picker (hours and minutes) for the configured duration. The new duration is
     * applied when the user confirms.
     *
     * @param fragmentManager where to show the picker
     * @param title           title of the picker
     */
    public void showPicker(FragmentManager fragmentManager, String title) {
        showPicker(fragmentManager, title, false);
    }

    /**
     * @param startOnConfirm start the timer with the chosen time when the user confirms (a
     *                       duration of 0 only stores it and never starts a timer)
     */
    public void showPicker(FragmentManager fragmentManager, String title, boolean startOnConfirm) {
        long totalMinutes = configuredMillis() / 60_000L;
        int hours = (int) Math.min(23L, totalMinutes / 60L);
        int minutes = (int) (totalMinutes % 60L);
        MaterialTimePicker picker = new MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(hours)
                .setMinute(minutes)
                .setTitleText(title)
                .setInputMode(INPUT_MODE_KEYBOARD).build();
        picker.addOnPositiveButtonClickListener(dialog -> {
            long millis = (picker.getHour() * 3600L + picker.getMinute() * 60L) * 1000L;
            YaaccLogger.d(ShutdownTimerController.class.getName(), "time set: " + picker.getHour() + ":" + picker.getMinute() + " millis: " + millis);
            setConfiguredMillis(millis);
            if (startOnConfirm && millis > 0L) {
                start();
            }
        });
        picker.show(fragmentManager, PICKER_TAG);
    }

    @Override
    public void onTick(long millisUntilFinished) {
        Display current = display;
        if (current != null) {
            current.showRemaining(millisUntilFinished, true);
        }
    }

    private void publishState() {
        Display current = display;
        if (current == null) {
            return;
        }
        boolean running = app.isShutdownTimerRunning();
        current.showRemaining(running ? app.getShutdownTimerRemainingMillis() : configuredMillis(), running);
    }
}
