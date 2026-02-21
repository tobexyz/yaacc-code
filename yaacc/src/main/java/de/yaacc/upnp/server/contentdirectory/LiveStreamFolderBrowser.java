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
import java.util.List;

import de.yaacc.R;

/**
 * Browser for live streams (system audio and screen cast).
 * Only available on Android 10+.
 *
 * @author Tobias Schoene (tobexyz)
 */
public class LiveStreamFolderBrowser extends ContentBrowser {

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

    @Override
    public List<Item> browseItem(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        List<Item> result = new ArrayList<>();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return result;
        }

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getContext());

        // System audio stream
        if (preferences.getBoolean(getContext().getString(R.string.settings_local_server_serve_system_audio_chkbx), false)) {
            String streamUrl = "http://" + contentDirectory.getIpAddress() + ":"
                    + de.yaacc.upnp.server.YaaccUpnpServerService.PORT + "/live/audio";

            de.yaacc.util.YaaccLogger.i(getClass().getName(), "Creating live audio item with URL: " + streamUrl);

            MimeType mimeType = MimeType.valueOf("audio/wav");
            
            Item audioItem = createItem(
                ContentDirectoryIDs.LIVE_STREAM_SYSTEM_AUDIO.getId(),
                ContentDirectoryIDs.LIVE_STREAM_FOLDER.getId(),
                preferences.getString(getContext().getString(R.string.settings_local_server_name), "YAACC") + " " + getContext().getString(R.string.system_audio_stream),
                "yaacc",
                false,
                mimeType,
                streamUrl,
                null,
                null
            );
            
            // Add custom audio properties
            if (audioItem.getResources().size() > 0) {
                Res res = audioItem.getResources().get(0);
                res.setSampleFrequency(44100L);
                res.setNrAudioChannels(2L);
                res.setBitsPerSample(16L);
            }

            de.yaacc.util.YaaccLogger.i(getClass().getName(), "AudioItem created with URL: " + streamUrl);

            result.add(audioItem);
        }

        // Screen cast stream
        if (preferences.getBoolean(getContext().getString(R.string.settings_local_server_serve_screen_cast_chkbx), false)) {
            String streamUrl = "http://" + contentDirectory.getIpAddress() + ":"
                    + de.yaacc.upnp.server.YaaccUpnpServerService.PORT + "/live/video";

            de.yaacc.util.YaaccLogger.i(getClass().getName(), "Creating live video item with URL: " + streamUrl);

            MimeType mimeType = MimeType.valueOf("video/mpeg");
            
            Item videoItem = createItem(
                ContentDirectoryIDs.LIVE_STREAM_SCREEN_CAST.getId(),
                ContentDirectoryIDs.LIVE_STREAM_FOLDER.getId(),
                preferences.getString(getContext().getString(R.string.settings_local_server_name), "YAACC") + " " + getContext().getString(R.string.screen_cast_stream),
                "yaacc",
                false,
                mimeType,
                streamUrl,
                null,
                null
            );
            
            // Add custom video properties
            if (videoItem.getResources().size() > 0) {
                Res res = videoItem.getResources().get(0);
                res.setResolution("1280x720");
            }

            de.yaacc.util.YaaccLogger.i(getClass().getName(), "VideoItem created with URL: " + streamUrl);

            result.add(videoItem);
        }
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
