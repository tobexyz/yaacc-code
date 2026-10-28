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
package de.yaacc.util;

/**
 * How the log viewer takes a freshly read log into the text it shows. Replacing the whole text of a
 * selectable view drops the user's selection and moves the scroll position, which made it impossible to
 * mark and copy an entry while the log was refreshed. New lines at the end are therefore appended.
 */
public final class LogDelta {

    /** What to do with the shown text. */
    public enum Kind { NONE, APPEND, REPLACE }

    private final Kind kind;
    private final String text;

    private LogDelta(Kind kind, String text) {
        this.kind = kind;
        this.text = text;
    }

    public Kind kind() {
        return kind;
    }

    /** The text to append for {@link Kind#APPEND}, the whole new text for {@link Kind#REPLACE}, else empty. */
    public String text() {
        return text;
    }

    /**
     * @param shown    what the view shows now, null before the first read
     * @param fresh    the log that was just read
     * @param selecting the user has text selected: nothing is changed then, the next read catches up
     */
    public static LogDelta between(String shown, String fresh, boolean selecting) {
        if (selecting || fresh == null || fresh.equals(shown)) {
            return new LogDelta(Kind.NONE, "");
        }
        if (shown != null && !shown.isEmpty() && fresh.startsWith(shown)) {
            return new LogDelta(Kind.APPEND, fresh.substring(shown.length()));
        }
        return new LogDelta(Kind.REPLACE, fresh);
    }
}
