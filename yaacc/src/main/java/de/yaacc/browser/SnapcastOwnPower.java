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

import java.util.concurrent.atomic.AtomicReference;

import de.yaacc.multiroom.client.SnapcastClientPower;

/**
 * The power of the phone's own Snapcast client on a card, as the one power model of the cards ({@link RendererPower},
 * {@link RendererPowerToggle.Source}; tasks 12.4 part 2 and 12.16). On while the client has a session (it starts, plays or reconnects),
 * off otherwise, never "unknown"; a tap on an off card switches the client on (the same path as the switch on the first page), a tap on
 * an on card switches it off, which stops the session: that is the disconnect (a Snapcast client of another device can not be
 * disconnected by the protocol, so no other card has this button). A refusal of the start is kept for the toast with its own reason
 * ({@code SnapcastClientPowerTexts}), the toggle reports it as "refused". Pure (the client is a {@link Switch}).
 */
final class SnapcastOwnPower {

    /** What the card needs of the phone's own client ({@code SnapcastClientPower} in the app). */
    interface Switch {
        boolean isPoweredOn();

        SnapcastClientPower.Outcome setOn(boolean on);
    }

    private SnapcastOwnPower() {
    }

    /** @param offered the card is the one of the own client ({@code SnapcastOwnClient#cardOffersPower}); no other card has power */
    static RendererPower of(boolean offered, boolean poweredOn) {
        if (!offered) {
            return RendererPower.unsupported();
        }
        return RendererPower.of(poweredOn ? RendererPower.State.ON : RendererPower.State.STANDBY);
    }

    /**
     * @param refusal receives the outcome of a start that was refused (cleared by every tap), for the text of the toast
     */
    static RendererPowerToggle.Source source(final Switch own, final AtomicReference<SnapcastClientPower.Outcome> refusal) {
        return new RendererPowerToggle.Source() {
            @Override
            public RendererPower.State read() {
                return own.isPoweredOn() ? RendererPower.State.ON : RendererPower.State.STANDBY;
            }

            @Override
            public void set(boolean on) throws RendererPowerToggle.PowerException {
                refusal.set(null);
                SnapcastClientPower.Outcome outcome = own.setOn(on);
                if (outcome.isRefused()) {
                    refusal.set(outcome);
                    throw new RendererPowerToggle.PowerException();
                }
            }
        };
    }
}
