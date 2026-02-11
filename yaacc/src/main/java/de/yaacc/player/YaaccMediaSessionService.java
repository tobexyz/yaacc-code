package de.yaacc.player;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import de.yaacc.util.YaaccLogger;

public class YaaccMediaSessionService extends MediaSessionService {
    private MediaSession mediaSession;
    private ExoPlayer player;
    private YaaccExoPlayer yaaccExoPlayer;

    @Override
    public void onCreate() {
        super.onCreate();
        YaaccLogger.d(getClass().getName(), "Service created");
        
        // Initialize ExoPlayer
        player = new ExoPlayer.Builder(this).build();
        
        // Create MediaSession
        mediaSession = new MediaSession.Builder(this, player)
                .setSessionActivity(createSessionActivity())
                .setCallback(new MediaSessionCallback())
                .build();
        
        // Create wrapper
        yaaccExoPlayer = new YaaccExoPlayer(this, player, mediaSession);
    }

    @Override
    public void onDestroy() {
        YaaccLogger.d(getClass().getName(), "Service destroyed");
        yaaccExoPlayer.release();
        mediaSession.release();
        player.release();
        super.onDestroy();
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    private PendingIntent createSessionActivity() {
        Intent intent = new Intent(this, de.yaacc.browser.TabBrowserActivity.class);
        return PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
    }

    private class MediaSessionCallback implements MediaSession.Callback {
        @Override
        public MediaSession.ConnectionResult onConnect(
                MediaSession session,
                MediaSession.ControllerInfo controller) {
            YaaccLogger.d(getClass().getName(), "Controller connected: " + controller.getPackageName());
            return MediaSession.Callback.super.onConnect(session, controller);
        }

        @Override
        public void onDisconnected(MediaSession session, MediaSession.ControllerInfo controller) {
            YaaccLogger.d(getClass().getName(), "Controller disconnected: " + controller.getPackageName());
            MediaSession.Callback.super.onDisconnected(session, controller);
        }
    }
}
