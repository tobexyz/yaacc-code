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

import android.content.Context;
import android.webkit.MimeTypeMap;

import org.fourthline.cling.support.contentdirectory.DIDLParser;
import org.fourthline.cling.support.model.DIDLContent;
import org.fourthline.cling.support.model.item.Item;

import java.util.List;

import de.yaacc.player.AbstractPlayer;
import de.yaacc.player.PlayableItem;
import de.yaacc.player.Player;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.server.renderingcontrol.SystemVolume;
import de.yaacc.util.YaaccLogger;

/**
 * The Android side of the OpenHome renderer: the music player of this device and the system volume.
 */
public final class AndroidOpenHomeHost implements OpenHomeRenderer.Host, OpenHomeRenderer.Volume {

    private static final String DEFAULT_MIME_TYPE = "audio/mpeg";

    private final Context context;
    private final UpnpClient upnpClient;
    private final SystemVolume systemVolume;

    public AndroidOpenHomeHost(Context context, UpnpClient upnpClient) {
        this.context = context;
        this.upnpClient = upnpClient;
        this.systemVolume = new SystemVolume(context);
    }

    @Override
    public AbstractPlayer createPlayer(List<PlayableItem> items) {
        Player player = upnpClient.initializeLocalPlayer(items);
        return player instanceof AbstractPlayer ? (AbstractPlayer) player : null;
    }

    @Override
    public boolean isAlive(AbstractPlayer player) {
        return upnpClient.getCurrentPlayers().contains(player);
    }

    @Override
    public PlayableItem toPlayableItem(String uri, String metadata) {
        PlayableItem item = null;
        try {
            if (metadata != null && !metadata.isEmpty()) {
                DIDLContent content = new DIDLParser().parse(metadata);
                if (!content.getItems().isEmpty()) {
                    Item didl = content.getItems().get(0);
                    item = new PlayableItem(didl, upnpClient.getDefaultDuration());
                }
            }
        } catch (Exception e) {
            // a control point may send anything as metadata; the track plays by its address then
            YaaccLogger.d(getClass().getName(), "OpenHome track metadata not readable: " + e.getClass().getSimpleName());
        }
        if (item == null) {
            item = new PlayableItem();
            item.setTitle(uri);
            item.setDuration(upnpClient.getDefaultDuration());
        }
        // the address of the queue is the truth, whatever the metadata say
        item.setUri(android.net.Uri.parse(uri));
        // the renderer plays audio: whatever the metadata claim, a video or image type must not start another kind of player
        if (item.getMimeType() == null || !item.getMimeType().startsWith("audio/")) {
            String extension = MimeTypeMap.getFileExtensionFromUrl(uri);
            String mimeType = extension == null ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            item.setMimeType(mimeType != null && mimeType.startsWith("audio/") ? mimeType : DEFAULT_MIME_TYPE);
        }
        return item;
    }

    @Override
    public int percent() {
        return systemVolume.getPercent();
    }

    @Override
    public boolean muted() {
        return systemVolume.isMuted();
    }

    @Override
    public void setPercent(int percent) {
        systemVolume.setPercent(percent);
    }

    @Override
    public void setMuted(boolean muted) {
        systemVolume.setMuted(muted);
    }
}
