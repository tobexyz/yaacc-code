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
package de.yaacc.upnp.server.openhome;

/**
 * The standby state of the app's own OpenHome renderer (the {@code Standby} variable of its Product service) as the cards and the
 * holder {@link OwnOpenHomeStandby} see it. {@link OhRendererCore} is the implementation; the seam keeps the holder testable.
 */
public interface OhStandby {
    /** @return true while the renderer is in standby (the value a control point reads with {@code Standby}) */
    boolean isStandby();

    /** What the {@code SetStandby} action does: standby stops the playback (the queue stays), awake only says so. */
    void setStandby(boolean standby);

    /** Tells {@code listener} (on whatever thread changed it) every time the value changed; the same listener twice is one. */
    void addStandbyListener(Runnable listener);

    void removeStandbyListener(Runnable listener);
}
