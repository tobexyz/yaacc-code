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
package de.yaacc.upnp.server.openhome;

import java.beans.PropertyChangeSupport;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The values of the state variables of one OpenHome service, as the texts the control points see. It is the "service
 * implementation" the Cling service manager holds: the manager reads the values from here for the events and tells the
 * subscribers when {@link #set} changes one (one event for all variables of one {@link #setAll} call).
 */
public final class OhServiceState {
    private final PropertyChangeSupport propertyChangeSupport = new PropertyChangeSupport(this);
    private final Map<String, String> values = new LinkedHashMap<>();

    /** Drops the listeners of earlier service managers: the services are built again when the device is. */
    public void clearListeners() {
        for (java.beans.PropertyChangeListener listener : propertyChangeSupport.getPropertyChangeListeners()) {
            propertyChangeSupport.removePropertyChangeListener(listener);
        }
    }

    /** The manager looks for this getter. */
    public PropertyChangeSupport getPropertyChangeSupport() {
        return propertyChangeSupport;
    }

    public synchronized String get(String variable) {
        String value = values.get(variable);
        return value == null ? "" : value;
    }

    /** Sets a value without an event (the initial values). */
    public synchronized void init(String variable, String value) {
        values.put(variable, value == null ? "" : value);
    }

    /** @return whether the value changed (and the subscribers were told) */
    public boolean set(String variable, String value) {
        return setAll(java.util.Collections.singletonMap(variable, value));
    }

    /** Sets several values; one event names all the variables that changed. */
    public boolean setAll(Map<String, String> changes) {
        StringBuilder changed = new StringBuilder();
        synchronized (this) {
            for (Map.Entry<String, String> change : changes.entrySet()) {
                String value = change.getValue() == null ? "" : change.getValue();
                if (!value.equals(values.get(change.getKey()))) {
                    values.put(change.getKey(), value);
                    if (changed.length() > 0) {
                        changed.append(',');
                    }
                    changed.append(change.getKey());
                }
            }
        }
        if (changed.length() == 0) {
            return false;
        }
        // outside the lock: the manager reads the values back
        propertyChangeSupport.firePropertyChange(changed.toString(), null, null);
        return true;
    }
}
