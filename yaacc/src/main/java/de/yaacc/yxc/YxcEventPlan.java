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
package de.yaacc.yxc;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * What an event asks the monitor to read again. The events of a device are bursts of flags ("the play info
 * changed", "the group changed") that say nothing about the new value, so the monitor reads the matching call once
 * after the burst. Values that the event carries itself (power, volume, mute, input) are applied directly by
 * {@link YxcDeviceState#withEvent}. {@code play_time} ticks and {@code signal_info_updated} alone ask for nothing; a group change asks for the group and the zone (a client's input follows the link).
 */
public final class YxcEventPlan {

    /** The calls that can be asked for. */
    public enum Refresh {ZONE_STATUS, DIST_INFO, PLAY_INFO}

    private YxcEventPlan() {
    }

    public static Set<Refresh> refreshesFor(YxcEvent event) {
        if (event == null || !event.isParsable()) {
            return Collections.emptySet();
        }
        Set<Refresh> result = EnumSet.noneOf(Refresh.class);
        YxcEvent.ZoneBlock main = event.getZones().get(YxcDeviceState.MAIN_ZONE);
        if (main != null && (main.isStatusUpdated() || main.getPower() != null)) {
            // a power change brings new input, volume and the play info of the source with it
            result.add(Refresh.ZONE_STATUS);
        }
        if (main != null && main.getPower() != null) {
            result.add(Refresh.PLAY_INFO);
        }
        YxcEvent.NetUsbBlock netUsb = event.getNetUsb();
        if (netUsb != null && netUsb.isPlayInfoUpdated()) {
            result.add(Refresh.PLAY_INFO);
        }
        if (main != null && main.getInput() != null) {
            result.add(Refresh.PLAY_INFO);
        }
        if (event.isDistInfoUpdated()) {
            // a link or unlink also switches the input of a client to or from the link input
            result.add(Refresh.DIST_INFO);
            result.add(Refresh.ZONE_STATUS);
        }
        return result;
    }
}
