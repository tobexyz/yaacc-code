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
package de.yaacc.player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The one place that answers "can this item be played as audio by a player that reads and decodes on this phone" (11.11) and
 * "is it a picture or a video" (11.2, the receiver picker). Pure: strings in, booleans out; the callers only ask.
 *
 * <p><b>Playlist files</b> ({@code .m3u}, {@code .pls}, {@code .asx}; types {@code audio/x-mpegurl}, {@code audio/mpegurl},
 * {@code audio/x-scpls}, {@code audio/x-ms-asx}) are listed by servers as audio items but are lists of other files; ExoPlayer
 * fails on them with {@code ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED}. They are matched by type (compared without case, a
 * {@code ;} parameter is ignored) and by the extension of the resource address or of the title. <b>HLS stays playable</b>:
 * the types {@code application/vnd.apple.mpegurl} and {@code application/x-mpegURL} and an address that ends in
 * {@code .m3u8} (query and fragment ignored), or a title that ends in {@code .m3u8}, are never playlist files, even when a
 * server announces such a stream with the generic playlist type. The extension of the address decides; the title is only
 * looked at when the address has no extension (a title such as "Dance. M3U" does not drop a track whose address ends in
 * {@code .flac}).
 */
public final class PlayableCheck {

    private static final String[] PLAYLIST_TYPES = {
            "audio/x-mpegurl", "audio/mpegurl", "audio/x-scpls", "audio/x-ms-asx"};
    private static final String[] HLS_TYPES = {"application/vnd.apple.mpegurl", "application/x-mpegurl"};
    private static final String[] PLAYLIST_EXTENSIONS = {"m3u", "pls", "asx"};
    private static final String HLS_EXTENSION = "m3u8";

    private PlayableCheck() {
    }

    /**
     * @param mimeType    the type of the resource, may be null
     * @param resourceUrl the address of the resource, may be null (never logged by this class)
     * @param title       the title of the item, may be null
     * @return true for a playlist file that must not reach the player
     */
    public static boolean isPlaylistFile(String mimeType, String resourceUrl, String title) {
        String type = normalizedType(mimeType);
        String urlExtension = extensionOfUrl(resourceUrl);
        String titleExtension = extensionOfName(title);
        if (matches(type, HLS_TYPES) || HLS_EXTENSION.equals(urlExtension) || HLS_EXTENSION.equals(titleExtension)) {
            return false;
        }
        // the address is the better witness; the title (a name a person chose) only decides when the address has no extension
        String extension = urlExtension.isEmpty() ? titleExtension : urlExtension;
        return matches(type, PLAYLIST_TYPES) || matches(extension, PLAYLIST_EXTENSIONS);
    }

    /** As {@link #isPlaylistFile(String, String, String)} for an item of a queue. */
    public static boolean isPlaylistFile(PlayableItem item) {
        if (item == null) {
            return false;
        }
        return isPlaylistFile(item.getMimeType(), item.getUri() == null ? null : item.getUri().toString(), item.getTitle());
    }

    /** The items that can be played as audio: the list without its playlist files, in the same order (a new list, never null). */
    public static List<PlayableItem> withoutPlaylistFiles(List<PlayableItem> items) {
        List<PlayableItem> kept = new ArrayList<>();
        if (items == null) {
            return kept;
        }
        for (PlayableItem item : items) {
            if (!isPlaylistFile(item)) {
                kept.add(item);
            }
        }
        return kept;
    }

    /** True for a type that says image or video (the receivers that play music only can not take it). */
    public static boolean isImageOrVideo(String mimeType) {
        String type = normalizedType(mimeType);
        return type.startsWith("image/") || type.startsWith("video/");
    }

    /** Lower case, trimmed, without the parameters after a ';'; empty for null. */
    private static String normalizedType(String mimeType) {
        if (mimeType == null) {
            return "";
        }
        String type = mimeType;
        int parameters = type.indexOf(';');
        if (parameters >= 0) {
            type = type.substring(0, parameters);
        }
        return type.trim().toLowerCase(Locale.ROOT);
    }

    /** The extension of the last path segment of an address, query and fragment cut off; lower case, empty when there is none. */
    private static String extensionOfUrl(String url) {
        if (url == null) {
            return "";
        }
        String path = url;
        int cut = path.indexOf('?');
        if (cut >= 0) {
            path = path.substring(0, cut);
        }
        cut = path.indexOf('#');
        if (cut >= 0) {
            path = path.substring(0, cut);
        }
        int slash = path.lastIndexOf('/');
        return extensionOfName(slash >= 0 ? path.substring(slash + 1) : path);
    }

    /** The text after the last dot of a name; empty when there is no dot or nothing behind it. */
    private static String extensionOfName(String name) {
        if (name == null) {
            return "";
        }
        String trimmed = name.trim();
        int dot = trimmed.lastIndexOf('.');
        if (dot < 0 || dot == trimmed.length() - 1) {
            return "";
        }
        return trimmed.substring(dot + 1).trim().toLowerCase(Locale.ROOT);
    }

    private static boolean matches(String value, String[] candidates) {
        for (String candidate : candidates) {
            if (candidate.equals(value)) {
                return true;
            }
        }
        return false;
    }
}
