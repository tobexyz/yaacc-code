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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package de.yaacc.imageviewer;

import android.app.Application;
import de.yaacc.util.YaaccLogger;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.List;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

import de.yaacc.player.PlayMode;
import de.yaacc.player.PlayModeSettings;
import de.yaacc.player.PlayOrder;

/**
 * ViewModel for ImageViewerActivity.
 */
public class ImageViewerViewModel extends AndroidViewModel {
    private final MutableLiveData<ImageViewerState> _state = new MutableLiveData<>(new ImageViewerState());
    public LiveData<ImageViewerState> getState() { return _state; }

    private Timer slideshowTimer;

    // Repeat and shuffle of the slideshow: the mode of the LocalImagePlayer when there is one (the activity
    // hands it over), otherwise the one the settings give; the order is the one the slideshow walks along
    private final MutableLiveData<PlayMode> _playMode = new MutableLiveData<>();
    private volatile PlayMode playMode;
    private final PlayOrder playOrder = new PlayOrder(new Random());

    public ImageViewerViewModel(@NonNull Application application) {
        super(application);
    }

    public LiveData<PlayMode> getPlayModeLive() { return _playMode; }

    public PlayMode getPlayMode() {
        PlayMode mode = playMode;
        if (mode == null) {
            mode = PlayModeSettings.defaultPlayMode(getApplication());
            playMode = mode;
        }
        return mode;
    }

    /** May be called from any thread. */
    public void setPlayMode(PlayMode mode) {
        if (mode == null || mode.equals(getPlayMode())) {
            return;
        }
        playMode = mode;
        ImageViewerState current = _state.getValue();
        int total = current == null ? 0 : current.getTotalImages();
        int index = current == null ? 0 : current.getCurrentIndex();
        playOrder.fit(total, index);
        playOrder.setShuffle(mode.isShuffle(), index);
        _playMode.postValue(mode);
    }

    public void setImageUris(List<Uri> uris) {
        ImageViewerState current = _state.getValue();
        if (current != null) {
            current.setImageUris(uris);
            current.setCurrentIndex(0);
            current.setPlaying(false);
            playOrder.reset(current.getTotalImages(), 0, getPlayMode().isShuffle());
            _state.setValue(current);
        }
    }

    public void setCurrentIndex(int currentIndex) {
        ImageViewerState current = _state.getValue();
        if (current != null) {
            current.setCurrentIndex(currentIndex);
            _state.setValue(current);
        }
    }

    /** Somebody skips to the next image (swipe, button, remote control). */
    public void next() {
        move(false);
    }

    /** The slideshow shows the next image after its time: repeat one stays, the end may stop it. */
    private void onSlideEnded() {
        move(true);
    }

    private void move(boolean slideEnded) {
        ImageViewerState current = _state.getValue();
        if (current == null) return;

        playOrder.fit(current.getTotalImages(), current.getCurrentIndex());
        int target = playOrder.next(current.getCurrentIndex(), getPlayMode().getRepeat(), slideEnded);
        if (target == PlayOrder.NONE) {
            if (slideEnded) {
                // The list is shown once (repeat off): the slideshow ends on the last image
                cancelSlideshowTimer();
                current.setPlaying(false);
                _state.postValue(current);
            }
            return;
        }
        if (target == current.getCurrentIndex()) {
            return; // repeat one: the image stays
        }
        current.setCurrentIndex(target);
        _state.postValue(current); // Use postValue for background thread safety
    }

    public void previous() {
        ImageViewerState current = _state.getValue();
        if (current == null) return;

        playOrder.fit(current.getTotalImages(), current.getCurrentIndex());
        int target = playOrder.previous(current.getCurrentIndex());
        current.setCurrentIndex(target == PlayOrder.NONE ? 0 : target);
        _state.postValue(current); // Use postValue for background thread safety
    }

    public void play() {
        ImageViewerState current = _state.getValue();
        if (current == null || current.getTotalImages() == 0) return;

        current.setPlaying(true);
        _state.setValue(current);
        startSlideshowTimer();
    }

    public void pause() {
        cancelSlideshowTimer();
        ImageViewerState current = _state.getValue();
        if (current != null) {
            current.setPlaying(false);
            _state.setValue(current);
        }
    }

    public void stop() {
        cancelSlideshowTimer();
        ImageViewerState current = _state.getValue();
        if (current != null) {
            current.setPlaying(false);
            // Back to the beginning of the order: image 0, or a random one with shuffle
            boolean shuffle = getPlayMode().isShuffle();
            playOrder.reset(current.getTotalImages(), PlayOrder.NONE, shuffle);
            current.setCurrentIndex(shuffle && current.getTotalImages() > 0 ? playOrder.first() : 0);
            _state.setValue(current);
        }
    }

    public void toggleControls() {
        ImageViewerState current = _state.getValue();
        if (current != null) {
            current.setControlsVisible(!current.isControlsVisible());
            _state.setValue(current);
        }
    }

    public void showControls() {
        ImageViewerState current = _state.getValue();
        if (current != null) {
            current.setControlsVisible(true);
            _state.setValue(current);
        }
    }

    public void hideControls() {
        ImageViewerState current = _state.getValue();
        if (current != null) {
            current.setControlsVisible(false);
            _state.setValue(current);
        }
    }

    public void setDurationMs(long durationMs) {
        ImageViewerState current = _state.getValue();
        if (current != null) {
            current.setDurationMs(durationMs);
            _state.setValue(current);
        }
    }

    private void startSlideshowTimer() {
        cancelSlideshowTimer();
        ImageViewerState current = _state.getValue();
        if (current == null) return;

        slideshowTimer = new Timer();
        slideshowTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (_state.getValue() != null && _state.getValue().isPlaying()) {
                    // Post to main thread
                    _state.postValue(_state.getValue());
                    onSlideEnded();
                }
            }
        }, current.getDurationMs(), current.getDurationMs());
    }

    private void cancelSlideshowTimer() {
        if (slideshowTimer != null) {
            slideshowTimer.cancel();
            slideshowTimer = null;
        }
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        cancelSlideshowTimer();
    }
}
