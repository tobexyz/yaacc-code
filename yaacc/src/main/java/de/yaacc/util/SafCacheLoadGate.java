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
package de.yaacc.util;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Opens once the stored SAF cache is loaded. A caller on the main thread never waits (it gets "not loaded" and
 * works without the stored state); any other thread waits up to a limit. No Android classes: the caller says
 * whether it is the main thread.
 */
final class SafCacheLoadGate {

    private final CountDownLatch latch = new CountDownLatch(1);

    void open() {
        latch.countDown();
    }

    boolean isOpen() {
        return latch.getCount() == 0;
    }

    /**
     * @return true if the stored state is loaded (after waiting, when allowed)
     */
    boolean await(boolean onMainThread, long timeoutMillis) {
        if (isOpen()) {
            return true;
        }
        if (onMainThread) {
            return false;
        }
        try {
            return latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
