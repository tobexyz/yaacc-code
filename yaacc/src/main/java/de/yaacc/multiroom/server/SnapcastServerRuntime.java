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
package de.yaacc.multiroom.server;

/** What the screens may ask about the running built-in server (issue #330): whether it runs, its ports and its clients. */
public final class SnapcastServerRuntime {

    /** A snapshot for a screen. */
    public static final class View {
        public final boolean running;
        public final int streamPort;
        public final int controlPort;
        public final int clients;
        public final int streams;

        View(boolean running, int streamPort, int controlPort, int clients, int streams) {
            this.running = running;
            this.streamPort = streamPort;
            this.controlPort = controlPort;
            this.clients = clients;
            this.streams = streams;
        }
    }

    private static volatile SnapcastServer server;
    private static volatile ControlServer control;

    private SnapcastServerRuntime() {
    }

    static void set(SnapcastServer running, ControlServer controlRunning) {
        server = running;
        control = controlRunning;
    }

    static void clear() {
        server = null;
        control = null;
    }

    public static boolean isRunning() {
        return server != null;
    }

    public static View get() {
        SnapcastServer s = server;
        ControlServer c = control;
        if (s == null) {
            return new View(false, -1, -1, 0, 0);
        }
        return new View(true, s.streamPort(), c == null ? -1 : c.port(), s.state().connectedClientCount(), s.state().streamIds().size());
    }
}
