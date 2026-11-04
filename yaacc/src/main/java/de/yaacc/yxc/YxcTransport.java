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

/**
 * The wire under {@link YxcClient}: sends a request to one device and returns the body text of the answer.
 * Paths are relative to the control URL of the device (for example {@code dist/getDistributionInfo}).
 * An answer with an HTTP status outside 200..299 is a {@link YxcHttpStatusException}.
 */
public interface YxcTransport {

    String get(String path) throws IOException;

    /** @param jsonBody the JSON text sent as the request body */
    String post(String path, String jsonBody) throws IOException;
}
