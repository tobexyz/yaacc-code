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
package de.yaacc.upnp.server;

import android.content.pm.PackageManager;

/**
 * The state of the boot receiver {@link ServerAutostart} that the setting "autostart on boot" stands for: ticked = the component is
 * enabled (the system delivers BOOT_COMPLETED to it, so the process is created at boot and the normal startup route runs), not
 * ticked = disabled (nothing starts at boot). The state is always explicit, never the manifest default.
 */
public final class AutostartReceiverState {

    private AutostartReceiverState() {
    }

    /** @return a {@link PackageManager} component state: ENABLED when the setting is ticked, else DISABLED */
    public static int componentState(boolean autostartTicked) {
        return autostartTicked
                ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }
}
