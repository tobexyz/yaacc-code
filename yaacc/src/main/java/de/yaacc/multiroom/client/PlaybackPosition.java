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
package de.yaacc.multiroom.client;

/**
 * Where the output is: {@code frames} frames (counted from the last flush) have been presented, or are committed to be
 * presented, at {@code presentedAtMicros} on the engine's clock. The sink converts whatever clock its platform uses to the
 * engine's clock before it reports.
 */
public final class PlaybackPosition {

    private final long frames;
    private final long presentedAtMicros;

    public PlaybackPosition(long frames, long presentedAtMicros) {
        this.frames = frames;
        this.presentedAtMicros = presentedAtMicros;
    }

    public long getFrames() {
        return frames;
    }

    public long getPresentedAtMicros() {
        return presentedAtMicros;
    }
}
