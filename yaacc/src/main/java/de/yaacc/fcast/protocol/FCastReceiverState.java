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

/**
 * What YAACC knows about one receiver <b>as the receiver reported it</b> (immutable snapshot). A command of ours never changes
 * the playback fields: only the receiver's messages do ({@link FCastSession}); until it says so the UI sees {@link Pending}.
 */
public final class FCastReceiverState {
    /** The link to the receiver. */
    public enum Link {
        /** No connection (none wanted, or closed after the idle time). */
        IDLE,
        CONNECTING,
        CONNECTED,
        /** The connection was lost while somebody uses the receiver; one retry is under way. */
        RECONNECTING,
        /** The receiver can not be reached (or the retry failed); the text is in {@link #getLastError()}. */
        FAILED
    }

    /** What the receiver says it is doing. */
    public enum Playback {
        /** Nothing was reported yet (or the connection was lost). */
        UNKNOWN,
        IDLE,
        PLAYING,
        PAUSED
    }

    /** A command that was sent and not confirmed by the receiver yet. */
    public enum Pending {
        NONE, PLAY, PAUSE, RESUME, STOP, SEEK
    }

    /** The empty state of a session that has not connected. */
    public static final FCastReceiverState INITIAL = new Builder().build();

    private final Link link;
    private final int protocolVersion;
    private final String receiverName;
    private final String receiverApp;
    private final String receiverAppVersion;
    private final Playback playback;
    private final Double position;
    private final Double duration;
    private final Double speed;
    private final Double volume;
    private final String itemUrl;
    private final String itemContainer;
    private final String itemTitle;
    private final boolean itemFromElsewhere;
    private final Pending pending;
    private final long playbackUpdatedAt;
    private final String lastError;
    private final int errorSerial;
    private final java.util.List<FCastTrack> tracks;
    private final Long selectedAudio;
    private final Long selectedSubtitle;
    private final FCastCapabilities capabilities;
    private final int peerVersion;
    private final FCastPin.Source pinSource;

    private FCastReceiverState(Builder b) {
        this.link = b.link;
        this.protocolVersion = b.protocolVersion;
        this.receiverName = b.receiverName;
        this.receiverApp = b.receiverApp;
        this.receiverAppVersion = b.receiverAppVersion;
        this.playback = b.playback;
        this.position = b.position;
        this.duration = b.duration;
        this.speed = b.speed;
        this.volume = b.volume;
        this.itemUrl = b.itemUrl;
        this.itemContainer = b.itemContainer;
        this.itemTitle = b.itemTitle;
        this.itemFromElsewhere = b.itemFromElsewhere;
        this.pending = b.pending;
        this.playbackUpdatedAt = b.playbackUpdatedAt;
        this.lastError = b.lastError;
        this.errorSerial = b.errorSerial;
        this.tracks = b.tracks;
        this.selectedAudio = b.selectedAudio;
        this.selectedSubtitle = b.selectedSubtitle;
        this.capabilities = b.capabilities;
        this.peerVersion = b.peerVersion;
        this.pinSource = b.pinSource;
    }

    /** The highest version the receiver said it speaks ({@code Version}), 0 before it said. More than {@link #getProtocolVersion()} when the connection is not secured. */
    public int getPeerVersion() {
        return peerVersion;
    }

    /** Where the key of a secured connection came from, null if the connection is not secured. */
    public FCastPin.Source getPinSource() {
        return pinSource;
    }

    /** The tracks of the item the receiver plays (version 4 only), empty if it said none. */
    public java.util.List<FCastTrack> getTracks() {
        return tracks;
    }

    /** The audio track that is selected, or null if the receiver did not say. */
    public Long getSelectedAudio() {
        return selectedAudio;
    }

    /** The subtitle track that is selected, or null for none. */
    public Long getSelectedSubtitle() {
        return selectedSubtitle;
    }

    /** What the receiver can play (version 4 only), or null. */
    public FCastCapabilities getCapabilities() {
        return capabilities;
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public Link getLink() {
        return link;
    }

    /** The version the connection speaks (1 to 3), 0 before the first connection. */
    public int getProtocolVersion() {
        return protocolVersion;
    }

    /** The receiver's own name from its {@code Initial}, or null (an old receiver has none). */
    public String getReceiverName() {
        return receiverName;
    }

    public String getReceiverApp() {
        return receiverApp;
    }

    public String getReceiverAppVersion() {
        return receiverAppVersion;
    }

    public Playback getPlayback() {
        return playback;
    }

    /** The position in seconds as last reported, or null. */
    public Double getPosition() {
        return position;
    }

    public Double getDuration() {
        return duration;
    }

    public Double getSpeed() {
        return speed;
    }

    /** The volume 0 to 1 as last reported, or null while it has not said. */
    public Double getVolume() {
        return volume;
    }

    /** The URL of the item the receiver reported, or null. */
    public String getItemUrl() {
        return itemUrl;
    }

    public String getItemContainer() {
        return itemContainer;
    }

    public String getItemTitle() {
        return itemTitle;
    }

    /** True if the receiver reported an item that this session did not start (another sender, or the receiver itself). */
    public boolean isItemFromElsewhere() {
        return itemFromElsewhere;
    }

    /**
     * True if the receiver reports that the item ended: both reference receivers send a {@code Paused} update with the position
     * at the duration (not an Idle one, see {@code docs/tech.md}). The position counts as at the end within one second of a
     * duration above zero, as the Android receiver does.
     */
    public boolean hasEnded() {
        return playback == Playback.PAUSED && position != null && duration != null && duration > 0 && duration - position <= 1.0;
    }

    /**
     * True if the receiver is playing or paused within one second of the end of the item (a duration above zero). Together with
     * a silence of the receiver this is the safety net for the end of an item: the desktop receiver reports the end only as an
     * event, and a sender that has no subscription sees the updates just stop (docs/tech.md).
     */
    public boolean isNearEnd() {
        return (playback == Playback.PLAYING || playback == Playback.PAUSED) && position != null && duration != null
                && duration > 0 && duration - position <= 1.0;
    }

    public Pending getPending() {
        return pending;
    }

    /**
     * The monotonic clock time (milliseconds, the session's clock) when the last {@code PlaybackUpdate} arrived, 0 if none did since
     * the connection was made. The player needs it for the safety net of the end of an item: the receiver was near the end of the
     * item and nothing came for 2 s.
     */
    public long getPlaybackUpdatedAt() {
        return playbackUpdatedAt;
    }

    /** The last error text (a failed connection, a refused command, a playback error of the receiver), or null. */
    public String getLastError() {
        return lastError;
    }

    /** Counts the errors, so that a UI can show each one once. */
    public int getErrorSerial() {
        return errorSerial;
    }

    @Override
    public String toString() {
        return "FCastReceiverState{" + link + ", v" + protocolVersion + ", " + playback + (pending == Pending.NONE ? "" : ", pending " + pending)
                + ", position " + position + "/" + duration + ", volume " + volume + (lastError == null ? "" : ", error " + lastError) + "}";
    }

    /** Copy-and-change; build() makes the snapshot. */
    public static final class Builder {
        private Link link = Link.IDLE;
        private int protocolVersion;
        private String receiverName;
        private String receiverApp;
        private String receiverAppVersion;
        private Playback playback = Playback.UNKNOWN;
        private Double position;
        private Double duration;
        private Double speed;
        private Double volume;
        private String itemUrl;
        private String itemContainer;
        private String itemTitle;
        private boolean itemFromElsewhere;
        private Pending pending = Pending.NONE;
        private long playbackUpdatedAt;
        private String lastError;
        private int errorSerial;
        private java.util.List<FCastTrack> tracks = java.util.Collections.emptyList();
        private Long selectedAudio;
        private Long selectedSubtitle;
        private FCastCapabilities capabilities;
        private int peerVersion;
        private FCastPin.Source pinSource;

        Builder() {
        }

        Builder(FCastReceiverState s) {
            link = s.link;
            protocolVersion = s.protocolVersion;
            receiverName = s.receiverName;
            receiverApp = s.receiverApp;
            receiverAppVersion = s.receiverAppVersion;
            playback = s.playback;
            position = s.position;
            duration = s.duration;
            speed = s.speed;
            volume = s.volume;
            itemUrl = s.itemUrl;
            itemContainer = s.itemContainer;
            itemTitle = s.itemTitle;
            itemFromElsewhere = s.itemFromElsewhere;
            pending = s.pending;
            playbackUpdatedAt = s.playbackUpdatedAt;
            lastError = s.lastError;
            errorSerial = s.errorSerial;
            tracks = s.tracks;
            selectedAudio = s.selectedAudio;
            selectedSubtitle = s.selectedSubtitle;
            capabilities = s.capabilities;
            peerVersion = s.peerVersion;
            pinSource = s.pinSource;
        }

        public Builder peerVersion(int version) {
            this.peerVersion = version;
            return this;
        }

        public Builder pinSource(FCastPin.Source source) {
            this.pinSource = source;
            return this;
        }

        /** The tracks of the item; the selection is cleared (a new list is a new item). */
        public Builder tracks(java.util.List<FCastTrack> tracks) {
            this.tracks = tracks == null ? java.util.Collections.<FCastTrack>emptyList() : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(tracks));
            this.selectedAudio = null;
            this.selectedSubtitle = null;
            return this;
        }

        /** The track of a type that the receiver has selected now (null id: none). */
        public Builder selectedTrack(FCastTrack.Type type, Long id) {
            if (type == FCastTrack.Type.AUDIO) {
                this.selectedAudio = id;
            } else if (type == FCastTrack.Type.SUBTITLE) {
                this.selectedSubtitle = id;
            }
            return this;
        }

        public Builder capabilities(FCastCapabilities capabilities) {
            this.capabilities = capabilities;
            return this;
        }

        public Builder link(Link link) {
            this.link = link;
            return this;
        }

        public Builder protocolVersion(int version) {
            this.protocolVersion = version;
            return this;
        }

        public Builder receiver(String name, String app, String appVersion) {
            this.receiverName = name;
            this.receiverApp = app;
            this.receiverAppVersion = appVersion;
            return this;
        }

        public Builder playback(Playback playback, Double position, Double duration, Double speed) {
            this.playback = playback;
            this.position = position;
            this.duration = duration;
            this.speed = speed;
            return this;
        }

        public Builder volume(Double volume) {
            this.volume = volume;
            return this;
        }

        public Builder item(String url, String container, String title, boolean fromElsewhere) {
            this.itemUrl = url;
            this.itemContainer = container;
            this.itemTitle = title;
            this.itemFromElsewhere = fromElsewhere;
            return this;
        }

        public Builder pending(Pending pending) {
            this.pending = pending;
            return this;
        }

        public Builder playbackUpdatedAt(long millis) {
            this.playbackUpdatedAt = millis;
            return this;
        }

        /** Sets the error text and counts it; null clears the text (the serial stays). */
        public Builder error(String text) {
            this.lastError = text;
            if (text != null) {
                errorSerial++;
            }
            return this;
        }

        public FCastReceiverState build() {
            return new FCastReceiverState(this);
        }
    }
}
