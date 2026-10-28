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
        if (shown != null && !shown.isEmpty()) {
            if (fresh.startsWith(shown)) {
                return new LogDelta(Kind.APPEND, fresh.substring(shown.length()));
            }
            // The system keeps only a window of the log: once it is full every new line pushes the oldest
            // one out, so the new read no longer starts with the old one. The last lines of the old read tell
            // where the new lines begin.
            // (three lines are safer against a repeated line, but the window may have slid past them: fewer then)
            for (int lines = MARKER_LINES; lines >= 1; lines--) {
                String marker = lastLines(shown, lines);
                int at = marker.isEmpty() ? -1 : fresh.lastIndexOf(marker);
                if (at >= 0) {
                    String tail = fresh.substring(at + marker.length());
                    return tail.isEmpty() ? new LogDelta(Kind.NONE, "") : new LogDelta(Kind.APPEND, tail);
                }
            }
        }
        return new LogDelta(Kind.REPLACE, fresh);
    }

    /** Lines of the log that identify its end; every line starts with a time stamp, so they are unique in practice. */
    private static final int MARKER_LINES = 3;

    /** The last {@code count} complete lines of {@code text} (with their line breaks), or "" if it has none. */
    static String lastLines(String text, int count) {
        if (text.isEmpty()) {
            return "";
        }
        boolean endsWithBreak = text.endsWith("\n");
        String body = endsWithBreak ? text.substring(0, text.length() - 1) : text;
        String[] lines = body.split("\n", -1);
        StringBuilder result = new StringBuilder();
        for (int i = Math.max(0, lines.length - count); i < lines.length; i++) {
            result.append(lines[i]);
            if (i < lines.length - 1 || endsWithBreak) {
                result.append('\n');
            }
        }
        return result.toString();
    }
}
