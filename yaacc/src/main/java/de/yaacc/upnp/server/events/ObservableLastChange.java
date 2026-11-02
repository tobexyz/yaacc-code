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

import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;
import org.fourthline.cling.support.lastchange.EventedValue;
import org.fourthline.cling.support.lastchange.LastChange;
import org.fourthline.cling.support.lastchange.LastChangeParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.yaacc.util.YaaccLogger;

/**
 * A {@link LastChange} that tells a listener whenever a value was put into it. Cling collects the changes of
 * the AVTransport and RenderingControl services in the LastChange but only sends them to the subscribers when
 * somebody calls {@code fire}; this is the hook that lets the service do that after every change, wherever it
 * comes from (a state of the state machine, a {@code SetPlayMode}, a button at a player, {@code SetMute}, the
 * volume keys).
 * <p>
 * <b>A value that equals the one put last is no change.</b> It is neither collected nor reported to the
 * listener, so a state that is entered again (Stop while stopped, Play while playing) or a volume callback that
 * finds the same volume does not cause an event. What was put last is also what every subscriber knows: it got
 * the state when it subscribed and every change since.
 * <p>
 * The listener is called while the LastChange is locked, so it must only schedule work and never block.
 */
public class ObservableLastChange extends LastChange {

    private volatile Runnable changeListener;
    // the last value put per instance and variable; guarded by this
    private final Map<Long, Map<Class<?>, String>> lastPut = new HashMap<>();

    public ObservableLastChange(LastChangeParser parser) {
        super(parser);
    }

    /**
     * @param listener called after values were put into this LastChange; {@code null} for none
     */
    public void setChangeListener(Runnable listener) {
        this.changeListener = listener;
    }

    @Override
    public synchronized void setEventedValue(UnsignedIntegerFourBytes instanceID, EventedValue... ev) {
        List<EventedValue> changed = new ArrayList<>();
        Map<Class<?>, String> known = lastPut.computeIfAbsent(instanceID.getValue(), id -> new HashMap<>());
        for (EventedValue value : ev) {
            if (value == null) {
                continue;
            }
            String fingerprint = fingerprint(value);
            if (fingerprint != null && fingerprint.equals(known.get(value.getClass()))) {
                continue; // what the subscribers already know
            }
            if (fingerprint != null) {
                known.put(value.getClass(), fingerprint);
            } else {
                known.remove(value.getClass());
            }
            changed.add(value);
        }
        if (changed.isEmpty()) {
            return;
        }
        super.setEventedValue(instanceID, changed.toArray(new EventedValue[0]));
        Runnable listener = changeListener;
        if (listener != null) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                // an event that is not sent must not break the action that caused it
                YaaccLogger.w(getClass().getName(), "LastChange listener failed", e);
            }
        }
    }

    /**
     * Forget which values were put last: the next value is a change again. For when collected changes were
     * dropped without being sent, so the subscribers do not know them.
     */
    public synchronized void forgetPutValues() {
        lastPut.clear();
    }

    private static String fingerprint(EventedValue<?> value) {
        try {
            StringBuilder text = new StringBuilder();
            for (Map.Entry<String, String> attribute : value.getAttributes()) {
                text.append(attribute.getKey()).append('=').append(attribute.getValue()).append(';');
            }
            return text.toString();
        } catch (RuntimeException e) {
            return null; // cannot be compared: it counts as a change
        }
    }
}
