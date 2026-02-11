package de.yaacc.player;

import java.util.List;

/**
 * Simplified player interface for modern architecture.
 * No service management - that's handled by MediaSessionService.
 */
public interface SimplePlayer {
    
    // Playback control
    void play();
    void pause();
    void stop();
    void next();
    void previous();
    void seekTo(long position);
    
    // Playlist management
    void setPlaylist(List<PlayableItem> items);
    void addToPlaylist(PlayableItem item);
    void clearPlaylist();
    
    // State queries
    boolean isPlaying();
    long getCurrentPosition();
    long getDuration();
    
    // Player info
    String getName();
    void setName(String name);
    int getId();
    
    // Lifecycle
    void release();
}
