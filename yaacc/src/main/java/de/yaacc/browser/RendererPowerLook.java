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

import de.yaacc.R;

/**
 * How the power button of a card looks and what it says (task 12.4), decided without views: shown only when the device supports power,
 * dimmed like the other toggle buttons of the cards (full when the device is on, half otherwise; the repeat and shuffle buttons use
 * the same 1 and 0.5, a disabled button 0.4), disabled while a change runs, and the state in words for accessibility (the way the mute
 * symbol says "Muted"). Pure.
 */
final class RendererPowerLook {

    private final boolean visible;
    private final boolean enabled;
    private final float alpha;
    private final int stateText;

    private RendererPowerLook(boolean visible, boolean enabled, float alpha, int stateText) {
        this.visible = visible;
        this.enabled = enabled;
        this.alpha = alpha;
        this.stateText = stateText;
    }

    static RendererPowerLook of(RendererPower power, boolean busy) {
        if (power == null || !power.isSupported()) {
            return new RendererPowerLook(false, false, 1f, 0);
        }
        int text;
        float alpha;
        switch (power.state()) {
            case ON:
                text = R.string.power_state_on;
                alpha = 1f;
                break;
            case STANDBY:
                text = R.string.power_state_standby;
                alpha = 0.5f;
                break;
            case UNKNOWN:
            default:
                text = R.string.power_state_unknown;
                alpha = 0.5f;
                break;
        }
        return busy ? new RendererPowerLook(true, false, 0.4f, text) : new RendererPowerLook(true, true, alpha, text);
    }

    boolean visible() {
        return visible;
    }

    boolean enabled() {
        return enabled;
    }

    float alpha() {
        return alpha;
    }

    /** The string resource of the state ("On", "Standby", "State unknown"); 0 when the button is not shown. */
    int stateText() {
        return stateText;
    }

    /** The string resource that tells why the power was not changed; 0 for no error. */
    static int errorText(RendererPowerToggle.Error error) {
        if (error == null) {
            return 0;
        }
        switch (error) {
            case UNREACHABLE:
                return R.string.power_error_unreachable;
            case UNKNOWN_STATE:
                return R.string.power_error_unknown_state;
            case REFUSED:
                return R.string.power_error_refused;
            case NONE:
            default:
                return 0;
        }
    }
}
