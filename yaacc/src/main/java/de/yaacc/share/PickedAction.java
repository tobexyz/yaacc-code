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
 * What a picked action does with the answer of the receiver picker (see {@link GuardedPickedAction}
 * and {@code BrowserHost#pickReceivers}). Called on the main thread.
 */
public interface PickedAction {

    /**
     * The user confirmed. The action owns the single flight now: it MUST call {@code done} exactly
     * when it no longer needs the stored receiver selection (started, failed or refused); calling it
     * again is harmless.
     *
     * @param receiverIds the chosen receivers (never empty; unmodifiable)
     */
    void onConfirmed(Set<String> receiverIds, Runnable done);

    /** The picker was dismissed, abandoned, or the action was refused because another one is in flight. */
    void onCancelled();
}
