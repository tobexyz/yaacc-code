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

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.preference.PreferenceManager;

import de.yaacc.R;
import de.yaacc.upnp.server.media.MediaProjectionHelper;
import de.yaacc.util.YaaccLogger;

/**
 * The screen/audio streaming permission flow of the yaacc server row: asks for the MediaProjection
 * permission, applies the answer, and resets the streaming state when the projection stops.
 * Shared by every fragment that hosts the {@link BrowseDeviceAdapter}.
 * <p>
 * Usage in the fragment: call {@link #createRequestIntent()} from the adapter's permission
 * callback and start it with {@code startActivityForResult(intent,
 * MediaProjectionHelper.REQUEST_CODE_MEDIA_PROJECTION)}; pass the result of
 * {@code onActivityResult} to {@link #onPermissionResult(int, int, Intent)}; call
 * {@link #registerStopCallback()} in {@code onStart} and {@link #unregisterStopCallback()} in
 * {@code onStop}.
 * <p>
 * Security: the permission result {@code Intent} is a capability to capture the screen. It is
 * handed straight to {@link MediaProjectionHelper}, which keeps it in its static fields only. It
 * is never put in a Bundle, preferences or another Intent, and its extras are never logged. A
 * result is accepted only after this helper issued a request and only for that request code.
 * Only the application context is kept. Main thread only.
 */
public final class StreamingPermissionHelper {

    private static final String TAG = StreamingPermissionHelper.class.getName();

    /** True from {@link #createRequestIntent()} until its answer arrives. Shared by all instances
     * because a rotation can deliver the answer to a new fragment (and a new helper). */
    private static boolean requestPending = false;

    /**
     * Resets the streaming state whenever the projection stops, even while no screen is started,
     * so the preferences and the toggle flags never claim that streaming is on. Installed once.
     */
    private static MediaProjectionHelper.MediaProjectionStopCallback stateReset;

    private final Context appContext;
    private final Runnable refreshAdapter;
    private final MediaProjectionHelper.MediaProjectionStopCallback uiCallback;

    /**
     * @param context        any context; only the application context is kept
     * @param refreshAdapter refreshes the server row after the streaming state changed; called on
     *                       the main thread, between register and unregister of the stop callback
     *                       and after a permission result
     */
    public StreamingPermissionHelper(Context context, Runnable refreshAdapter) {
        this.appContext = context.getApplicationContext();
        this.refreshAdapter = refreshAdapter;
        this.uiCallback = this::refresh;
        installStateReset(appContext);
    }

    /**
     * @return the intent that asks the user for the screen capture permission, to be started with
     * {@code startActivityForResult} and {@link MediaProjectionHelper#REQUEST_CODE_MEDIA_PROJECTION}.
     * Returns null before Android 10.
     */
    public Intent createRequestIntent() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null;
        }
        requestPending = true;
        return MediaProjectionHelper.createPermissionIntent(appContext);
    }

    /**
     * Applies the answer to the request issued by {@link #createRequestIntent()}. Results for
     * another request code, or without a pending request, are ignored.
     */
    public void onPermissionResult(int requestCode, int resultCode, Intent data) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || requestCode != MediaProjectionHelper.REQUEST_CODE_MEDIA_PROJECTION) {
            return;
        }
        if (!requestPending) {
            YaaccLogger.w(TAG, "Ignoring MediaProjection result: no request was issued");
            return;
        }
        requestPending = false;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(appContext);
        if (MediaProjectionHelper.handlePermissionResult(appContext, resultCode, data)) {
            // Permission granted - enable only the button that requested it
            YaaccLogger.i(TAG, "MediaProjection permission granted");
            SharedPreferences.Editor editor = prefs.edit();
            if (BrowseDeviceAdapter.isPendingAudioRequest()) {
                editor.putBoolean(appContext.getString(R.string.settings_local_server_serve_system_audio_chkbx), true);
                BrowseDeviceAdapter.setAudioStreaming(true);
            }
            if (BrowseDeviceAdapter.isPendingVideoRequest()) {
                editor.putBoolean(appContext.getString(R.string.settings_local_server_serve_screen_cast_chkbx), true);
                BrowseDeviceAdapter.setVideoStreaming(true);
            }
            editor.apply();
            BrowseDeviceAdapter.clearPendingRequests();
        } else {
            // Permission denied - disable both and clear pending
            YaaccLogger.w(TAG, "MediaProjection permission denied");
            BrowseDeviceAdapter.clearPendingRequests();
            resetStreamingState(appContext);
        }
        refresh();
    }

    /**
     * Refreshes the server row when the projection stops. Call from {@code onStart}.
     */
    public void registerStopCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaProjectionHelper.addStopCallback(uiCallback);
        }
    }

    /**
     * Call from {@code onStop}. Removes exactly the callback this helper added.
     */
    public void unregisterStopCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaProjectionHelper.removeStopCallback(uiCallback);
        }
    }

    private void refresh() {
        if (refreshAdapter != null) {
            refreshAdapter.run();
        }
    }

    private static synchronized void installStateReset(Context appContext) {
        if (stateReset != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }
        // Added before any per-screen callback of this class, so the state is already reset when
        // the screens refresh. Captures the application context only.
        stateReset = () -> {
            resetStreamingState(appContext);
            YaaccLogger.i(TAG, "Streaming disabled - MediaProjection stopped");
        };
        MediaProjectionHelper.addStopCallback(stateReset);
    }

    private static void resetStreamingState(Context appContext) {
        PreferenceManager.getDefaultSharedPreferences(appContext).edit()
                .putBoolean(appContext.getString(R.string.settings_local_server_serve_system_audio_chkbx), false)
                .putBoolean(appContext.getString(R.string.settings_local_server_serve_screen_cast_chkbx), false)
                .apply();
        BrowseDeviceAdapter.setAudioStreaming(false);
        BrowseDeviceAdapter.setVideoStreaming(false);
    }
}
