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
package de.yaacc.upnp.server.openhome;

import de.yaacc.multiroom.SnapcastRendererSources;

/**
 * Where the Product service of the renderer gets its sources from and where a choice goes (task 12.5): the app's side of the
 * source list. The renderer core itself knows nothing about Snapcast; it asks for a snapshot and hands a chosen source on.
 * Both are called on any thread (a control point's request, the preference listener); an implementation is thread safe and never
 * blocks on the network.
 */
public interface OhSourceProvider {

    /** The list as it is now (never null). May throw: the core then keeps what it told the controllers last. */
    SnapcastRendererSources.Snapshot snapshot();

    /**
     * Makes this source the output of the renderer.
     *
     * @param entry a source of an earlier snapshot; it is checked against the facts of now
     * @return true when the output is this source now (also when it already was); false when it was refused (the old output
     * stays, the user was told)
     */
    boolean select(SnapcastRendererSources.Entry entry);
}
