package de.yaacc.player;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;

import org.fourthline.cling.support.model.DIDLObject;

import java.net.URI;
import java.util.concurrent.ExecutionException;

import de.yaacc.R;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.YaaccLogger;

/**
 * Local music player using MediaSessionService and ExoPlayer.
 * Replaces LocalBackgroundMusicPlayer with modern architecture.
 */
public class LocalMediaSessionPlayer extends AbstractPlayer {
    private MediaController mediaController;
    private ListenableFuture<MediaController> controllerFuture;
    private URI albumArtUri;
    private PlayableItem pendingItem; // Queue item if controller not ready

    public LocalMediaSessionPlayer(UpnpClient upnpClient, String name, String shortName) {
        this(upnpClient);
        setName(name);
        setShortName(shortName);
    }

    public LocalMediaSessionPlayer(UpnpClient upnpClient) {
        super(upnpClient);
        connectToMediaSession();
    }

    @Override
    public int getIconResourceId() {
        return R.drawable.ic_baseline_library_music_32;
    }

    private void connectToMediaSession() {
        Context context = getContext();
        
        YaaccLogger.d(getClass().getName(), "Connecting to MediaSessionService");
        
        SessionToken sessionToken = new SessionToken(
            context,
            new ComponentName(context, YaaccMediaSessionService.class)
        );
        
        controllerFuture = new MediaController.Builder(context, sessionToken).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                mediaController = controllerFuture.get();
                YaaccLogger.d(getClass().getName(), "Connected to MediaSessionService");
                
                // If there's a pending item, play it now
                if (pendingItem != null) {
                    YaaccLogger.d(getClass().getName(), "Playing pending item: " + pendingItem.getTitle());
                    PlayableItem item = pendingItem;
                    pendingItem = null;
                    startItemNow(item);
                }
            } catch (ExecutionException | InterruptedException e) {
                YaaccLogger.e(getClass().getName(), "Failed to connect to MediaSessionService", e);
            }
        }, context.getMainExecutor());
    }

    @Override
    protected void startItem(PlayableItem playableItem, Object loadedItem) {
        YaaccLogger.d(getClass().getName(), "startItem called for: " + playableItem.getTitle());
        
        if (mediaController == null) {
            YaaccLogger.w(getClass().getName(), "MediaController not ready, queuing item");
            pendingItem = playableItem;
            return;
        }

        startItemNow(playableItem);
    }

    private void startItemNow(PlayableItem playableItem) {
        DIDLObject.Property<URI> albumArtUriProperty = playableItem.getItem() == null ? null : 
            playableItem.getItem().getFirstProperty(DIDLObject.Property.UPNP.ALBUM_ART_URI.class);
        albumArtUri = (albumArtUriProperty == null) ? null : albumArtUriProperty.getValue();

        // MediaController must be called from main thread
        new Handler(Looper.getMainLooper()).post(() -> {
            if (mediaController != null) {
                MediaItem.Builder builder = new MediaItem.Builder()
                    .setUri(playableItem.getUri());
                
                // Add metadata with album art
                if (albumArtUri != null) {
                    MediaMetadata metadata = new MediaMetadata.Builder()
                        .setTitle(playableItem.getTitle())
                        .setArtworkUri(Uri.parse(albumArtUri.toString()))
                        .build();
                    builder.setMediaMetadata(metadata);
                }
                
                mediaController.setMediaItem(builder.build());
                mediaController.prepare();
                mediaController.play();
                YaaccLogger.d(getClass().getName(), "Started playing: " + playableItem.getTitle());
            }
        });
    }

    @Override
    protected Object loadItem(PlayableItem playableItem) {
        YaaccLogger.d(getClass().getName(), "loadItem called for: " + playableItem.getTitle());
        // No loading needed for ExoPlayer - it handles streaming
        return playableItem; // Return non-null to indicate ready
    }

    @Override
    protected int getNotificationId() {
        return de.yaacc.util.NotificationId.LOCAL_BACKGROUND_MUSIC_PLAYER.getId();
    }

    @Override
    public android.app.PendingIntent getNotificationIntent() {
        android.content.Intent intent = new android.content.Intent(getContext(), MusicPlayerActivity.class);
        intent.putExtra(PLAYER_ID, getId());
        return android.app.PendingIntent.getActivity(getContext(), 0, intent, 
            android.app.PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    protected void stopItem(PlayableItem playableItem) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (mediaController != null) {
                mediaController.stop();
            }
        });
    }

    @Override
    protected void doPause() {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (mediaController != null) {
                mediaController.pause();
            }
        });
    }

    @Override
    public void seekTo(long position) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (mediaController != null) {
                mediaController.seekTo(position);
            }
        });
    }

    @Override
    public long getCurrentPosition() {
        return mediaController != null ? mediaController.getCurrentPosition() : 0;
    }

    @Override
    public String getElapsedTime() {
        long elapsed = getCurrentPosition() / 1000;
        return String.format("%02d:%02d", elapsed / 60, elapsed % 60);
    }

    @Override
    public String getDuration() {
        if (mediaController == null) return "00:00";
        long durationMs = mediaController.getDuration();
        if (durationMs < 0) return "00:00"; // C.TIME_UNSET or unknown
        long duration = durationMs / 1000;
        return String.format("%02d:%02d", duration / 60, duration % 60);
    }

    @Override
    public URI getAlbumArt() {
        return albumArtUri;
    }

    @Override
    public void onDestroy() {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (mediaController != null) {
                mediaController.stop();
                MediaController.releaseFuture(controllerFuture);
                mediaController = null;
            }
        });
        super.onDestroy();
    }
}
