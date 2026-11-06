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
package de.yaacc.openhome;

/**
 * The volume of the OpenHome volume service is a number from 0 to {@code VolumeMax} (the device says how many steps it has), YAACC
 * uses percent. The renderer is the phone's music stream: 100 steps, so the number is the percent.
 */
public final class OhVolume {
    /** What the renderer reports: {@code VolumeMax}, {@code VolumeUnity} and {@code VolumeSteps}. */
    public static final long RENDERER_MAX = 100;

    private OhVolume() {
    }

    /** @return the volume as percent of the device's scale, 0 to 100; a missing or zero maximum counts as 100 */
    public static int toPercent(long value, long max) {
        long m = max <= 0 ? 100 : max;
        long v = Math.max(0, Math.min(value, m));
        return (int) Math.round(v * 100.0 / m);
    }

    /** @return the device's volume step for the percent (0 to 100) */
    public static long fromPercent(int percent, long max) {
        long m = max <= 0 ? 100 : max;
        long p = Math.max(0, Math.min(percent, 100));
        return Math.round(p * m / 100.0);
    }
}
