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
import java.util.Timer;
import java.util.TimerTask;

/**
 * ViewModel for ImageViewerActivity.
 */
public class ImageViewerViewModel extends AndroidViewModel {
    private final MutableLiveData<ImageViewerState> _state = new MutableLiveData<>(new ImageViewerState());
    public LiveData<ImageViewerState> getState() { return _state; }

    private Timer slideshowTimer;

    public ImageViewerViewModel(@NonNull Application application) {
        super(application);
    }

    public void setImageUris(List<Uri> uris) {
        ImageViewerState current = _state.getValue();
        if (current != null) {
            current.setImageUris(uris);
            current.setCurrentIndex(0);
            current.setPlaying(false);
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

    public void next() {
        ImageViewerState current = _state.getValue();
        if (current == null) return;

        int nextIndex = current.getCurrentIndex() + 1;
        if (nextIndex >= current.getTotalImages()) {
            nextIndex = 0;
        }
        current.setCurrentIndex(nextIndex);
        _state.postValue(current); // Use postValue for background thread safety
    }

    public void previous() {
        ImageViewerState current = _state.getValue();
        if (current == null) return;

        int prevIndex = current.getCurrentIndex() - 1;
        if (prevIndex < 0) {
            prevIndex = Math.max(0, current.getTotalImages() - 1);
        }
        current.setCurrentIndex(prevIndex);
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
            current.setCurrentIndex(0);
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
                    next();
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
