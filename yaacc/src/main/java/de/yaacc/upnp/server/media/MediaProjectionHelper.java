/*
 * Copyright (C) 2026 www.yaacc.de
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

import androidx.annotation.RequiresApi;

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
    
    private static MediaProjection mediaProjection;
    private static MediaProjectionManager mediaProjectionManager;
    private static int storedResultCode = Activity.RESULT_CANCELED;
    private static Intent storedResultData = null;
    
    // Callback for when MediaProjection stops
    public interface MediaProjectionStopCallback {
        void onMediaProjectionStopped();
    }
    
    private static MediaProjectionStopCallback stopCallback;

    /**
     * Set callback for when MediaProjection stops.
     */
    public static void setStopCallback(MediaProjectionStopCallback callback) {
        stopCallback = callback;
    }

    /**
     * Create intent to request MediaProjection permission.
     */
    public static Intent createPermissionIntent(Context context) {
        if (mediaProjectionManager == null) {
            mediaProjectionManager = (MediaProjectionManager) 
                context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        }
        return mediaProjectionManager.createScreenCaptureIntent();
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
        if (mediaProjectionManager == null) {
            mediaProjectionManager = (MediaProjectionManager) 
                context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        }

        stopMediaProjection();
        
        try {
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data);
            
            if (mediaProjection != null) {
                mediaProjection.registerCallback(new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        YaaccLogger.i(MediaProjectionHelper.class.getName(), 
                            "MediaProjection stopped");
                        mediaProjection = null;
                        storedResultCode = Activity.RESULT_CANCELED;
                        storedResultData = null;
                        
                        // Notify callback
                        if (stopCallback != null) {
                            stopCallback.onMediaProjectionStopped();
                        }
                    }
                }, null);
                
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
