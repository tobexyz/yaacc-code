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

/** Which short text the notification of the service shows, decided from the phase and the state of the engine. Pure. */
public enum NotificationText {
    CONNECTING, PLAYING, RECONNECTING;

    public static NotificationText of(SnapcastClientRuntime.Phase phase, EngineState engineState) {
        if (phase == SnapcastClientRuntime.Phase.RECONNECTING) {
            return RECONNECTING;
        }
        if (engineState == EngineState.PLAYING) {
            return PLAYING;
        }
        return CONNECTING;
    }
}
