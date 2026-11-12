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
 * When the session holds the wake lock and the Wi-Fi lock, and when it ends for lack of audio. Security review W3 of
 * Group 8: the locks belong to <b>playing</b>. A server that accepts the connection and never plays (or one that drops the
 * connection every 11 seconds) must not keep the CPU and the radio awake for hours. So:
 * <ul>
 * <li>the locks are held while the engine plays, and for {@value #GRACE_MS} ms after the session starts or after the last
 * time it played (connecting, a short pause of the stream, a reconnect), then released;</li>
 * <li>they are taken again as soon as it plays again;</li>
 * <li>the wake lock is renewed every 30 minutes, only while the locks are held;</li>
 * <li>a session that has not played for {@value #NO_AUDIO_CEILING_MS} ms (15 minutes) ends, whatever the connection does.</li>
 * </ul>
 * Pure: the service calls {@link #update} on its watchdog tick and does what the decision says.
 */
public final class SessionPowerPolicy {

    public static final long GRACE_MS = 60_000L;
    public static final long NO_AUDIO_CEILING_MS = 15L * 60 * 1000;

    /** What to do now. */
    public static final class Decision {
        public final boolean holdLocks;
        public final boolean acquireNow;
        public final boolean releaseNow;
        public final boolean renewNow;
        public final boolean end;

        Decision(boolean holdLocks, boolean acquireNow, boolean releaseNow, boolean renewNow, boolean end) {
            this.holdLocks = holdLocks;
            this.acquireNow = acquireNow;
            this.releaseNow = releaseNow;
            this.renewNow = renewNow;
            this.end = end;
        }
    }

    private long lastPlayingMillis;
    private long lastAcquiredMillis;
    private boolean holding = true; // the service takes the locks when the session starts

    public SessionPowerPolicy(long startMillis) {
        lastPlayingMillis = startMillis;
        lastAcquiredMillis = startMillis;
    }

    public synchronized Decision update(long nowMillis, boolean playing) {
        if (playing) {
            lastPlayingMillis = nowMillis;
        }
        long idle = nowMillis - lastPlayingMillis;
        boolean end = idle >= NO_AUDIO_CEILING_MS;
        boolean hold = !end && (playing || idle < GRACE_MS);
        boolean acquire = hold && !holding;
        boolean release = !hold && holding;
        boolean renew = false;
        if (acquire) {
            lastAcquiredMillis = nowMillis;
        } else if (hold && LockPolicy.shouldRenewWakeLock(nowMillis, lastAcquiredMillis)) {
            renew = true;
            lastAcquiredMillis = nowMillis;
        }
        holding = hold;
        return new Decision(hold, acquire, release, renew, end);
    }
}
