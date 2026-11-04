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

package de.yaacc.share;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import de.yaacc.util.YaaccLogger;

/**
 * The duration of a picked audio or video file, read when its share is created so the receiver
 * and the player know how long the item is (a receiver shows 0:00:00 without it, and the next
 * item is started by it). One bounded call per provider; an unknown duration is -1. Never logs a
 * uri or a name.
 */
public final class ContentDuration {

    private static final String TAG = ContentDuration.class.getName();

    /** A longer duration is not believed (24 hours). */
    static final long MAX_MILLIS = 24L * 60L * 60L * 1000L;

    private static final BoundedCall CALLS = new BoundedCall(2, "pick-duration", ProviderCallGate.createDefault());

    private ContentDuration() {
    }

    /**
     * @param mimeType the accepted type of the file; only audio and video have a duration
     * @return the duration in milliseconds, or -1 when unknown, not audio or video, or the provider
     * did not answer in time
     */
    public static long read(Context context, String uriText, String mimeType) {
        if (mimeType == null || !(mimeType.startsWith("audio/") || mimeType.startsWith("video/"))) {
            return -1L;
        }
        try {
            Context app = context.getApplicationContext();
            String key = ProviderKeys.of(app.getPackageManager(), SharedContentPolicy.authorityOf(uriText));
            Long millis = CALLS.call(key, () -> extract(app, uriText), ShareLimits.PROVIDER_CALL_TIMEOUT_MILLIS, null);
            return millis == null ? -1L : millis;
        } catch (RuntimeException e) {
            YaaccLogger.w(TAG, "Could not read a duration (" + e.getClass().getSimpleName() + ")");
            return -1L;
        }
    }

    private static Long extract(Context context, String uriText) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, Uri.parse(uriText));
            return parseMillis(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        } catch (RuntimeException e) {
            return -1L;
        } finally {
            try {
                retriever.release();
            } catch (Exception e) {
                // nothing left to do (release() declares an IOException since API 29)
            }
        }
    }

    /** The text the retriever answers as milliseconds; -1 for null, junk, zero, negative or over 24 hours. */
    static long parseMillis(String text) {
        if (text == null || text.length() > 12) {
            return -1L;
        }
        try {
            long value = Long.parseLong(text.trim());
            return value > 0L && value <= MAX_MILLIS ? value : -1L;
        } catch (NumberFormatException e) {
            return -1L;
        }
    }
}
