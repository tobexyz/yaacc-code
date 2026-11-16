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

import de.yaacc.multiroom.client.SnapcastClientSpike;

/**
 * Which part of the settings screen the gear on the first page opens: the screen is one scrolling list, so "directly" means scrolled
 * to the first entry of the Snapcast client section. The request comes as an extra of the intent; only the keys listed here are
 * accepted, anything else is ignored (the activity is not exported, but the value is checked all the same). Pure.
 */
public final class SnapcastSettingsTarget {

    /** The extra of the intent that opens the settings. */
    public static final String EXTRA_SCROLL_TO = "de.yaacc.settings.SCROLL_TO";

    /** The checkbox of the client: the first entry of its section that is shown while the option is on. */
    public static final String SNAPCAST_CLIENT = SnapcastClientSpike.KEY_SWITCHED_ON;

    private SnapcastSettingsTarget() {
    }

    /** @return the key of the entry to scroll to; null for no request or a key that is not allowed */
    public static String scrollKey(String requested) {
        return SNAPCAST_CLIENT.equals(requested) ? SNAPCAST_CLIENT : null;
    }
}
