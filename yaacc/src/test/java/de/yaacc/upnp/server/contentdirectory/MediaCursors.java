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
package de.yaacc.upnp.server.contentdirectory;

import static org.mockito.Mockito.mock;

import android.database.Cursor;

import org.mockito.Answers;

import java.util.Arrays;
import java.util.List;

/** A cursor over fixed rows for the tests of the browsers (the stub cursors of the platform do nothing). */
final class MediaCursors {
    private MediaCursors() {
    }

    static Cursor of(List<String> columns, Object[][] rows) {
        int[] position = {-1};
        return mock(Cursor.class, invocation -> {
            Object[] args = invocation.getArguments();
            switch (invocation.getMethod().getName()) {
                case "getCount":
                    return rows.length;
                case "getPosition":
                    return position[0];
                case "getColumnIndex":
                    return columns.indexOf(String.valueOf(args[0]));
                case "moveToFirst":
                    position[0] = 0;
                    return rows.length > 0;
                case "moveToNext":
                    position[0]++;
                    return position[0] < rows.length;
                case "moveToPosition":
                    position[0] = (Integer) args[0];
                    return position[0] >= 0 && position[0] < rows.length;
                case "isAfterLast":
                    return position[0] >= rows.length;
                case "isNull":
                    return rows[position[0]][(Integer) args[0]] == null;
                case "getString": {
                    Object value = rows[position[0]][(Integer) args[0]];
                    return value == null ? null : String.valueOf(value);
                }
                case "getLong": {
                    Object value = rows[position[0]][(Integer) args[0]];
                    return value == null ? 0L : Long.valueOf(String.valueOf(value));
                }
                case "getInt": {
                    Object value = rows[position[0]][(Integer) args[0]];
                    return value == null ? 0 : Integer.valueOf(String.valueOf(value));
                }
                default:
                    return Answers.RETURNS_DEFAULTS.answer(invocation);
            }
        });
    }

    static Cursor of(String[] columns, Object[]... rows) {
        return of(Arrays.asList(columns), rows);
    }
}
