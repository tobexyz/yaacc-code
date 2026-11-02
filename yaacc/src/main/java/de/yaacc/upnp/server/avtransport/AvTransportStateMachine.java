/*
 *
 * Copyright (C) 2013 Tobias Schoene www.yaacc.de
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
package de.yaacc.upnp.server.avtransport;

import org.fourthline.cling.support.avtransport.impl.AVTransportStateMachine;
import org.seamless.statemachine.States;

import de.yaacc.player.Player;

/**
 * @author Tobias Schöne (openbit)
 */

@States({
        AvTransportMediaRendererNoMediaPresent.class,
        AvTransportMediaRendererStopped.class,
        AvTransportMediaRendererPlaying.class,
        AvTransportMediaRendererPaused.class
})
public interface AvTransportStateMachine extends AVTransportStateMachine {
    void syncPlay(String speed, String referencedPositionUnits, String referencedPosition, String referencedPresentationTime, String referencedClockId);

    void syncPause(String referencedPresentationTime, String referencedClockId);

    void syncStop(String referencedPresentationTime, String referencedClockId);

    /**
     * A player of the transport has stopped (the end of its playlist with repeat off, the stop button of the app, a
     * Stop of the transport itself), or, with {@code null}, the players of the transport are gone. Playing and
     * Paused go to Stopped when that means that nothing plays any more (see {@link AvTransport#playbackEnded}); the
     * other states do nothing. Not an action of a control point: {@code AvTransport} calls it, never under one of its locks.
     */
    void playerStopped(Player player);

}
