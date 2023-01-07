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

public enum ContentDirectoryIDs {
    PARENT_OF_ROOT("-1"),
    ROOT("0"),
    IMAGES_FOLDER("100999"),
    IMAGES_BY_BUCKET_NAMES_FOLDER("200999"),
    IMAGES_BY_BUCKET_NAME_PREFIX("210999"),
    IMAGE_BY_BUCKET_PREFIX("220999"),
    IMAGES_ALL_FOLDER("300999"),
    IMAGE_ALL_PREFIX("310999"),
    VIDEOS_FOLDER("400999"),
    VIDEO_PREFIX("410999"),
    MUSIC_FOLDER("500999"),
    MUSIC_ALL_TITLES_FOLDER("600999"),
    MUSIC_ALL_TITLES_ITEM_PREFIX("610999"),
    MUSIC_GENRES_FOLDER("700999"),
    MUSIC_GENRE_PREFIX("710999"),
    MUSIC_GENRE_ITEM_PREFIX("720999"),
    MUSIC_ALBUMS_FOLDER("800999"),
    MUSIC_ALBUM_PREFIX("810999"),
    MUSIC_ALBUM_ITEM_PREFIX("820999"),
    MUSIC_ARTISTS_FOLDER("900999"),
    MUSIC_ARTIST_PREFIX("910999"),
    MUSIC_ARTIST_ITEM_PREFIX("920999");


    String id;

    ContentDirectoryIDs(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

}
