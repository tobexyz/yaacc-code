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
package de.yaacc.upnp.server.avtransport;

import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;
import org.fourthline.cling.support.lastchange.EventedValue;
import org.fourthline.cling.support.lastchange.LastChange;
import org.fourthline.cling.support.lastchange.LastChangeParser;

import de.yaacc.util.YaaccLogger;

/**
 * A {@link LastChange} that tells a listener whenever a value was put into it. Cling collects the changes of
 * the AVTransport service in the LastChange but only sends them to the subscribers when somebody calls
 * {@code fire}; this is the hook that lets the service do that after every change, wherever it comes from (a
 * state of the state machine, a {@code SetPlayMode}, a button at a player).
 * <p>
 * The listener is called while the LastChange is locked, so it must only schedule work and never block.
 */
public class ObservableLastChange extends LastChange {

    private volatile Runnable changeListener;

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
        super.setEventedValue(instanceID, ev);
        boolean changed = false;
        for (EventedValue value : ev) {
            changed |= value != null;
        }
        Runnable listener = changeListener;
        if (changed && listener != null) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                // an event that is not sent must not break the action that caused it
                YaaccLogger.w(getClass().getName(), "LastChange listener failed", e);
            }
        }
    }
}
