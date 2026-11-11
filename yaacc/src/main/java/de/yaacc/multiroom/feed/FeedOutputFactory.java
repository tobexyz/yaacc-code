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
package de.yaacc.multiroom.feed;

import java.io.IOException;
import java.net.InetAddress;

/** Opens the feed to a stream port. */
public interface FeedOutputFactory {

    /**
     * Connects to exactly this address and port. Blocking.
     *
     * @param address  the peer address of the control connection (see {@link FeedServer#peerAddress()}), nothing else
     * @param port     the port that YAACC asked for
     * @param onFailed told once if the feed fails later (not when it is closed); called on a thread of the feed
     * @throws IOException when the connection can not be made (without the address in the text)
     */
    FeedOutput open(InetAddress address, int port, Runnable onFailed) throws IOException;
}
