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

import java.util.Map;

import de.yaacc.openhome.OhError;

/**
 * What an action does. The input and the output are the argument values as texts of the UPnP data types (numbers in decimal,
 * booleans as {@code true} / {@code false}, binary as base64).
 */
public interface OhActionHandler {
    /**
     * @param in the input arguments by name
     * @return the output arguments by name; {@code null} or empty for an action without output
     * @throws OhError for an error that goes to the control point with its code
     */
    Map<String, String> invoke(Map<String, String> in);
}
