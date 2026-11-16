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

import java.util.concurrent.CopyOnWriteArrayList;

import de.yaacc.util.YaaccLogger;

/**
 * The way the receiver cards reach the standby state of the app's own OpenHome renderer (task 12.13): the one {@code Standby} state
 * that the power switch of the OpenHome settings sheet and a control point write with the action {@code SetStandby}. The service
 * {@link #attach attaches} the renderer when it builds the OpenHome services and {@link #detach detaches} it when it ends; nothing here
 * keeps a value of its own, a thread or a setting. The listeners (the rows of a shown list) are told, on whatever thread changed the
 * state, when the state changed or the renderer came or went; they hand over to the main thread themselves.
 */
public final class OwnOpenHomeStandby {

    /** Told that the state or the existence of the own renderer changed. */
    public interface Listener {
        void onOwnStandbyChanged();
    }

    private static final OwnOpenHomeStandby SHARED = new OwnOpenHomeStandby();

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Runnable forwarder = this::tell;
    private volatile OhStandby renderer;

    public static OwnOpenHomeStandby shared() {
        return SHARED;
    }

    /** The renderer of the running service; one that was attached before is let go. */
    public synchronized void attach(OhStandby renderer) {
        if (renderer == null) {
            return;
        }
        OhStandby before = this.renderer;
        if (before != null && before != renderer) {
            before.removeStandbyListener(forwarder);
        }
        this.renderer = renderer;
        renderer.addStandbyListener(forwarder);
        tell();
    }

    /** Only the renderer that is attached now can be detached (a late call of an earlier service changes nothing). */
    public synchronized void detach(OhStandby renderer) {
        if (renderer == null || this.renderer != renderer) {
            return;
        }
        renderer.removeStandbyListener(forwarder);
        this.renderer = null;
        tell();
    }

    /** @return whether the app's own OpenHome renderer runs (the cards offer the button only then) */
    public boolean isAttached() {
        return renderer != null;
    }

    /** @return true when the renderer is in standby, null when there is none */
    public Boolean standby() {
        OhStandby current = renderer;
        return current == null ? null : current.isStandby();
    }

    /** @return false when there is no renderer; otherwise the renderer did what the action {@code SetStandby} does */
    public boolean setStandby(boolean standby) {
        OhStandby current = renderer;
        if (current == null) {
            return false;
        }
        current.setStandby(standby);
        return true;
    }

    /** The same listener twice is one. */
    public void addListener(Listener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void tell() {
        for (Listener listener : listeners) {
            try {
                listener.onOwnStandbyChanged();
            } catch (RuntimeException broken) {
                YaaccLogger.w(getClass().getName(), "A standby listener failed (" + broken.getClass().getSimpleName() + ")");
            }
        }
    }
}
