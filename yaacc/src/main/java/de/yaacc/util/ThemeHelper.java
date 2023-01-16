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
