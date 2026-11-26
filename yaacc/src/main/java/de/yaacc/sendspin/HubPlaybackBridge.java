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
package de.yaacc.sendspin;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.PersonWithRole;
import org.fourthline.cling.support.model.item.Item;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import de.yaacc.Yaacc;
import de.yaacc.browser.DeviceSessionGrouper;
import de.yaacc.player.PlayMode;
import de.yaacc.player.PlayableItem;
import de.yaacc.player.Player;
import de.yaacc.sendspin.control.PlaybackBridge;
import de.yaacc.upnp.UpnpClient;

/**
 * The playback of the app, as the controller role of the server sees it (issue #325, K15): one group for each Sendspin player (or
 * leader of a group) that an app player plays to. The transport and play mode commands are the app's own methods, run on the main
 * thread as the buttons of the app do; the volume and the mute are the group's, as the slider of the card sets them.
 */
final class HubPlaybackBridge implements PlaybackBridge {

    private final Context context;
    private final SendspinHub hub;
    private final Handler main = new Handler(Looper.getMainLooper());

    HubPlaybackBridge(Context context, SendspinHub hub) {
        this.context = context.getApplicationContext();
        this.hub = hub;
    }

    @Override
    public List<Group> groups() {
        List<Group> result = new ArrayList<>();
        UpnpClient client = ((Yaacc) context).getUpnpClient();
        if (client == null) {
            return result;
        }
        Collection<Player> players = client.getCurrentPlayers();
        for (SendspinReceiverDevice device : hub.receivers()) {
            String id = device.getReceiverId();
            if (device.isServer() || device.isSelf() || hub.groups().leaderOf(id) != null) {
                continue; // a member has no player of its own: the group's is its leader's
            }
            List<DeviceSessionGrouper.PlayerRef> refs = new ArrayList<>();
            for (Player player : players) {
                refs.add(new DeviceSessionGrouper.PlayerRef(player.getId(), player.getDeviceId(), player.isPlaying(),
                        client.getPlayerCreationSequence(player)));
            }
            DeviceSessionGrouper.PlayerRef primary = DeviceSessionGrouper.primarySession(id, refs);
            if (primary == null) {
                continue;
            }
            for (Player player : players) {
                if (player.getId() == primary.playerId()) {
                    result.add(new PlayerGroup(id, device, player, client.getPlayerCreationSequence(player)));
                }
            }
        }
        return result;
    }

    private final class PlayerGroup implements Group {
        private final String id;
        private final SendspinReceiverDevice device;
        private final Player player;
        private final long serial;

        PlayerGroup(String id, SendspinReceiverDevice device, Player player, long serial) {
            this.id = id;
            this.device = device;
            this.player = player;
            this.serial = serial;
        }

        private PlayableItem current() {
            List<PlayableItem> items = player.getItems();
            int index = player.getCurrentItemIndex();
            return items != null && index >= 0 && index < items.size() ? items.get(index) : null;
        }

        private Item didl() {
            PlayableItem item = current();
            return item == null ? null : item.getItem();
        }

        @Override public String id() { return id; }

        @Override
        public String name() {
            String name = SendspinRowText.clean(device.getName());
            int members = hub.groups().membersOf(id).size();
            return members == 0 ? name : name + " +" + members;
        }

        @Override public boolean isPlaying() { return player.isPlaying(); }

        @Override public long activitySerial() { return serial; }

        @Override
        public int itemCount() {
            List<PlayableItem> items = player.getItems();
            return items == null ? 0 : items.size();
        }

        @Override
        public long durationMs() {
            PlayableItem item = current();
            if (item != null && item.getDuration() > 0) {
                return item.getDuration();
            }
            return parseMillis(player.getDuration());
        }

        @Override public long positionMs() { return Math.max(0L, player.getCurrentPosition()); }

        @Override
        public int volume() {
            Integer v = hub.groupVolumeOf(id);
            return v == null ? 100 : v;
        }

        @Override public boolean isMuted() { return hub.groupMutedOf(id); }

        @Override public boolean supportsVolume() { return hub.groupVolumeOf(id) != null; }

        @Override public boolean supportsMute() { return hub.groupVolumeOf(id) != null; }

        @Override public boolean supportsPlayModes() { return player.getPlayMode() != null; }

        @Override
        public String repeat() {
            PlayMode mode = player.getPlayMode();
            return mode == null ? "off" : mode.getRepeat().name().toLowerCase(Locale.ROOT);
        }

        @Override
        public boolean isShuffle() {
            PlayMode mode = player.getPlayMode();
            return mode != null && mode.isShuffle();
        }

        @Override public String title() { return player.getCurrentItemTitle(); }

        @Override
        public String artist() {
            Item item = didl();
            if (item == null) {
                return null;
            }
            PersonWithRole artist = item.getFirstPropertyValue(DIDLObject.Property.UPNP.ARTIST.class);
            if (artist != null && artist.getName() != null && !artist.getName().isEmpty()) {
                return artist.getName();
            }
            return item.getCreator();
        }

        @Override
        public String album() {
            Item item = didl();
            return item == null ? null : item.getFirstPropertyValue(DIDLObject.Property.UPNP.ALBUM.class);
        }

        @Override
        public String artworkUrl() {
            URI art = player.getAlbumArt();
            if (art == null) {
                return null;
            }
            String scheme = art.getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme) ? art.toString() : null;
        }

        @Override
        public int year() {
            Item item = didl();
            String date = item == null ? null : item.getFirstPropertyValue(DIDLObject.Property.DC.DATE.class);
            if (date != null && date.length() >= 4 && date.substring(0, 4).matches("[0-9]{4}")) {
                return Integer.parseInt(date.substring(0, 4));
            }
            return 0;
        }

        @Override
        public int track() {
            Item item = didl();
            Integer n = item == null ? null : item.getFirstPropertyValue(DIDLObject.Property.UPNP.ORIGINAL_TRACK_NUMBER.class);
            return n == null || n < 1 ? 0 : n;
        }

        @Override public void play() { main.post(player::play); }

        @Override public void pause() { main.post(player::pause); }

        @Override public void stop() { main.post(player::stop); }

        @Override public void next() { main.post(player::next); }

        @Override public void previous() { main.post(player::previous); }

        @Override public void setVolume(int percent) { hub.setGroupVolume(id, percent); }

        @Override public void setMuted(boolean muted) { hub.setGroupMuted(id, muted); }

        @Override
        public void setRepeat(final String mode) {
            main.post(() -> {
                PlayMode now = player.getPlayMode();
                if (now != null) {
                    player.setPlayMode(now.withRepeat(PlayMode.RepeatMode.valueOf(mode.toUpperCase(Locale.ROOT))));
                }
            });
        }

        @Override
        public void setShuffle(final boolean on) {
            main.post(() -> {
                PlayMode now = player.getPlayMode();
                if (now != null) {
                    player.setPlayMode(now.withShuffle(on));
                }
            });
        }

        @Override public void seekTo(final long millis) { main.post(() -> player.seekTo(millis)); }
    }

    /** "H:MM:SS" or "H:MM:SS.fff" (a UPnP duration) to milliseconds; 0 if it is not one. */
    static long parseMillis(String time) {
        if (time == null) {
            return 0L;
        }
        String[] parts = time.trim().split(":");
        if (parts.length != 3 && parts.length != 2) {
            return 0L;
        }
        try {
            double seconds = 0;
            for (String part : parts) {
                seconds = seconds * 60 + Double.parseDouble(part);
            }
            return Math.max(0L, (long) (seconds * 1000));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
