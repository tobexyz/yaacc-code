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
 * When the notification of the service is rebuilt. A rebuild costs binder calls on the main thread, and the events that
 * ask for one (every server settings message, every status) are driven by the server, so the rule is: <b>never when the
 * shown text would be the same</b>, at once for a change of phase (connecting, playing, reconnecting), and at most once per
 * second for any other change. A request that is held back is not remembered; the status asks again every 250 ms. Pure.
 */
public final class NotificationPolicy {

    public static final long MIN_INTERVAL_MS = 1000L;

    private Object lastKey;
    private long lastAtMillis = Long.MIN_VALUE / 2;

    /**
     * @param key         what would be shown (the text); equal keys are the same notification
     * @param phaseChange true when the request comes from a change of phase
     * @return true when the notification is to be rebuilt now (the policy then counts it as shown)
     */
    public synchronized boolean shouldRefresh(Object key, boolean phaseChange, long nowMillis) {
        if (key.equals(lastKey)) {
            return false;
        }
        if (!phaseChange && nowMillis - lastAtMillis < MIN_INTERVAL_MS) {
            return false;
        }
        lastKey = key;
        lastAtMillis = nowMillis;
        return true;
    }
}
