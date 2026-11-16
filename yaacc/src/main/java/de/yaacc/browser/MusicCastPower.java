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

import java.util.Locale;

import de.yaacc.yxc.YxcClient;
import de.yaacc.yxc.YxcDeviceState;
import de.yaacc.yxc.YxcException;

/**
 * The power of a MusicCast (YXC) card (task 12.4). <b>Capability:</b> the main zone lists {@code power} in its function list in
 * {@code system/getFeatures} ({@link de.yaacc.yxc.YxcSourceCapabilities#hasPower}); a device that has not told its features yet, or
 * does not answer, has no button. <b>State:</b> the {@code power} word of the zone status ({@code on} or {@code standby}), kept
 * current by the monitor's events and 30 s read ({@link YxcDeviceState#getPower}). <b>Change:</b> {@code main/setPower?power=on} or
 * {@code power=standby} (docs/tech.md, section 7), after a fresh {@code main/getStatus}.
 */
final class MusicCastPower {

    private MusicCastPower() {
    }

    static RendererPower of(YxcDeviceState state) {
        if (state == null || !state.isReachable() || state.getCapabilities() == null || !state.getCapabilities().hasPower()) {
            return RendererPower.unsupported();
        }
        return RendererPower.of(stateOf(state.getPower()));
    }

    /** {@code on} is on, {@code standby} is standby, anything else (also a missing word) is not known. */
    static RendererPower.State stateOf(String word) {
        if (word == null) {
            return RendererPower.State.UNKNOWN;
        }
        String lower = word.trim().toLowerCase(Locale.ROOT);
        if (lower.equals("on")) {
            return RendererPower.State.ON;
        }
        if (lower.equals("standby")) {
            return RendererPower.State.STANDBY;
        }
        return RendererPower.State.UNKNOWN;
    }

    /** The calls of the toggle for the main zone of one device. */
    static RendererPowerToggle.Source source(final YxcClient client) {
        return new RendererPowerToggle.Source() {
            @Override
            public RendererPower.State read() throws RendererPowerToggle.PowerException {
                try {
                    return stateOf(client.getZoneStatus(YxcDeviceState.MAIN_ZONE).getPower());
                } catch (YxcException e) {
                    throw new RendererPowerToggle.PowerException(e);
                }
            }

            @Override
            public void set(boolean on) throws RendererPowerToggle.PowerException {
                try {
                    client.setPower(YxcDeviceState.MAIN_ZONE, on);
                } catch (YxcException e) {
                    throw new RendererPowerToggle.PowerException(e);
                }
            }
        };
    }
}
