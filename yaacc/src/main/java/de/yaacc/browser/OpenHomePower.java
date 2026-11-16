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

import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.RemoteDevice;
import org.fourthline.cling.model.meta.Service;

import java.util.Collection;

import de.yaacc.openhome.OhControlException;
import de.yaacc.openhome.OhRemote;
import de.yaacc.upnp.callback.openhome.ClingOhControl;

/**
 * The power of an OpenHome card (task 12.4). <b>Capability:</b> the Product service of the device description has the actions
 * {@code Standby} and {@code SetStandby} (both are part of the OpenHome Product service; a device that lacks one gets no button).
 * Only devices on the network: the app's own renderer's standby is an app state, not a power state. <b>State:</b> the value of
 * {@code Standby} (true is standby), read by {@link OpenHomePowerMonitor}. <b>Change:</b> {@code SetStandby}, after a fresh
 * {@code Standby}.
 */
final class OpenHomePower {
    private static final String STANDBY = "Standby";
    private static final String SET_STANDBY = "SetStandby";

    private OpenHomePower() {
    }

    /** @param productActions the names of the actions of the Product service (null for none) */
    static boolean offers(Collection<String> productActions) {
        return productActions != null && productActions.contains(STANDBY) && productActions.contains(SET_STANDBY);
    }

    /** The device description of a device on the network offers power ({@link #offers} of its Product service). */
    static boolean offeredBy(Device<?, ?, ?> device) {
        if (!(device instanceof RemoteDevice)) {
            return false;
        }
        try {
            Service<?, ?> product = ClingOhControl.findService(device, "Product");
            return product != null && product.getAction(STANDBY) != null && product.getAction(SET_STANDBY) != null;
        } catch (RuntimeException odd) {
            return false; // called while a card is built: whatever is odd about a device, the list must still show
        }
    }

    /**
     * @param offered whether the device description offers power
     * @param standby the last value read; null when none was read (or the device did not answer)
     */
    static RendererPower of(boolean offered, Boolean standby) {
        if (!offered) {
            return RendererPower.unsupported();
        }
        if (standby == null) {
            return RendererPower.of(RendererPower.State.UNKNOWN);
        }
        return RendererPower.of(standby ? RendererPower.State.STANDBY : RendererPower.State.ON);
    }

    /** The calls of the toggle for one device. */
    static RendererPowerToggle.Source source(final OhRemote remote) {
        return new RendererPowerToggle.Source() {
            @Override
            public RendererPower.State read() throws RendererPowerToggle.PowerException {
                try {
                    return remote.standby() ? RendererPower.State.STANDBY : RendererPower.State.ON;
                } catch (OhControlException e) {
                    throw new RendererPowerToggle.PowerException(e);
                }
            }

            @Override
            public void set(boolean on) throws RendererPowerToggle.PowerException {
                try {
                    remote.setStandby(!on);
                } catch (OhControlException e) {
                    throw new RendererPowerToggle.PowerException(e);
                }
            }
        };
    }
}
