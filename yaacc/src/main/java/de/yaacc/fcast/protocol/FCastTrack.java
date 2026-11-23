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
package de.yaacc.fcast.protocol;

/** An audio, video or subtitle track of the item a v4 receiver plays (its {@code TracksAvailable}). Immutable; the texts are cleaned. */
public final class FCastTrack {
    /** What the track is. */
    public enum Type {
        VIDEO, AUDIO, SUBTITLE, UNKNOWN
    }

    private final long id;
    private final Type type;
    private final String language;
    private final String title;

    public FCastTrack(long id, Type type, String language, String title) {
        this.id = id;
        this.type = type;
        this.language = language == null ? "" : language;
        this.title = title == null ? "" : title;
    }

    /** The number the receiver uses to select the track. */
    public long getId() {
        return id;
    }

    public Type getType() {
        return type;
    }

    /** The ISO 639 language code as the receiver says it ("eng", "und"), may be empty. */
    public String getLanguage() {
        return language;
    }

    /** The title of the track, may be empty. */
    public String getTitle() {
        return title;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof FCastTrack)) {
            return false;
        }
        FCastTrack o = (FCastTrack) other;
        return id == o.id && type == o.type && language.equals(o.language) && title.equals(o.title);
    }

    @Override
    public int hashCode() {
        return (int) (id * 31 + type.hashCode()) * 31 + title.hashCode();
    }

    @Override
    public String toString() {
        return "FCastTrack{" + type + " " + id + " " + language + " " + title + "}";
    }
}
