package de.yaacc.util;

import android.annotation.SuppressLint;

import java.time.Duration;

public class FormatHelper {
    @SuppressLint("DefaultLocale")
    public static String parseMillisToTimeStringTo(long millis) {
        Duration duration = Duration.ofMillis(millis);
        long durationSeconds = duration.getSeconds();
        long hours = durationSeconds / 3600;
        long minutes = (durationSeconds % 3600) / 60;
        long seconds = durationSeconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }
}
