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

import static org.junit.Assert.assertEquals;

import android.content.pm.PackageManager;

import org.junit.Test;

/**
 * The boot receiver is switched by the setting "autostart on boot" (task 12.18): ticked = the component is enabled (the system
 * delivers BOOT_COMPLETED to it), not ticked = disabled (nothing is started at boot). Plain JVM table test.
 */
public class AutostartReceiverStateTest {

    @Test
    public void tickedEnablesTheReceiver() {
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, AutostartReceiverState.componentState(true));
    }

    @Test
    public void notTickedDisablesTheReceiver() {
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, AutostartReceiverState.componentState(false));
    }

    @Test
    public void theStateIsNeverTheDefaultOrTheDisabledUserState() {
        // the state is always explicit: DEFAULT would fall back to the manifest, which is disabled
        for (boolean ticked : new boolean[]{true, false}) {
            int state = AutostartReceiverState.componentState(ticked);
            assertEquals(ticked, state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
            assertEquals(!ticked, state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
        }
    }
}
