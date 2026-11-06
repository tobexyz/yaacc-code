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
package de.yaacc.openhome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Controls the playlist of an OpenHome device: the device owns the queue, this class reads it, edits it and starts the playback.
 * Written against {@link OhControl}, so it knows nothing about Cling or Android. Not thread safe: use it from one thread.
 */
public final class OhRemote {
    private static final String PRODUCT = "Product";
    private static final String PLAYLIST = "Playlist";
    private static final String INFO = "Info";
    private static final String TIME = "Time";
    private static final String VOLUME = "Volume";

    /** How many tracks one {@code ReadList} asks for. */
    static final int READ_BATCH = 50;
    private static final int WAKE_ATTEMPTS = 20;
    private static final long WAKE_PAUSE_MILLIS = 500;

    /** Waits; replaced in tests. */
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** A track to put into the queue. */
    public static final class Track {
        public final String uri;
        public final String metadata;

        public Track(String uri, String metadata) {
            this.uri = uri;
            this.metadata = metadata == null ? "" : metadata;
        }
    }

    /** What the device says it is doing. */
    public static final class State {
        public OhTransportState transportState = OhTransportState.STOPPED;
        public long id;
        public boolean repeat;
        public boolean shuffle;
        public long seconds;
        public long durationSeconds;
        public String uri = "";
        public String metadata = "";
        public int volumePercent;
        public boolean muted;
    }

    private final OhControl control;
    private final Sleeper sleeper;
    private final Map<Long, OhQueue.Entry> known = new HashMap<>();
    private long volumeMax = -1;

    public OhRemote(OhControl control) {
        this(control, Thread::sleep);
    }

    public OhRemote(OhControl control, Sleeper sleeper) {
        this.control = control;
        this.sleeper = sleeper;
    }

    private Map<String, String> call(String service, String action, String... nameValues) throws OhControlException {
        Map<String, String> arguments = new LinkedHashMap<>();
        for (int i = 0; i + 1 < nameValues.length; i += 2) {
            arguments.put(nameValues[i], nameValues[i + 1]);
        }
        Map<String, String> result = control.invoke(service, action, arguments);
        return result == null ? Collections.<String, String>emptyMap() : result;
    }

    private static String get(Map<String, String> map, String name) {
        String value = map.get(name);
        return value == null ? "" : value;
    }

    private static long number(String text, long fallback) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ---- getting ready

    /**
     * Wakes the device from standby and switches it to its playlist source. Waits (a few seconds) for a device that wakes slowly.
     *
     * @throws OhControlException when the device has no playlist source or does not answer
     */
    public void prepare() throws OhControlException {
        try {
            if (OhSources.parseBoolean(get(call(PRODUCT, "Standby"), "Value"), false)) {
                call(PRODUCT, "SetStandby", "Value", "false");
                for (int i = 0; i < WAKE_ATTEMPTS
                        && OhSources.parseBoolean(get(call(PRODUCT, "Standby"), "Value"), false); i++) {
                    sleeper.sleep(WAKE_PAUSE_MILLIS);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OhControlException("interrupted while the device wakes up", e);
        }
        OhSources.Source playlist = OhSources.findPlaylist(OhSources.parse(get(call(PRODUCT, "SourceXml"), "Value")));
        if (playlist == null) {
            throw new OhControlException(OhError.INVALID_ARGS, "the device has no playlist source");
        }
        if (number(get(call(PRODUCT, "SourceIndex"), "Value"), -1) != playlist.index) {
            call(PRODUCT, "SetSourceIndex", "Value", String.valueOf(playlist.index));
        }
    }

    // ---- the queue

    public int tracksMax() throws OhControlException {
        return (int) Math.min(Integer.MAX_VALUE, number(get(call(PLAYLIST, "TracksMax"), "Value"), OhQueue.DEFAULT_TRACKS_MAX));
    }

    /** @return the ids of the queue in play order */
    public List<Long> idArray() throws OhControlException {
        return OhIds.decodeIdArray(get(call(PLAYLIST, "IdArray"), "Array"));
    }

    /** The tracks of the queue; only the ones not seen before are read from the device. */
    public List<OhQueue.Entry> queue() throws OhControlException {
        List<Long> ids = idArray();
        List<Long> missing = new ArrayList<>();
        for (Long id : ids) {
            if (!known.containsKey(id)) {
                missing.add(id);
            }
        }
        for (int from = 0; from < missing.size(); from += READ_BATCH) {
            List<Long> batch = missing.subList(from, Math.min(missing.size(), from + READ_BATCH));
            String xml = get(call(PLAYLIST, "ReadList", "IdList", OhIds.formatIdList(batch)), "TrackList");
            for (OhQueue.Entry entry : OhTrackListParser.parse(xml)) {
                known.put(entry.getId(), entry);
            }
        }
        known.keySet().retainAll(new java.util.HashSet<>(ids));
        List<OhQueue.Entry> result = new ArrayList<>(ids.size());
        for (Long id : ids) {
            OhQueue.Entry entry = known.get(id);
            // a track the device could not give (deleted in the meantime) is left out
            if (entry != null) {
                result.add(entry);
            }
        }
        return result;
    }

    /** @return the id of the new track */
    public long insert(long afterId, Track track) throws OhControlException {
        long id = number(get(call(PLAYLIST, "Insert", "AfterId", String.valueOf(afterId), "Uri", track.uri, "Metadata",
                track.metadata), "NewId"), 0);
        if (!OhIds.isId(id)) {
            throw new OhControlException(OhError.INVALID_ARGS, "the device gave no id for the new track");
        }
        known.put(id, new OhQueue.Entry(id, track.uri, track.metadata));
        return id;
    }

    /** Puts the tracks at the end of the queue, one after the other. @return their ids */
    public List<Long> append(List<Track> tracks) throws OhControlException {
        List<Long> ids = idArray();
        long after = ids.isEmpty() ? 0 : ids.get(ids.size() - 1);
        return insertAfter(after, tracks);
    }

    /** Empties the queue and puts the tracks in. @return their ids */
    public List<Long> replace(List<Track> tracks) throws OhControlException {
        deleteAll();
        return insertAfter(0, tracks);
    }

    private List<Long> insertAfter(long afterId, List<Track> tracks) throws OhControlException {
        List<Long> ids = new ArrayList<>(tracks.size());
        long after = afterId;
        for (Track track : tracks) {
            after = insert(after, track);
            ids.add(after);
        }
        return ids;
    }

    public void deleteId(long id) throws OhControlException {
        call(PLAYLIST, "DeleteId", "Value", String.valueOf(id));
        known.remove(id);
    }

    public void deleteAll() throws OhControlException {
        call(PLAYLIST, "DeleteAll");
        known.clear();
    }

    // ---- transport

    public void play() throws OhControlException {
        call(PLAYLIST, "Play");
    }

    public void pause() throws OhControlException {
        call(PLAYLIST, "Pause");
    }

    public void stop() throws OhControlException {
        call(PLAYLIST, "Stop");
    }

    public void next() throws OhControlException {
        call(PLAYLIST, "Next");
    }

    public void previous() throws OhControlException {
        call(PLAYLIST, "Previous");
    }

    public void seekId(long id) throws OhControlException {
        call(PLAYLIST, "SeekId", "Value", String.valueOf(id));
    }

    public void seekIndex(long index) throws OhControlException {
        call(PLAYLIST, "SeekIndex", "Value", String.valueOf(index));
    }

    public void seekSecondAbsolute(long second) throws OhControlException {
        call(PLAYLIST, "SeekSecondAbsolute", "Value", String.valueOf(Math.max(0, second)));
    }

    public void setRepeat(boolean repeat) throws OhControlException {
        call(PLAYLIST, "SetRepeat", "Value", String.valueOf(repeat));
    }

    public void setShuffle(boolean shuffle) throws OhControlException {
        call(PLAYLIST, "SetShuffle", "Value", String.valueOf(shuffle));
    }

    // ---- volume

    private long volumeMax() throws OhControlException {
        if (volumeMax <= 0) {
            volumeMax = number(get(call(VOLUME, "Characteristics"), "VolumeMax"), OhVolume.RENDERER_MAX);
            if (volumeMax <= 0) {
                volumeMax = OhVolume.RENDERER_MAX;
            }
        }
        return volumeMax;
    }

    public void setVolumePercent(int percent) throws OhControlException {
        call(VOLUME, "SetVolume", "Value", String.valueOf(OhVolume.fromPercent(percent, volumeMax())));
    }

    public void setMute(boolean mute) throws OhControlException {
        call(VOLUME, "SetMute", "Value", String.valueOf(mute));
    }

    // ---- state

    /** Reads what the device does. A service the device does not answer leaves its part of the state as it was empty. */
    public State readState() throws OhControlException {
        State state = new State();
        OhTransportState transport = OhTransportState.fromValue(get(call(PLAYLIST, "TransportState"), "Value"));
        state.transportState = transport == null ? OhTransportState.STOPPED : transport;
        state.id = number(get(call(PLAYLIST, "Id"), "Value"), 0);
        state.repeat = OhSources.parseBoolean(get(call(PLAYLIST, "Repeat"), "Value"), false);
        state.shuffle = OhSources.parseBoolean(get(call(PLAYLIST, "Shuffle"), "Value"), false);
        try {
            Map<String, String> time = call(TIME, "Time");
            state.seconds = number(get(time, "Seconds"), 0);
            state.durationSeconds = number(get(time, "Duration"), 0);
            Map<String, String> track = call(INFO, "Track");
            state.uri = get(track, "Uri");
            state.metadata = get(track, "Metadata");
        } catch (OhControlException e) {
            if (e.getCode() == OhControlException.NO_ANSWER) {
                throw e;
            }
            // the device refused a service that is only nice to have
        }
        try {
            state.volumePercent = OhVolume.toPercent(number(get(call(VOLUME, "Volume"), "Value"), 0), volumeMax());
            state.muted = OhSources.parseBoolean(get(call(VOLUME, "Mute"), "Value"), false);
        } catch (OhControlException e) {
            if (e.getCode() == OhControlException.NO_ANSWER) {
                throw e;
            }
        }
        return state;
    }
}
