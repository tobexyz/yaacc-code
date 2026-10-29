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
package de.yaacc.upnp.server.media;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.RequiresApi;

import java.util.concurrent.CopyOnWriteArrayList;

import de.yaacc.util.YaaccLogger;

/**
 * Manages MediaProjection for capturing system audio and screen.
 * Requires Android 10+ (API 29).
 *
 * @author Tobias Schoene (tobexyz)
 */
@RequiresApi(api = Build.VERSION_CODES.Q)
public class MediaProjectionHelper {

    public static final int REQUEST_CODE_MEDIA_PROJECTION = 2001;

    private static volatile MediaProjection mediaProjection;
    private static volatile MediaProjectionManager mediaProjectionManager;
    private static volatile int storedResultCode = Activity.RESULT_CANCELED;
    private static volatile Intent storedResultData = null;

    // Callback for when MediaProjection stops
    public interface MediaProjectionStopCallback {
        void onMediaProjectionStopped();
    }

    // Several owners may listen at once (a process-wide state reset and one UI per started
    // screen). Each owner removes exactly the callback it added, so a later owner never
    // replaces an earlier one and a stopped screen is not kept alive.
    private static final CopyOnWriteArrayList<MediaProjectionStopCallback> stopCallbacks =
            new CopyOnWriteArrayList<>();

    /**
     * Add a callback for when MediaProjection stops. It is called on the main thread. Adding the
     * same callback twice has no effect. The callback must not capture a Context or View that
     * outlives its owner; remove it with {@link #removeStopCallback(MediaProjectionStopCallback)}.
     */
    public static void addStopCallback(MediaProjectionStopCallback callback) {
        if (callback != null) {
            stopCallbacks.addIfAbsent(callback);
        }
    }

    /**
     * Remove a callback added with {@link #addStopCallback(MediaProjectionStopCallback)}.
     */
    public static void removeStopCallback(MediaProjectionStopCallback callback) {
        stopCallbacks.remove(callback);
    }

    private static MediaProjectionManager manager(Context context) {
        if (mediaProjectionManager == null) {
            // application context: the manager is kept in a static field
            mediaProjectionManager = (MediaProjectionManager)
                    context.getApplicationContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        }
        return mediaProjectionManager;
    }

    /**
     * Create intent to request MediaProjection permission.
     */
    public static Intent createPermissionIntent(Context context) {
        return manager(context).createScreenCaptureIntent();
    }

    /**
     * Handle permission result from activity.
     * Note: MediaProjection must be created from a foreground service.
     */
    public static boolean handlePermissionResult(Context context, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            YaaccLogger.w(MediaProjectionHelper.class.getName(),
                    "MediaProjection permission denied");
            storedResultCode = Activity.RESULT_CANCELED;
            storedResultData = null;
            return false;
        }

        // Store the result for later use by the service
        storedResultCode = resultCode;
        storedResultData = data;

        YaaccLogger.i(MediaProjectionHelper.class.getName(),
                "MediaProjection permission granted, stored for service");
        return true;
    }

    /**
     * Create MediaProjection from a foreground service using stored permission.
     * Must be called from a service with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION.
     */
    public static boolean createMediaProjectionFromStored(Context context) {
        if (storedResultCode == Activity.RESULT_CANCELED || storedResultData == null) {
            YaaccLogger.w(MediaProjectionHelper.class.getName(),
                    "No stored MediaProjection permission");
            return false;
        }
        return createMediaProjection(context, storedResultCode, storedResultData);
    }

    /**
     * Create MediaProjection from a foreground service.
     * Must be called from a service with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION.
     */
    public static boolean createMediaProjection(Context context, int resultCode, Intent data) {
        MediaProjectionManager projectionManager = manager(context);

        stopMediaProjection();

        try {
            mediaProjection = projectionManager.getMediaProjection(resultCode, data);

            if (mediaProjection != null) {
                mediaProjection.registerCallback(new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        YaaccLogger.i(MediaProjectionHelper.class.getName(),
                                "MediaProjection stopped");
                        mediaProjection = null;
                        storedResultCode = Activity.RESULT_CANCELED;
                        storedResultData = null;

                        // Notify callbacks (on the main thread, see the handler below)
                        for (MediaProjectionStopCallback callback : stopCallbacks) {
                            try {
                                callback.onMediaProjectionStopped();
                            } catch (RuntimeException e) {
                                YaaccLogger.w(MediaProjectionHelper.class.getName(),
                                        "MediaProjection stop callback failed", e);
                            }
                        }
                    }
                }, new Handler(Looper.getMainLooper()));

                YaaccLogger.i(MediaProjectionHelper.class.getName(),
                        "MediaProjection created successfully");
                return true;
            }
        } catch (Exception e) {
            YaaccLogger.e(MediaProjectionHelper.class.getName(),
                    "Failed to create MediaProjection", e);
        }

        return false;
    }

    /**
     * Get current MediaProjection instance.
     */
    public static MediaProjection getMediaProjection() {
        return mediaProjection;
    }

    /**
     * Check if MediaProjection is active.
     */
    public static boolean isActive() {
        return mediaProjection != null;
    }

    /**
     * Check if we have stored permission (either active or pending).
     */
    public static boolean hasPermission() {
        return isActive() || (storedResultCode == Activity.RESULT_OK && storedResultData != null);
    }

    /**
     * Clear stored MediaProjection permission.
     */
    public static void clearPermission() {
        stopMediaProjection();
        storedResultCode = Activity.RESULT_CANCELED;
        storedResultData = null;
        YaaccLogger.i(MediaProjectionHelper.class.getName(), "MediaProjection permission cleared");
    }

    /**
     * Stop MediaProjection and release resources.
     */
    public static void stopMediaProjection() {
        if (mediaProjection != null) {
            try {
                mediaProjection.stop();
            } catch (Exception e) {
                YaaccLogger.e(MediaProjectionHelper.class.getName(),
                        "Error stopping MediaProjection", e);
            }
            mediaProjection = null;
        }
    }
}
