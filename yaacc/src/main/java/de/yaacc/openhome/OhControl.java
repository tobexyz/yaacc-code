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
package de.yaacc.openhome;

import java.util.Map;

/**
 * Calls an action of a service of an OpenHome device. The controller ({@link OhRemote}) is written against this, so it runs in
 * plain JVM tests against the renderer classes; the app implements it with Cling.
 */
public interface OhControl {

    /**
     * @param service   "Product", "Playlist", "Info", "Time" or "Volume"
     * @param action    the action name
     * @param arguments the input arguments by name, as text
     * @return the output arguments by name, as text (empty for none)
     * @throws OhControlException when the device refused the action, did not answer, or does not offer the service
     */
    Map<String, String> invoke(String service, String action, Map<String, String> arguments) throws OhControlException;
}
