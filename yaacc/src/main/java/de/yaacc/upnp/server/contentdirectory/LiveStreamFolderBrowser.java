/*
 * Copyright (C) 2026 www.yaacc.de
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
package de.yaacc.upnp.server.contentdirectory;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.preference.PreferenceManager;

import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.Res;
import org.fourthline.cling.support.model.SortCriterion;
import org.fourthline.cling.support.model.container.Container;
import org.fourthline.cling.support.model.container.StorageFolder;
import org.fourthline.cling.support.model.item.AudioItem;
import org.fourthline.cling.support.model.item.Item;
import org.fourthline.cling.support.model.item.VideoItem;
import org.seamless.util.MimeType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import de.yaacc.R;

/**
 * Browser for live streams (system audio and screen cast).
 * Only available on Android 10+.
 *
 * @author Tobias Schoene (tobexyz)
 */
public class LiveStreamFolderBrowser extends ContentBrowser {

    /** Live stream items are title-only - there is no meaningful per-stream date. */
    private static final Map<String, Function<DIDLObject, String>> ITEM_ACCESSOR_MAP =
            Map.of(SortSupport.PROPERTY_TITLE, DIDLObject::getTitle);

    /**
     * No-op comparator used as the {@code defaultComparator} for {@link SortSupport#toComparator}.
     * {@link List#sort} is a stable sort, so applying this leaves the list - already built in
     * today's fixed system-audio-then-screen-cast order - unchanged when no
     * {@code SortCriteria} was requested.
     */
    private static final Comparator<DIDLObject> STABLE_ORDER = (a, b) -> 0;

    public LiveStreamFolderBrowser(Context context) {
        super(context);
    }

    @Override
    public DIDLObject browseMeta(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        return new StorageFolder(
                ContentDirectoryIDs.LIVE_STREAM_FOLDER.getId(),
                ContentDirectoryIDs.ROOT.getId(),
                getContext().getString(R.string.live_streams),
                "yaacc",
                getSize(contentDirectory, myId),
                null);
    }

    @Override
    public Integer getSize(YaaccContentDirectory contentDirectory, String myId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return 0;
        }

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
        int count = 0;

        if (preferences.getBoolean(getContext().getString(R.string.settings_local_server_serve_system_audio_chkbx), false)) {
            count++;
        }
        if (preferences.getBoolean(getContext().getString(R.string.settings_local_server_serve_screen_cast_chkbx), false)) {
            count++;
        }

        return count;
    }

    @Override
    public List<Container> browseContainer(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        return new ArrayList<>();
    }

    /**
     * The item of a live stream of this phone, as the folder lists it and as the cards of the server list hand it to a
     * receiver: the system audio ({@code /live/audio}, wav) or the screen cast ({@code /live/video}, mpeg).
     *
     * @param audio     true for the device audio, false for the screen cast
     * @param ipAddress the address of this phone that the receiver reaches
     */
    public Item liveItem(boolean audio, String ipAddress) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
        String serverName = preferences.getString(getContext().getString(R.string.settings_local_server_name), "YAACC");
        String streamUrl = "http://" + ipAddress + ":" + de.yaacc.upnp.server.YaaccUpnpServerService.PORT
                + (audio ? "/live/audio" : "/live/video");
        de.yaacc.util.YaaccLogger.i(getClass().getName(), "Creating live " + (audio ? "audio" : "video") + " item with URL: " + streamUrl);
        Item item = createItem(
                audio ? ContentDirectoryIDs.LIVE_STREAM_SYSTEM_AUDIO.getId() : ContentDirectoryIDs.LIVE_STREAM_SCREEN_CAST.getId(),
                ContentDirectoryIDs.LIVE_STREAM_FOLDER.getId(),
                serverName + " " + getContext().getString(audio ? R.string.system_audio_stream : R.string.screen_cast_stream),
                "yaacc",
                false,
                MimeType.valueOf(audio ? "audio/wav" : "video/mpeg"),
                streamUrl,
                null,
                null);
        if (!item.getResources().isEmpty()) {
            Res res = item.getResources().get(0);
            if (audio) {
                res.setSampleFrequency(44100L);
                res.setNrAudioChannels(2L);
                res.setBitsPerSample(16L);
            } else {
                res.setResolution("1280x720");
            }
        }
        return item;
    }

    @Override
    public List<Item> browseItem(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        List<Item> result = new ArrayList<>();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return result;
        }

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getContext());

        // System audio stream
        if (preferences.getBoolean(getContext().getString(R.string.settings_local_server_serve_system_audio_chkbx), false)) {
            result.add(liveItem(true, contentDirectory.getIpAddress()));
        }

        // Screen cast stream
        if (preferences.getBoolean(getContext().getString(R.string.settings_local_server_serve_screen_cast_chkbx), false)) {
            result.add(liveItem(false, contentDirectory.getIpAddress()));
        }
        // result is already built in today's fixed default order; STABLE_ORDER preserves
        // that exactly via stable sort when orderby is empty/null.
        result.sort(SortSupport.toComparator(orderby, ITEM_ACCESSOR_MAP, STABLE_ORDER));
        //FIXME experimental not stable working
        // Combined video+audio stream (MPEG-TS)
        /*
        if (preferences.getBoolean(getContext().getString(R.string.settings_local_server_serve_system_audio_chkbx), false) &&
                preferences.getBoolean(getContext().getString(R.string.settings_local_server_serve_screen_cast_chkbx), false)) {

            String streamUrl = "http://" + contentDirectory.getIpAddress() + ":"
                    + de.yaacc.upnp.server.YaaccUpnpServerService.PORT + "/live/videoaudio";

            org.fourthline.cling.support.model.ProtocolInfo protocolInfo =
                    new org.fourthline.cling.support.model.ProtocolInfo(
                            org.fourthline.cling.support.model.Protocol.HTTP_GET,
                            org.fourthline.cling.support.model.ProtocolInfo.WILDCARD,
                            "video/mp2t",
                            "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01500000000000000000000000000000");

            Res res = new Res(protocolInfo, null, streamUrl);
            res.setResolution("1280x720");

            VideoItem combinedItem = new VideoItem(
                    ContentDirectoryIDs.LIVE_STREAM_COMBINED.getId(),
                    ContentDirectoryIDs.LIVE_STREAM_FOLDER.getId(),
                    preferences.getString(getContext().getString(R.string.settings_local_server_name), "YAACC") + " Video+Audio Stream",
                    "yaacc",
                    res);

            result.add(combinedItem);
        }
        */
        return result;
    }
}
