/*
 *
 * Copyright (C) 2014 Tobias Schoene www.yaacc.de
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

import org.fourthline.cling.support.model.DIDLObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The galleries of the videos and the images: one per bucket of the media store (a directory). Pure logic, so that it can be tested without a device.
 */
final class MediaGalleries {
    private MediaGalleries() {
    }

    /** One gallery: the bucket id, the name to show (unique among the galleries) and the number of files. */
    static final class Gallery {
        final String id;
        final String name;
        final int count;

        Gallery(String id, String name, int count) {
            this.id = id;
            this.name = name;
            this.count = count;
        }
    }

    /** Bucket ids are the hash of a path, an int, also negative: as numbers when they are numbers, else as text. */
    private static final Comparator<String> BUCKET_ID_ORDER = (a, b) -> {
        try {
            return Long.compare(Long.parseLong(a), Long.parseLong(b));
        } catch (NumberFormatException e) {
            return a.compareTo(b);
        }
    };

    /**
     * Groups the rows of the media store (one per image or video) to galleries.
     *
     * @param rows    {bucket id, bucket name} per file, the name may be null
     * @param unnamed the name of a bucket without one (the files in the root of a volume)
     * @return the galleries in the order the buckets were first seen; two galleries with the same name are told apart by a counter
     * (" (2)", " (3)") in the order of the bucket ids, so the names do not depend on the order of the rows
     */
    static List<Gallery> group(List<String[]> rows, String unnamed) {
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String[] row : rows) {
            String id = row[0];
            if (!names.containsKey(id)) {
                String name = row[1] == null || row[1].trim().isEmpty() ? unnamed : row[1];
                names.put(id, name);
            }
            counts.merge(id, 1, Integer::sum);
        }
        // the ids that share a name, in the order of the ids
        Map<String, List<String>> idsByName = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : names.entrySet()) {
            idsByName.computeIfAbsent(entry.getValue(), name -> new ArrayList<>()).add(entry.getKey());
        }
        Map<String, String> uniqueNames = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : idsByName.entrySet()) {
            List<String> ids = entry.getValue();
            Collections.sort(ids, BUCKET_ID_ORDER);
            for (int i = 0; i < ids.size(); i++) {
                uniqueNames.put(ids.get(i), i == 0 ? entry.getKey() : entry.getKey() + " (" + (i + 1) + ")");
            }
        }
        List<Gallery> galleries = new ArrayList<>();
        for (Map.Entry<String, String> entry : names.entrySet()) {
            galleries.add(new Gallery(entry.getKey(), uniqueNames.get(entry.getKey()), counts.get(entry.getKey())));
        }
        return galleries;
    }

    /** The key a list of folders is sorted by: the title without regard to the case. */
    static String titleKey(DIDLObject object) {
        return object.getTitle() == null ? "" : object.getTitle().toLowerCase(Locale.ROOT);
    }

    /**
     * The order of folders of the same title key: by title (upper case first), then by id, so that a sort is the same on every call
     * and a client that pages gets every folder once.
     */
    static Comparator<DIDLObject> inTitleOrder() {
        return Comparator.comparing(MediaGalleries::titleKey)
                .thenComparing(object -> object.getTitle() == null ? "" : object.getTitle())
                .thenComparing(DIDLObject::getId);
    }

    /** @return the part of the list a client asked for ({@code first} items skipped, at most {@code max} returned) */
    static <T> List<T> page(List<T> all, long first, long max) {
        int start = (int) Math.max(0, Math.min(first, all.size()));
        // no start + max: max is Long.MAX_VALUE when the client asked for everything
        int end = max >= all.size() - start ? all.size() : start + (int) Math.max(0, max);
        return new ArrayList<>(all.subList(start, end));
    }
}
