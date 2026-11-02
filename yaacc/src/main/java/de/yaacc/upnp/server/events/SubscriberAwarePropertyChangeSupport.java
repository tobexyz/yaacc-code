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
package de.yaacc.upnp.server.events;

import org.fourthline.cling.model.DefaultServiceManager;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.function.IntConsumer;

/**
 * The {@link PropertyChangeSupport} a local service hands to its service manager: the GENA subscriptions listen at
 * it ({@code LocalGENASubscription} adds itself when it is registered and removes itself when it ends). This one
 * tells the service when the first subscriber comes and the last one goes, so a service can watch a source of
 * changes (the system volume) exactly as long as somebody wants the events.
 */
public class SubscriberAwarePropertyChangeSupport extends PropertyChangeSupport {

    private volatile IntConsumer subscriberCountListener;

    public SubscriberAwarePropertyChangeSupport(Object sourceBean) {
        super(sourceBean);
    }

    /**
     * @param listener called with the number of subscribers after each change of it; {@code null} for none. Called
     *                 on the thread that added or removed the subscriber, possibly while it holds its own monitor:
     *                 it must not take a lock of the service manager.
     */
    public void setSubscriberCountListener(IntConsumer listener) {
        this.subscriberCountListener = listener;
    }

    /** @return true for the listener the service manager registers for the plain state variables of its service */
    public static boolean isManagerListener(PropertyChangeListener listener) {
        return listener.getClass().getEnclosingClass() == DefaultServiceManager.class;
    }

    /** @return the listeners that are subscribers, not the one of the service manager itself */
    public int getSubscriberCount() {
        int count = 0;
        for (PropertyChangeListener listener : getPropertyChangeListeners()) {
            if (!isManagerListener(listener)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public void addPropertyChangeListener(PropertyChangeListener listener) {
        super.addPropertyChangeListener(listener);
        subscribersChanged();
    }

    @Override
    public void removePropertyChangeListener(PropertyChangeListener listener) {
        super.removePropertyChangeListener(listener);
        subscribersChanged();
    }

    private void subscribersChanged() {
        IntConsumer listener = subscriberCountListener;
        if (listener != null) {
            listener.accept(getSubscriberCount());
        }
    }
}
