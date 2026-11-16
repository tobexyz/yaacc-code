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
package de.yaacc.browser;

import android.content.Context;
import android.view.View;
import android.widget.ImageButton;

import androidx.core.view.ViewCompat;

import de.yaacc.R;

/**
 * Binds the power button of a renderer card (task 12.4): the button is shown only for a device that supports power, it has a
 * description for accessibility ("Power: Kitchen") and a state text that follows the state ("On", "Standby", "State unknown"), the
 * way the mute symbol of the cards says "Muted". The look is decided by {@link RendererPowerLook}; the colour comes from the theme
 * (the drawable uses {@code colorControlNormal}). The same for every kind of renderer: a kind hands in its {@link RendererPower}
 * and what a tap does. Thin Android glue.
 */
final class RendererPowerButton {

    private RendererPowerButton() {
    }

    /**
     * @param name  the cleaned name of the device (display text), for the description
     * @param onTap what a tap does; not called while the button is disabled
     */
    static void bind(Context context, ImageButton button, String name, RendererPower power, boolean busy, Runnable onTap) {
        RendererPowerLook look = RendererPowerLook.of(power, busy);
        if (!look.visible()) {
            clear(button);
            return;
        }
        button.setVisibility(View.VISIBLE);
        button.setContentDescription(context.getString(R.string.power_button_description, name));
        ViewCompat.setStateDescription(button, context.getString(look.stateText()));
        button.setEnabled(look.enabled());
        button.setAlpha(look.alpha());
        button.setOnClickListener(look.enabled() ? v -> onTap.run() : null);
    }

    /** The card has no power (any more): a recycled card may have been a device that had. */
    static void clear(ImageButton button) {
        button.setVisibility(View.GONE);
        button.setOnClickListener(null);
        button.setContentDescription(null);
        ViewCompat.setStateDescription(button, null);
    }
}
