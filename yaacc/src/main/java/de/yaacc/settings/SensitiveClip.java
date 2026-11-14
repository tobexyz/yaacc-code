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
package de.yaacc.settings;

import android.content.ClipData;
import android.content.ClipDescription;
import android.os.Build;
import android.os.PersistableBundle;

/**
 * A clip for text that names things of the user's network (the Snapcast reports: server names, addresses, the phone
 * model). From Android 13 on the clip carries {@link ClipDescription#EXTRA_IS_SENSITIVE}, so the system keeps its content
 * out of the paste preview; on older versions the flag does not exist and the clip is a plain one.
 */
final class SensitiveClip {

    private SensitiveClip() {
    }

    /** A plain text clip, marked as sensitive where the system knows the mark. */
    static ClipData plainText(CharSequence label, CharSequence text) {
        ClipData clip = ClipData.newPlainText(label, text);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        return clip;
    }
}
