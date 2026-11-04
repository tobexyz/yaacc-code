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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * What the user picked with the system picker (files or the files of a folder), split into the
 * queues that are played: one for each media type (audio, video, image), each sorted by name, at
 * most {@link #MAX_FILES} files over all queues (audio first, then video, then image). Everything
 * that is not audio, video or an image is skipped. Pure logic, no Android classes.
 */
public final class PickedQueue {

    /** The most files that are queued over all types (the number of files, not bytes). */
    public static final int MAX_FILES = 500;

    private PickedQueue() {
    }

    /** One picked file. Name and type are untrusted text of the provider and are cleaned here. */
    public static final class Entry {
        private final String uri;
        private final String displayName;
        private final String mimeType;
        private final long sizeBytes;

        /**
         * @param uri         the {@code content:} uri of the file
         * @param displayName the name the provider reported, cleaned by {@link DisplayName#clean}
         * @param mimeType    the type the provider reported, kept only if
         *                    {@link SharedContentPolicy#isAcceptableMimeType} accepts it (then lower case)
         * @param sizeBytes   the size, or 0 or less when unknown
         */
        public Entry(String uri, String displayName, String mimeType, long sizeBytes) {
            this.uri = uri;
            this.displayName = DisplayName.clean(displayName);
            this.mimeType = SharedContentPolicy.chooseMimeType(mimeType, null);
            this.sizeBytes = sizeBytes;
        }

        public String getUri() {
            return uri;
        }

        public String getDisplayName() {
            return displayName;
        }

        /** The accepted lower case type, or null when the provider's type is none of the three. */
        public String getMimeType() {
            return mimeType;
        }

        public long getSizeBytes() {
            return sizeBytes;
        }
    }

    /** The three queues and what was left out. */
    public static final class Result {
        private final List<Entry> audio;
        private final List<Entry> video;
        private final List<Entry> image;
        private final int skippedNotMedia;
        private final int skippedOverCap;

        Result(List<Entry> audio, List<Entry> video, List<Entry> image, int skippedNotMedia,
               int skippedOverCap) {
            this.audio = Collections.unmodifiableList(audio);
            this.video = Collections.unmodifiableList(video);
            this.image = Collections.unmodifiableList(image);
            this.skippedNotMedia = skippedNotMedia;
            this.skippedOverCap = skippedOverCap;
        }

        public List<Entry> audio() {
            return audio;
        }

        public List<Entry> video() {
            return video;
        }

        public List<Entry> image() {
            return image;
        }

        /** Files that are not audio, video or an image (or have no usable type). */
        public int skippedNotMedia() {
            return skippedNotMedia;
        }

        /** Media files left out because of {@link #MAX_FILES}. */
        public int skippedOverCap() {
            return skippedOverCap;
        }

        public int queuedCount() {
            return audio.size() + video.size() + image.size();
        }

        public boolean isEmpty() {
            return queuedCount() == 0;
        }
    }

    /**
     * @param entries the picked files, may be null
     * @param sort    true: each queue sorted by name (case-insensitive, numbers in numeric order);
     *                false: the order of the given list within each type
     */
    public static Result build(List<Entry> entries, boolean sort) {
        List<Entry> audio = new ArrayList<>();
        List<Entry> video = new ArrayList<>();
        List<Entry> image = new ArrayList<>();
        int notMedia = 0;
        if (entries != null) {
            for (Entry entry : entries) {
                String mime = entry == null ? null : entry.getMimeType();
                if (mime == null) {
                    notMedia++;
                } else if (mime.startsWith("audio/")) {
                    audio.add(entry);
                } else if (mime.startsWith("video/")) {
                    video.add(entry);
                } else {
                    image.add(entry);
                }
            }
        }
        if (sort) {
            Comparator<Entry> byName = (a, b) -> NaturalOrder.compare(a.getDisplayName(), b.getDisplayName());
            Collections.sort(audio, byName);
            Collections.sort(video, byName);
            Collections.sort(image, byName);
        }
        int room = MAX_FILES;
        int overCap = 0;
        overCap += cut(audio, room);
        room -= audio.size();
        overCap += cut(video, room);
        room -= video.size();
        overCap += cut(image, room);
        return new Result(audio, video, image, notMedia, overCap);
    }

    /** Cuts the list to {@code room} entries, returns how many were cut. */
    private static int cut(List<Entry> list, int room) {
        int keep = Math.max(room, 0);
        if (list.size() <= keep) {
            return 0;
        }
        int cut = list.size() - keep;
        list.subList(keep, list.size()).clear();
        return cut;
    }

    /** Case-insensitive order in which digit runs compare as numbers (track2 before track10). */
    static final class NaturalOrder {
        private NaturalOrder() {
        }

        static int compare(String a, String b) {
            String x = a.toLowerCase(Locale.ROOT);
            String y = b.toLowerCase(Locale.ROOT);
            int i = 0;
            int j = 0;
            while (i < x.length() && j < y.length()) {
                char c = x.charAt(i);
                char d = y.charAt(j);
                if (isDigit(c) && isDigit(d)) {
                    int iEnd = digitsEnd(x, i);
                    int jEnd = digitsEnd(y, j);
                    int result = compareNumbers(x.substring(i, iEnd), y.substring(j, jEnd));
                    if (result != 0) {
                        return result;
                    }
                    i = iEnd;
                    j = jEnd;
                } else {
                    if (c != d) {
                        return c < d ? -1 : 1;
                    }
                    i++;
                    j++;
                }
            }
            int rest = (x.length() - i) - (y.length() - j);
            if (rest != 0) {
                return rest < 0 ? -1 : 1;
            }
            return a.compareTo(b);
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private static int digitsEnd(String s, int from) {
            int end = from;
            while (end < s.length() && isDigit(s.charAt(end))) {
                end++;
            }
            return end;
        }

        /** Compares two digit runs by value without parsing them (no overflow). */
        private static int compareNumbers(String a, String b) {
            String x = stripZeros(a);
            String y = stripZeros(b);
            if (x.length() != y.length()) {
                return x.length() < y.length() ? -1 : 1;
            }
            return Integer.signum(x.compareTo(y));
        }

        private static String stripZeros(String s) {
            int start = 0;
            while (start < s.length() - 1 && s.charAt(start) == '0') {
                start++;
            }
            return s.substring(start);
        }
    }
}
