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

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * The {@link ExitRestartGuard} of the app, with its mark in a preferences file of its own (not the settings: it is no setting).
 */
public final class ExitRestartGuards {

    private static final String FILE = "yaacc_exit_guard";
    private static final String KEY_EXIT_REQUESTED_AT = "exit_requested_at";

    private ExitRestartGuards() {
    }

    public static ExitRestartGuard of(Context context) {
        final SharedPreferences preferences = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        return new ExitRestartGuard(new ExitRestartGuard.Store() {
            @Override
            public long readExitRequestedAt() {
                return preferences.getLong(KEY_EXIT_REQUESTED_AT, 0L);
            }

            @Override
            @SuppressLint("ApplySharedPref") // commit on purpose: the process ends right after the mark is written
            public void writeExitRequestedAt(long millis) {
                SharedPreferences.Editor editor = preferences.edit();
                if (millis <= 0L) {
                    editor.remove(KEY_EXIT_REQUESTED_AT);
                } else {
                    editor.putLong(KEY_EXIT_REQUESTED_AT, millis);
                }
                if (!editor.commit()) {
                    throw new IllegalStateException("could not write the exit mark");
                }
            }
        }, System::currentTimeMillis);
    }
}
