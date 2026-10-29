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

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayDeque;

/**
 * Reads a log stream but keeps only its last lines, each cut to a maximum length, so that a large
 * logcat buffer (several MB, e.g. after a flood of stack traces) cannot freeze the log viewer:
 * memory, text comparison and text layout stay bounded. Plain JVM.
 */
public final class LogTail {

    private LogTail() {
    }

    /**
     * @param reader       the stream to read to its end
     * @param maxLines     the number of last lines to keep, at least 1
     * @param maxLineChars the longest line kept; longer lines end with an ellipsis
     * @return the kept lines, each followed by a line feed; empty if there was no line
     */
    public static String read(BufferedReader reader, int maxLines, int maxLineChars) throws IOException {
        ArrayDeque<String> lines = new ArrayDeque<>(Math.min(Math.max(maxLines, 1), 10_000));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.length() > maxLineChars) {
                line = line.substring(0, maxLineChars) + "…";
            }
            if (lines.size() >= Math.max(maxLines, 1)) {
                lines.removeFirst();
            }
            lines.addLast(line);
        }
        StringBuilder text = new StringBuilder();
        for (String kept : lines) {
            text.append(kept).append('\n');
        }
        return text.toString();
    }
}
