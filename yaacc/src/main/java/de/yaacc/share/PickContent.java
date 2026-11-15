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
package de.yaacc.share;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import de.yaacc.player.PlayableCheck;
import de.yaacc.player.PlayableItem;

/**
 * What the receiver picker ("Play on...") is told about the content that is about to be played, so that it does not offer a
 * receiver that can not play it (11.2). Today there is one such receiver kind: a Snapcast group plays music only, so it is not
 * offered when the content holds an image or a video. Content whose type is not known offers everything (the check at the
 * start of the playback stays as the safety net). Pure.
 */
public final class PickContent {

    /** Nothing known: every receiver is offered. */
    public static final PickContent UNKNOWN = new PickContent(true);

    private final boolean audioOnlyReceivers;

    private PickContent(boolean audioOnlyReceivers) {
        this.audioOnlyReceivers = audioOnlyReceivers;
    }

    /** False when the content holds an image or a video: a receiver that plays music only is not offered. */
    public boolean offersAudioOnlyReceivers() {
        return audioOnlyReceivers;
    }

    /** The content as a flag says (a picker that was restored from its arguments). */
    public static PickContent offering(boolean audioOnlyReceivers) {
        return audioOnlyReceivers ? UNKNOWN : new PickContent(false);
    }

    /** The content of these types (null and empty entries are unknown types and change nothing). */
    public static PickContent ofMimeTypes(Collection<String> mimeTypes) {
        if (mimeTypes != null) {
            for (String mimeType : mimeTypes) {
                if (PlayableCheck.isImageOrVideo(mimeType)) {
                    return new PickContent(false);
                }
            }
        }
        return UNKNOWN;
    }

    /** The content of a kind as {@code SameMediaKind} names it ("audio", "image", "video"); null is unknown. */
    public static PickContent ofKind(String kind) {
        return "image".equals(kind) || "video".equals(kind) ? new PickContent(false) : UNKNOWN;
    }

    /** The picked files: video and images make the content not music. */
    public static PickContent of(PickedQueue.Result queue) {
        if (queue == null) {
            return UNKNOWN;
        }
        return queue.video().isEmpty() && queue.image().isEmpty() ? UNKNOWN : new PickContent(false);
    }

    /** The items of a queue, by their types. */
    public static PickContent ofItems(Collection<PlayableItem> items) {
        List<String> types = new ArrayList<>();
        if (items != null) {
            for (PlayableItem item : items) {
                types.add(item == null ? null : item.getMimeType());
            }
        }
        return ofMimeTypes(types);
    }
}
