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
package de.yaacc.share;

import java.util.Set;

/**
 * Adapts a {@link PickedAction} to {@link PendingPick.Callbacks} and puts it under the single
 * {@link ActionFlight}: a confirm begins the flight, hands the action a {@code done} callback that
 * ends it, and an action that throws ends it too. A confirm while another action is in flight is
 * refused: the action only hears {@link PickedAction#onCancelled()} and nothing was stored.
 */
public final class GuardedPickedAction implements PendingPick.Callbacks {

    private final ActionFlight flight;
    private final PickedAction action;

    public GuardedPickedAction(ActionFlight flight, PickedAction action) {
        if (flight == null || action == null) {
            throw new IllegalArgumentException("a flight and an action are required");
        }
        this.flight = flight;
        this.action = action;
    }

    @Override
    public void onConfirmed(Set<String> receiverIds) {
        ActionFlight.Ticket ticket = flight.tryBegin();
        if (ticket == null) {
            action.onCancelled();
            return;
        }
        try {
            action.onConfirmed(receiverIds, ticket::end);
        } catch (RuntimeException e) {
            ticket.end();
            throw e;
        }
    }

    @Override
    public void onCancelled() {
        action.onCancelled();
    }
}
