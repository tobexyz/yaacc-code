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
package de.yaacc.browser;

import de.yaacc.upnp.server.openhome.OwnOpenHomeStandby;

/**
 * The power of the card of the app's own OpenHome renderer (task 12.13), as the one power model of the cards ({@link RendererPower},
 * {@link RendererPowerToggle.Source}). It is the {@code Standby} state of the renderer's Product service, the same one the power
 * switch of the OpenHome settings sheet shows (switch on = not in standby) and writes with {@code SetStandby}: the card shows it as
 * the card of a remote OpenHome device does ({@link OpenHomePower#of}), and a tap writes it the way the switch does (standby stops the
 * playback, the queue stays). Both read and write the renderer, so they always agree. Pure ({@link Switch} is the renderer).
 */
final class OwnOpenHomePower {

    /** What the card needs of the renderer ({@code OwnOpenHomeStandby} in the app). */
    interface Switch {
        /** @return true in standby, null when the renderer is not there */
        Boolean standby();

        /** @return false when the renderer is not there and nothing was written */
        boolean setStandby(boolean standby);
    }

    private OwnOpenHomePower() {
    }

    /** The renderer of the running service. */
    static Switch app() {
        final OwnOpenHomeStandby holder = OwnOpenHomeStandby.shared();
        return new Switch() {
            @Override
            public Boolean standby() {
                return holder.standby();
            }

            @Override
            public boolean setStandby(boolean standby) {
                return holder.setStandby(standby);
            }
        };
    }

    /**
     * @param localDevice     the card is the local device (a LocalDevice; the dummy device of "this phone" is not)
     * @param offersOpenHome  the device offers the OpenHome Product and Playlist services (the renderer is on in OpenHome mode)
     * @param rendererRunning the service built the renderer and handed it over
     */
    static boolean offered(boolean localDevice, boolean offersOpenHome, boolean rendererRunning) {
        return localDevice && offersOpenHome && rendererRunning;
    }

    /** @param standby true in standby, false on, null not known */
    static RendererPower of(boolean offered, Boolean standby) {
        return OpenHomePower.of(offered, standby);
    }

    /** The calls of the toggle: read the renderer, write it with the standby the action {@code SetStandby} takes. */
    static RendererPowerToggle.Source source(final Switch own) {
        return new RendererPowerToggle.Source() {
            @Override
            public RendererPower.State read() throws RendererPowerToggle.PowerException {
                Boolean standby = own.standby();
                if (standby == null) {
                    throw new RendererPowerToggle.PowerException();
                }
                return standby ? RendererPower.State.STANDBY : RendererPower.State.ON;
            }

            @Override
            public void set(boolean on) throws RendererPowerToggle.PowerException {
                if (!own.setStandby(!on)) {
                    throw new RendererPowerToggle.PowerException();
                }
            }
        };
    }
}
