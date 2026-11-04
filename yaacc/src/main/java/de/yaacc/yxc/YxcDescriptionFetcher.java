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
package de.yaacc.yxc;

import java.io.IOException;

/** Fetches the UPnP description of a device from the URL the registry of yaacc knows it by. */
public interface YxcDescriptionFetcher {

    /**
     * @return the text of the description, at most {@link YxcDescriptionParser#MAX_DESCRIPTION_CHARS} characters
     * @throws IOException when the device does not answer or answers with anything but a document of that size
     */
    String fetch(String descriptorUrl) throws IOException;
}
