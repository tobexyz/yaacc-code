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
package de.yaacc.sendspin.client;

import de.yaacc.sendspin.session.ClientHandshake;

/**
 * What the controller needs of the pairing records of this client: the PSKs the server may name in the handshake (the Pairing PSK of
 * the token this client shows, the long-term PSK of a server it is paired with) and the completion of a pairing.
 */
public interface ClientCredentials extends ClientHandshake.PskLookup {

    /** @return true if a Pairing PSK is held (the user asked for a token), so that a pairing activation can be answered */
    boolean holdsPairingPsk();

    /**
     * Keeps the long-term PSK of a server and uses up the Pairing PSK. @return false if the PSK is not fit to be kept (the Sentinel, or the
     * Pairing PSK itself)
     */
    boolean completePairing(String serverId, byte[] longTermPsk);

    /** The server unpaired this client: its long-term record is forgotten. */
    void unpair(String serverId);
}
