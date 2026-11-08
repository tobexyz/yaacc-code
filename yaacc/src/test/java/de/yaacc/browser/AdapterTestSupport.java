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
package de.yaacc.browser;

import androidx.recyclerview.widget.RecyclerView;

import java.lang.reflect.Field;
import java.util.ArrayList;

/**
 * The platform stub of {@code android.database.Observable} (JVM unit tests) has no observer list, so every {@code notify...} of an
 * adapter fails with a NullPointerException. This gives an adapter the list, so that the logic around the notifications can be tested.
 */
final class AdapterTestSupport {
    private AdapterTestSupport() {
    }

    static void giveObserverList(RecyclerView.Adapter<?> adapter) throws Exception {
        Field observable = RecyclerView.Adapter.class.getDeclaredField("mObservable");
        observable.setAccessible(true);
        Field observers = android.database.Observable.class.getDeclaredField("mObservers");
        observers.setAccessible(true);
        observers.set(observable.get(adapter), new ArrayList<>());
    }
}
