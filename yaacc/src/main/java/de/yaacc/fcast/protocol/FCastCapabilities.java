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
package de.yaacc.fcast.protocol;

import java.util.Collections;
import java.util.List;

/**
 * What a v4 receiver says it can play ({@code ReceiverCapabilities} of its introduction). The lists hold the short lowercase tokens of
 * the protocol document ("h264", "mkv", "srt", ...), cleaned and cut. Immutable. A v3 receiver has no capabilities: the state holds null.
 */
public final class FCastCapabilities {
    private final List<String> protocols;
    private final List<String> containers;
    private final List<String> videoFormats;
    private final List<String> audioFormats;
    private final List<String> subtitleFormats;
    private final List<String> hdrFormats;
    private final List<String> imageFormats;
    private final boolean externalSubtitles;
    private final boolean mirroring;
    private final Double volumeStep;
    private final int displayWidth;
    private final int displayHeight;

    public FCastCapabilities(List<String> protocols, List<String> containers, List<String> videoFormats, List<String> audioFormats,
                             List<String> subtitleFormats, List<String> hdrFormats, List<String> imageFormats, boolean externalSubtitles,
                             boolean mirroring, Double volumeStep, int displayWidth, int displayHeight) {
        this.protocols = copy(protocols);
        this.containers = copy(containers);
        this.videoFormats = copy(videoFormats);
        this.audioFormats = copy(audioFormats);
        this.subtitleFormats = copy(subtitleFormats);
        this.hdrFormats = copy(hdrFormats);
        this.imageFormats = copy(imageFormats);
        this.externalSubtitles = externalSubtitles;
        this.mirroring = mirroring;
        this.volumeStep = volumeStep;
        this.displayWidth = displayWidth;
        this.displayHeight = displayHeight;
    }

    private static List<String> copy(List<String> list) {
        return list == null ? Collections.<String>emptyList() : Collections.unmodifiableList(new java.util.ArrayList<>(list));
    }

    public List<String> getProtocols() {
        return protocols;
    }

    public List<String> getContainers() {
        return containers;
    }

    public List<String> getVideoFormats() {
        return videoFormats;
    }

    public List<String> getAudioFormats() {
        return audioFormats;
    }

    public List<String> getSubtitleFormats() {
        return subtitleFormats;
    }

    public List<String> getHdrFormats() {
        return hdrFormats;
    }

    public List<String> getImageFormats() {
        return imageFormats;
    }

    /** True if the receiver takes a subtitle file by address ({@code AddSubtitleSource}). */
    public boolean supportsExternalSubtitles() {
        return externalSubtitles;
    }

    public boolean supportsMirroring() {
        return mirroring;
    }

    /** The volume step the receiver's own controls use (0 to 1), or null. */
    public Double getVolumeStep() {
        return volumeStep;
    }

    /** The receiver's screen size in pixels, 0 if it did not say. */
    public int getDisplayWidth() {
        return displayWidth;
    }

    public int getDisplayHeight() {
        return displayHeight;
    }
}
