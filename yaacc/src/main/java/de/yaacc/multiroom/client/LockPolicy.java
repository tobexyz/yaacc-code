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
 * Which locks the service takes and when it renews them. Pure, so the decisions are tested; the service only calls the
 * platform.
 *
 * <p><b>Wi-Fi.</b> {@code WIFI_MODE_FULL_HIGH_PERF} (3) keeps Wi-Fi out of power save also with the screen off. The
 * platform replaced it from Android 14 (API 34) by {@code WIFI_MODE_FULL_LOW_LATENCY} (4), which per its documentation is
 * active only while the screen is on and the app is in the foreground. So up to API 33 the high-performance lock is the
 * one that does what a screen-off session needs; from API 34 no lock exists that holds Wi-Fi out of power save with the
 * screen off, and the session depends on the phone's own behaviour (the foreground service, the partial wake lock).
 *
 * <p><b>Wake lock.</b> Taken with a safety timeout (so a bug can not hold the CPU for ever); a session may be longer than
 * that, so the lock is renewed while the session runs.
 */
public final class LockPolicy {

    /** The values of {@code WifiManager.WIFI_MODE_FULL_HIGH_PERF} and {@code WIFI_MODE_FULL_LOW_LATENCY}. */
    public static final int WIFI_MODE_FULL_HIGH_PERF = 3;
    public static final int WIFI_MODE_FULL_LOW_LATENCY = 4;

    public static final long WAKE_LOCK_SAFETY_TIMEOUT_MS = 8L * 60 * 60 * 1000;
    public static final long WAKE_LOCK_RENEW_INTERVAL_MS = 30L * 60 * 1000;

    private LockPolicy() {
    }

    public static int wifiMode(int sdkInt) {
        return sdkInt >= 34 ? WIFI_MODE_FULL_LOW_LATENCY : WIFI_MODE_FULL_HIGH_PERF;
    }

    /** False when the Wi-Fi lock of this version is not active with the screen off. */
    public static boolean wifiLockWorksWithScreenOff(int sdkInt) {
        return sdkInt < 34;
    }

    public static boolean shouldRenewWakeLock(long nowMillis, long lastRenewMillis) {
        return nowMillis - lastRenewMillis >= WAKE_LOCK_RENEW_INTERVAL_MS;
    }
}
