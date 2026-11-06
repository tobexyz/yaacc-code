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
package de.yaacc.upnp.server.openhome;

import org.fourthline.cling.model.ValidationException;
import org.fourthline.cling.model.meta.LocalService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import de.yaacc.openhome.OhError;
import de.yaacc.openhome.OhIds;
import de.yaacc.openhome.OhQueue;
import de.yaacc.openhome.OhSourceXml;
import de.yaacc.openhome.OhTrackList;
import de.yaacc.openhome.OhTransportState;
import de.yaacc.openhome.OhUriPolicy;
import de.yaacc.openhome.OhVolume;

/**
 * The OpenHome side of YAACC's renderer: the play queue, the state of the five services (Product, Playlist, Info, Time, Volume) and
 * what the actions do. Actions that need the player go through {@link OhPlayback}; what the player does is reported back with the
 * {@code set...} methods, which change the state variables and so send the events.
 * <p>
 * Everything a control point sends is checked here: the address of a track (http and https only), the sizes, the number of
 * ids of a {@code ReadList}, the range of a volume. The events of the {@code IdArray} are delayed a little, so a burst of
 * {@code Insert}s is one event.
 */
public final class OhRendererCore {
    /** How long the {@code IdArray} event waits for more edits. */
    public static final long ID_ARRAY_DELAY_MILLIS = 150;
    /** The most ids {@code ReadList} answers. */
    static final int READ_LIST_MAX = OhQueue.DEFAULT_TRACKS_MAX;

    /** Runs something later; the renderer uses a timer, tests run it by hand. */
    public interface Delayer {
        void runLater(Runnable runnable, long delayMillis);
    }

    /** What is shown as the device. */
    public static final class Details {
        public final String manufacturer;
        public final String manufacturerUrl;
        public final String model;
        public final String room;
        public final String name;

        public Details(String manufacturer, String manufacturerUrl, String model, String room, String name) {
            this.manufacturer = manufacturer;
            this.manufacturerUrl = manufacturerUrl;
            this.model = model;
            this.room = room;
            this.name = name;
        }
    }

    private final OhQueue queue = new OhQueue(OhQueue.DEFAULT_TRACKS_MAX);
    private final OhServiceState product = new OhServiceState();
    private final OhServiceState playlist = new OhServiceState();
    private final OhServiceState info = new OhServiceState();
    private final OhServiceState time = new OhServiceState();
    private final OhServiceState volume = new OhServiceState();
    private final OhPlayback playback;
    private final Delayer delayer;
    private final AtomicBoolean idArrayPending = new AtomicBoolean();

    public OhRendererCore(OhPlayback playback, Delayer delayer, Details details, String protocolInfo) {
        this.playback = playback;
        this.delayer = delayer;
        initProduct(details);
        playlist.init("TransportState", OhTransportState.STOPPED.value());
        playlist.init("Repeat", "false");
        playlist.init("Shuffle", "false");
        playlist.init("Id", "0");
        playlist.init("IdArray", "");
        playlist.init("TracksMax", String.valueOf(queue.tracksMax()));
        playlist.init("ProtocolInfo", protocolInfo);
        for (String v : new String[]{"TrackCount", "DetailsCount", "MetatextCount", "Duration", "BitRate", "BitDepth", "SampleRate"}) {
            info.init(v, "0");
        }
        info.init("Uri", "");
        info.init("Metadata", "");
        info.init("Lossless", "false");
        info.init("CodecName", "");
        info.init("Metatext", "");
        time.init("TrackCount", "0");
        time.init("Duration", "0");
        time.init("Seconds", "0");
        volume.init("Volume", "100");
        volume.init("Mute", "false");
        volume.init("Balance", "0");
        volume.init("Fade", "0");
        volume.init("VolumeLimit", String.valueOf(OhVolume.RENDERER_MAX));
        volume.init("VolumeMax", String.valueOf(OhVolume.RENDERER_MAX));
        volume.init("VolumeUnity", String.valueOf(OhVolume.RENDERER_MAX));
        volume.init("VolumeSteps", String.valueOf(OhVolume.RENDERER_MAX));
        volume.init("VolumeMilliDbPerStep", "0");
        volume.init("BalanceMax", "0");
        volume.init("FadeMax", "0");
        volume.init("UnityGain", "false");
    }

    private void initProduct(Details d) {
        product.init("ManufacturerName", d.manufacturer);
        product.init("ManufacturerInfo", "");
        product.init("ManufacturerUrl", d.manufacturerUrl);
        product.init("ManufacturerImageUri", "");
        product.init("ModelName", d.model);
        product.init("ModelInfo", "");
        product.init("ModelUrl", "");
        product.init("ModelImageUri", "");
        product.init("ProductRoom", d.room);
        product.init("ProductName", d.name);
        product.init("ProductInfo", "");
        product.init("ProductUrl", "");
        product.init("ProductImageUri", "");
        product.init("Standby", "false");
        product.init("SourceIndex", "0");
        product.init("SourceCount", "1");
        product.init("SourceXml", OhSourceXml.playlistOnly());
        product.init("Attributes", "Info Time Volume");
        product.init("SourceXmlChangeCount", "0");
        product.init("SourceType", OhSourceXml.PLAYLIST);
        product.init("SourceName", OhSourceXml.PLAYLIST);
        product.init("SourceSystemName", OhSourceXml.PLAYLIST);
        product.init("SourceVisible", "true");
    }

    public OhQueue queue() {
        return queue;
    }

    /** The five services, ready to be added to the renderer device. */
    public List<LocalService<?>> buildServices(int lockTimeoutMillis) throws ValidationException {
        // the device is built again now and then (network changes); the old services must not hold on to the states
        for (OhServiceState state : new OhServiceState[]{product, playlist, info, time, volume}) {
            state.clearListeners();
        }
        List<LocalService<?>> services = new ArrayList<>();
        services.add(OhLocalService.build(OhServiceDefs.product(), product, productHandlers(), lockTimeoutMillis));
        services.add(OhLocalService.build(OhServiceDefs.playlist(), playlist, playlistHandlers(), lockTimeoutMillis));
        services.add(OhLocalService.build(OhServiceDefs.info(), info, new HashMap<String, OhActionHandler>(), lockTimeoutMillis));
        services.add(OhLocalService.build(OhServiceDefs.time(), time, new HashMap<String, OhActionHandler>(), lockTimeoutMillis));
        services.add(OhLocalService.build(OhServiceDefs.volume(), volume, volumeHandlers(), lockTimeoutMillis));
        return services;
    }

    /** For tests: the current value of a state variable of the service ("playlist", "info", "time", "volume", "product"). */
    String stateValue(String service, String variable) {
        switch (service) {
            case "playlist":
                return playlist.get(variable);
            case "info":
                return info.get(variable);
            case "time":
                return time.get(variable);
            case "volume":
                return volume.get(variable);
            case "product":
                return product.get(variable);
            default:
                throw new IllegalArgumentException(service);
        }
    }

    // ---- what the player reports

    public void setTransportState(OhTransportState state) {
        playlist.set("TransportState", state.value());
    }

    /** The id of the track that plays (0 for none). */
    public void setCurrentId(long id) {
        playlist.set("Id", String.valueOf(id));
    }

    public void setRepeat(boolean repeat) {
        playlist.set("Repeat", String.valueOf(repeat));
    }

    public void setShuffle(boolean shuffle) {
        playlist.set("Shuffle", String.valueOf(shuffle));
    }

    /** The track that plays: its address and DIDL-Lite text; the counters go up with it. */
    public void setTrack(String uri, String metadata, long durationSeconds) {
        Map<String, String> changes = new LinkedHashMap<>();
        changes.put("Uri", uri == null ? "" : uri);
        changes.put("Metadata", metadata == null ? "" : metadata);
        changes.put("Duration", String.valueOf(Math.max(0, durationSeconds)));
        changes.put("TrackCount", String.valueOf(counter(info.get("TrackCount")) + 1));
        info.setAll(changes);
        Map<String, String> times = new LinkedHashMap<>();
        times.put("TrackCount", info.get("TrackCount"));
        times.put("Duration", String.valueOf(Math.max(0, durationSeconds)));
        times.put("Seconds", "0");
        time.setAll(times);
    }

    public void setSeconds(long seconds) {
        time.set("Seconds", String.valueOf(Math.max(0, seconds)));
    }

    public void setDuration(long durationSeconds) {
        Map<String, String> changes = new LinkedHashMap<>();
        changes.put("Duration", String.valueOf(Math.max(0, durationSeconds)));
        info.setAll(changes);
        time.setAll(changes);
    }

    public void setVolumePercent(int percent) {
        volume.set("Volume", String.valueOf(OhVolume.fromPercent(percent, OhVolume.RENDERER_MAX)));
    }

    public void setMuted(boolean muted) {
        volume.set("Mute", String.valueOf(muted));
    }

    private static long counter(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---- the queue

    private void queueEdited() {
        if (idArrayPending.compareAndSet(false, true)) {
            delayer.runLater(new Runnable() {
                @Override
                public void run() {
                    idArrayPending.set(false);
                    playlist.set("IdArray", OhIds.encodeIdArray(queue.idArray()));
                }
            }, ID_ARRAY_DELAY_MILLIS);
        }
    }

    // ---- the handlers

    private static Map<String, String> one(String name, String value) {
        Map<String, String> result = new HashMap<>();
        result.put(name, value);
        return result;
    }

    private static boolean bool(String text) {
        return "true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text);
    }

    private static long number(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            throw OhError.invalid("not a number");
        }
    }

    private Map<String, OhActionHandler> playlistHandlers() {
        Map<String, OhActionHandler> h = new HashMap<>();
        h.put("Play", in -> {
            playback.play();
            return null;
        });
        h.put("Pause", in -> {
            playback.pause();
            return null;
        });
        h.put("Stop", in -> {
            playback.stop();
            return null;
        });
        h.put("Next", in -> {
            playback.next();
            return null;
        });
        h.put("Previous", in -> {
            playback.previous();
            return null;
        });
        h.put("SetRepeat", in -> {
            boolean repeat = bool(in.get("Value"));
            playback.setRepeat(repeat);
            setRepeat(repeat);
            return null;
        });
        h.put("SetShuffle", in -> {
            boolean shuffle = bool(in.get("Value"));
            playback.setShuffle(shuffle);
            setShuffle(shuffle);
            return null;
        });
        h.put("SeekSecondAbsolute", in -> {
            long second = number(in.get("Value"));
            if (second < 0) {
                throw OhError.seekFailure();
            }
            playback.seekSecondAbsolute(second);
            return null;
        });
        h.put("SeekSecondRelative", in -> {
            long seconds = number(in.get("Value"));
            if (seconds < Integer.MIN_VALUE || seconds > Integer.MAX_VALUE) {
                throw OhError.seekFailure();
            }
            playback.seekSecondRelative((int) seconds);
            return null;
        });
        h.put("SeekId", in -> {
            long id = number(in.get("Value"));
            queue.read(id); // 800 if it is not in the queue
            playback.seekToTrack(id);
            return null;
        });
        h.put("SeekIndex", in -> {
            long index = number(in.get("Value"));
            if (index < 0 || index > Integer.MAX_VALUE) {
                throw OhError.indexNotFound();
            }
            playback.seekToTrack(queue.idAt((int) index)); // 802 if there is no track
            return null;
        });
        h.put("Read", in -> {
            OhQueue.Entry entry = queue.read(number(in.get("Id")));
            Map<String, String> out = new HashMap<>();
            out.put("Uri", entry.getUri());
            out.put("Metadata", entry.getMetadata());
            return out;
        });
        h.put("ReadList", in -> one("TrackList", OhTrackList.toXml(queue.readList(OhIds.parseIdList(in.get("IdList"), READ_LIST_MAX)))));
        h.put("Insert", in -> {
            long afterId = number(in.get("AfterId"));
            String uri = in.get("Uri");
            if (!OhUriPolicy.isAcceptable(uri)) {
                throw OhError.invalid("address");
            }
            long id = queue.insert(afterId, uri, in.get("Metadata"));
            queueEdited();
            playback.trackInserted(queue.indexOf(id), queue.read(id));
            return one("NewId", String.valueOf(id));
        });
        h.put("DeleteId", in -> {
            long id = number(in.get("Value"));
            int index = queue.indexOf(id);
            queue.delete(id); // 800 if it is not in the queue
            queueEdited();
            playback.trackDeleted(index, id);
            return null;
        });
        h.put("DeleteAll", in -> {
            queue.deleteAll();
            queueEdited();
            playback.queueCleared();
            return null;
        });
        h.put("IdArray", in -> {
            Map<String, String> out = new HashMap<>();
            long token = queue.token();
            out.put("Token", String.valueOf(token));
            out.put("Array", OhIds.encodeIdArray(queue.idArray()));
            return out;
        });
        h.put("IdArrayChanged", in -> one("Value", String.valueOf(number(in.get("Token")) != queue.token())));
        return h;
    }

    private Map<String, OhActionHandler> productHandlers() {
        Map<String, OhActionHandler> h = new HashMap<>();
        // the phone is the device and always awake: the request is accepted and changes nothing
        h.put("SetStandby", in -> null);
        h.put("SetSourceIndex", in -> {
            if (number(in.get("Value")) != 0) {
                throw OhError.invalid("source");
            }
            return null;
        });
        h.put("SetSourceIndexByName", in -> {
            requirePlaylistSource(in.get("Value"));
            return null;
        });
        h.put("SetSourceBySystemName", in -> {
            requirePlaylistSource(in.get("Value"));
            return null;
        });
        h.put("Source", in -> {
            if (number(in.get("Index")) != 0) {
                throw OhError.invalid("source");
            }
            Map<String, String> out = new HashMap<>();
            out.put("SystemName", OhSourceXml.PLAYLIST);
            out.put("Type", OhSourceXml.PLAYLIST);
            out.put("Name", OhSourceXml.PLAYLIST);
            out.put("Visible", "true");
            return out;
        });
        return h;
    }

    private static void requirePlaylistSource(String name) {
        if (!OhSourceXml.PLAYLIST.equals(name)) {
            throw OhError.invalid("source");
        }
    }

    private Map<String, OhActionHandler> volumeHandlers() {
        Map<String, OhActionHandler> h = new HashMap<>();
        h.put("SetVolume", in -> {
            setVolumeTo(number(in.get("Value")));
            return null;
        });
        h.put("VolumeInc", in -> {
            setVolumeTo(number(volume.get("Volume")) + 1);
            return null;
        });
        h.put("VolumeDec", in -> {
            setVolumeTo(number(volume.get("Volume")) - 1);
            return null;
        });
        h.put("SetMute", in -> {
            boolean mute = bool(in.get("Value"));
            playback.setMute(mute);
            setMuted(mute);
            return null;
        });
        // balance and fade do not exist on the phone: the requests are accepted and change nothing
        for (String ignored : new String[]{"SetBalance", "BalanceInc", "BalanceDec", "SetFade", "FadeInc", "FadeDec"}) {
            h.put(ignored, in -> null);
        }
        return h;
    }

    private void setVolumeTo(long steps) {
        long clamped = Math.max(0, Math.min(steps, OhVolume.RENDERER_MAX));
        playback.setVolume(OhVolume.toPercent(clamped, OhVolume.RENDERER_MAX));
        volume.set("Volume", String.valueOf(clamped));
    }
}
