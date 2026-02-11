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
import java.util.concurrent.atomic.AtomicInteger;

import de.yaacc.util.YaaccLogger;

/**
 * Base implementation of SimplePlayer using MediaController.
 * Handles connection to MediaSessionService.
 */
public abstract class BaseMediaPlayer implements SimplePlayer {
    private static final AtomicInteger ID_GENERATOR = new AtomicInteger(0);
    
    protected final Context context;
    protected final int id;
    protected String name;
    protected MediaController mediaController;
    protected ListenableFuture<MediaController> controllerFuture;
    protected List<PlayableItem> playlist = new ArrayList<>();

    public BaseMediaPlayer(Context context) {
        this.context = context;
        this.id = ID_GENERATOR.incrementAndGet();
        connectToMediaSession();
    }

    private void connectToMediaSession() {
        SessionToken sessionToken = new SessionToken(
            context,
            new ComponentName(context, YaaccMediaSessionService.class)
        );
        
        controllerFuture = new MediaController.Builder(context, sessionToken).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                mediaController = controllerFuture.get();
                onMediaControllerConnected();
            } catch (ExecutionException | InterruptedException e) {
                YaaccLogger.e(getClass().getName(), "Failed to connect to MediaSessionService", e);
            }
        }, context.getMainExecutor());
    }

    protected void onMediaControllerConnected() {
        YaaccLogger.d(getClass().getName(), "Connected to MediaSessionService");
    }

    @Override
    public void play() {
        if (mediaController != null) {
            mediaController.play();
        }
    }

    @Override
    public void pause() {
        if (mediaController != null) {
            mediaController.pause();
        }
    }

    @Override
    public void stop() {
        if (mediaController != null) {
            mediaController.stop();
        }
    }

    @Override
    public void next() {
        if (mediaController != null) {
            mediaController.seekToNext();
        }
    }

    @Override
    public void previous() {
        if (mediaController != null) {
            mediaController.seekToPrevious();
        }
    }

    @Override
    public void seekTo(long position) {
        if (mediaController != null) {
            mediaController.seekTo(position);
        }
    }

    @Override
    public void setPlaylist(List<PlayableItem> items) {
        this.playlist = new ArrayList<>(items);
        
        if (mediaController != null) {
            List<MediaItem> mediaItems = new ArrayList<>();
            for (PlayableItem item : items) {
                if (!isImage(item)) {
                    mediaItems.add(MediaItem.fromUri(item.getUri()));
                }
            }
            
            if (!mediaItems.isEmpty()) {
                mediaController.setMediaItems(mediaItems);
                mediaController.prepare();
            }
        }
    }

    @Override
    public void addToPlaylist(PlayableItem item) {
        playlist.add(item);
        if (mediaController != null && !isImage(item)) {
            mediaController.addMediaItem(MediaItem.fromUri(item.getUri()));
        }
    }

    @Override
    public void clearPlaylist() {
        playlist.clear();
        if (mediaController != null) {
            mediaController.clearMediaItems();
        }
    }

    @Override
    public boolean isPlaying() {
        return mediaController != null && mediaController.isPlaying();
    }

    @Override
    public long getCurrentPosition() {
        return mediaController != null ? mediaController.getCurrentPosition() : 0;
    }

    @Override
    public long getDuration() {
        return mediaController != null ? mediaController.getDuration() : 0;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setName(String name) {
        this.name = name;
    }

    @Override
    public int getId() {
        return id;
    }

    @Override
    public void release() {
        if (mediaController != null) {
            MediaController.releaseFuture(controllerFuture);
            mediaController = null;
        }
    }

    protected boolean isImage(PlayableItem item) {
        String mimeType = item.getMimeType();
        return mimeType != null && mimeType.startsWith("image/");
    }
}
