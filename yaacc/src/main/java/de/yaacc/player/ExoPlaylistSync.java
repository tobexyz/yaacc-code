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
package de.yaacc.player;

/**
 * When items added to a playing local music player may be appended to the playlist of ExoPlayer.
 * ExoPlayer holds the same list as the player (media item index = list index): only a list that is
 * in step with the player's list before the addition can simply be extended. Another size means the
 * playlist is not (yet) handed over, and the next start of an item builds it completely. Plain JVM.
 */
final class ExoPlaylistSync {

    private ExoPlaylistSync() {
    }

    /**
     * @param exoMediaItemCount the number of media items ExoPlayer holds now
     * @param listSizeBefore    the size of the player's list before the items were added
     */
    static boolean canAppend(int exoMediaItemCount, int listSizeBefore) {
        return exoMediaItemCount > 0 && exoMediaItemCount == listSizeBefore;
    }
}
