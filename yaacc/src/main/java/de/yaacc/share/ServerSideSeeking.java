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

import java.util.function.LongSupplier;

/**
 * The decision of the server-side time range management ("enable server-side time range
 * management in proxy mode"): some renderers cannot seek, they re-request the file with a
 * range that is wrong for the position they should be at. For an http(s) share and a renderer
 * that has the per-device setting on, the server replaces the requested start by the byte
 * position it estimates for the renderer's playback time. Plain Java; the caller owns the
 * network (the file size) and the preferences (the stored position).
 */
public final class ServerSideSeeking {

    private ServerSideSeeking() {
    }

    /**
     * Called for a request that carries a {@code Range} header.
     *
     * @param states        the share's renderer states
     * @param deviceId      the renderer's id from the URL (validated here; an invalid id changes nothing)
     * @param storedPosition supplies the position stored after the last seek, in milliseconds;
     *                      called only when the renderer has no state yet
     * @param totalSize     size of the file, &lt;= 0 if unknown
     * @return the byte position to start at (&gt; 0), or 0 to leave the requested range as it is
     */
    public static long plan(RendererStateMap states, String deviceId, LongSupplier storedPosition,
                            long totalSize) {
        if (states == null || storedPosition == null || !RendererIdPolicy.isValid(deviceId)) {
            return 0L;
        }
        RendererStateMap.State state = states.get(deviceId);
        if (state != null) {
            long position;
            if (state.isPaused()) {
                // paused: ask for the same place again
                position = state.getLastBytePosition();
            } else {
                if (state.getTotalDurationMs() <= 0L) {
                    state.setTotalDurationMs(SeekPositionCalculator.estimateDurationMs(totalSize));
                }
                position = SeekPositionCalculator.bytePositionForTime(totalSize,
                        state.getTimePositionMs(), state.getTotalDurationMs());
                if (position > 0L) {
                    state.setLastBytePosition(position);
                }
            }
            return Math.max(0L, position);
        }
        long stored = storedPosition.getAsLong();
        states.create(deviceId, stored);
        if (stored > 0L) {
            long duration = SeekPositionCalculator.estimateDurationMs(totalSize);
            if (duration > 0L) {
                return Math.max(0L, SeekPositionCalculator.bytePositionForTime(totalSize, stored, duration));
            }
        }
        return 0L;
    }
}
