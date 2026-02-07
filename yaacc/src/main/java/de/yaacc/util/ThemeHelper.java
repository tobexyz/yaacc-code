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
package de.yaacc.util;

import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;

import androidx.core.graphics.drawable.DrawableCompat;

public class ThemeHelper {
    public static Drawable tintDrawable(Drawable in, Resources.Theme theme) {
        Drawable drawable = DrawableCompat.wrap(in);
        TypedValue typedValue = new TypedValue();
        theme.resolveAttribute(android.R.attr.colorForeground, typedValue, true);
        int color = typedValue.data;
        DrawableCompat.setTint(drawable, color);
        return drawable;
    }
}
