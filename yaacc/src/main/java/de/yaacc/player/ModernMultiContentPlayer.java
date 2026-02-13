package de.yaacc.player;

import android.content.ComponentName;
import android.content.Context;

import androidx.media3.common.MediaItem;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.YaaccLogger;

/**
 * Coordinator for mixed content playlists (audio, video, images).
 * Delegates to MediaSessionService for audio/video and LocalImagePlayer for images.
 */
public class ModernMultiContentPlayer extends AbstractPlayer {
    private MediaController mediaController;
    private ListenableFuture<MediaController> controllerFuture;
    private LocalImagePlayer imagePlayer;
    private List<PlayableItem> playlist = new ArrayList<>();
    private int currentIndex = 0;

    public ModernMultiContentPlayer(UpnpClient upnpClient, String name, String shortName) {
        this(upnpClient);
        setName(name);
        setShortName(shortName);
    }

    public ModernMultiContentPlayer(UpnpClient upnpClient) {
        super(upnpClient);
        connectToMediaSession();
        imagePlayer = new LocalImagePlayer(upnpClient);
    }

    private void connectToMediaSession() {
        Context context = getContext();
        SessionToken sessionToken = new SessionToken(
            context,
            new ComponentName(context, PlayerService.class)
        );
        
        controllerFuture = new MediaController.Builder(context, sessionToken).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                mediaController = controllerFuture.get();
                YaaccLogger.d(getClass().getName(), "Connected to PlayerService MediaSession");
            } catch (ExecutionException | InterruptedException e) {
                YaaccLogger.e(getClass().getName(), "Failed to connect", e);
            }
        }, context.getMainExecutor());
    }

    public void setPlaylistAndPlay(List<PlayableItem> playlist) {
        this.playlist = new ArrayList<>(playlist);
        this.currentIndex = 0;
        YaaccLogger.d(getClass().getName(), "Playlist set with " + playlist.size() + " items");
    }

    @Override
    protected void startItem(PlayableItem playableItem, Object loadedItem) {
        if (isImage(playableItem)) {
            // Stop audio/video if playing
            if (mediaController != null && mediaController.isPlaying()) {
                mediaController.pause();
            }
            // Show image - use standard play method
            imagePlayer.play();
        } else {
            // Stop image slideshow if active
            if (imagePlayer.isPlaying()) {
                imagePlayer.stop();
            }
            // Play audio/video
            if (mediaController != null) {
                MediaItem mediaItem = MediaItem.fromUri(playableItem.getUri());
                mediaController.setMediaItem(mediaItem);
                mediaController.prepare();
                mediaController.play();
            }
        }
    }

    @Override
    protected void stopItem(PlayableItem playableItem) {
        if (isImage(playableItem)) {
            imagePlayer.stop();
        } else if (mediaController != null) {
            mediaController.stop();
        }
    }

    @Override
    protected Object loadItem(PlayableItem playableItem) {
        return null; // No loading needed
    }

    @Override
    public void play() {
        if (currentIndex < playlist.size()) {
            PlayableItem item = playlist.get(currentIndex);
            startItem(item, null);
        }
    }

    @Override
    public void pause() {
        if (mediaController != null) {
            mediaController.pause();
        }
        // Images don't pause
    }

    @Override
    public void stop() {
        if (mediaController != null) {
            mediaController.stop();
        }
        imagePlayer.stop();
    }

    @Override
    public void next() {
        currentIndex++;
        if (currentIndex >= playlist.size()) {
            currentIndex = 0;
        }
        play();
    }

    @Override
    public void previous() {
        currentIndex--;
        if (currentIndex < 0) {
            currentIndex = playlist.size() - 1;
        }
        play();
    }

    @Override
    public void seekTo(long position) {
        if (mediaController != null) {
            mediaController.seekTo(position);
        }
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
        long duration = mediaController.getDuration() / 1000;
        return String.format("%02d:%02d", duration / 60, duration % 60);
    }

    private boolean isImage(PlayableItem item) {
        String mimeType = item.getMimeType();
        return mimeType != null && mimeType.startsWith("image/");
    }

    @Override
    public void onDestroy() {
        if (mediaController != null) {
            MediaController.releaseFuture(controllerFuture);
        }
        imagePlayer.exit();
        super.onDestroy();
    }

    @Override
    protected int getNotificationId() {
        return de.yaacc.util.NotificationId.MULTI_CONTENT_PLAYER.getId();
    }

    @Override
    public android.app.PendingIntent getNotificationIntent() {
        android.content.Intent intent = new android.content.Intent(getContext(), 
            de.yaacc.player.MultiContentPlayerActivity.class);
        intent.putExtra(PLAYER_ID, getId());
        return android.app.PendingIntent.getActivity(getContext(), 0, intent, 
            android.app.PendingIntent.FLAG_IMMUTABLE);
    }
}
