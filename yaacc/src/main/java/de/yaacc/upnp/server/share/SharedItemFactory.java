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
package de.yaacc.upnp.server.share;

import android.net.Uri;

import org.fourthline.cling.support.model.Res;
import org.fourthline.cling.support.model.item.ImageItem;
import org.fourthline.cling.support.model.item.Item;
import org.fourthline.cling.support.model.item.MusicTrack;
import org.fourthline.cling.support.model.item.VideoItem;
import org.seamless.util.MimeType;

import de.yaacc.player.PlayableItem;
import de.yaacc.util.FormatHelper;

/**
 * Builds the {@link PlayableItem} of a shared file or link: the DIDL item class follows the MIME
 * type (audio: music track, video: video item, image: image item). Plain object building, no I/O.
 */
public final class SharedItemFactory {

    private SharedItemFactory() {
    }

    /**
     * @param mimeType       the type; only {@code audio/...}, {@code video/...} and {@code image/...} are built
     * @param title          the (cleaned) title
     * @param uri            the URI the player fetches
     * @param sizeBytes      the size if known, otherwise 0 or less (then no size is announced)
     * @param durationMillis the duration if known, otherwise 0 or less (then none is announced)
     * @param bitrate        the bitrate if known, otherwise 0 or less
     * @return the item, or null if the type is none of the three or cannot be parsed
     */
    public static PlayableItem create(String mimeType, String title, Uri uri, long sizeBytes,
                                      long durationMillis, long bitrate) {
        if (mimeType == null || uri == null) {
            return null;
        }
        String url = uri.toString();
        MimeType parsed;
        try {
            parsed = MimeType.valueOf(mimeType);
        } catch (RuntimeException e) {
            return null;
        }
        Long size = sizeBytes > 0L ? sizeBytes : null;
        Res res;
        if (durationMillis > 0L) {
            res = new Res(parsed, size, FormatHelper.parseMillisToTimeStringTo(durationMillis),
                    bitrate > 0L ? bitrate : null, url);
        } else {
            res = new Res(parsed, size, url);
        }
        Item didl;
        if (mimeType.startsWith("audio/")) {
            didl = new MusicTrack("1", "2", title, "", "", "", res);
        } else if (mimeType.startsWith("video/")) {
            didl = new VideoItem("1", "2", title, "", res);
        } else if (mimeType.startsWith("image/")) {
            didl = new ImageItem("1", "2", title, "", res);
        } else {
            return null;
        }
        PlayableItem item = new PlayableItem();
        item.setItem(didl);
        item.setMimeType(mimeType);
        item.setTitle(title);
        item.setUri(uri);
        item.setDuration(Math.max(durationMillis, 0L));
        return item;
    }
}
