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
package de.yaacc.upnp;

import org.fourthline.cling.model.action.ActionException;
import org.fourthline.cling.model.types.ErrorCode;

/**
 * An action failed because the request never reached the server or no answer came back (no
 * connection, timeout, dropped network), as opposed to the server answering with a UPnP error.
 * It behaves like any {@link ActionException}; the type only tells {@link UpnpFailure} that
 * repeating the same request can help.
 */
public class UpnpTransportException extends ActionException {

    public UpnpTransportException(String message) {
        super(ErrorCode.ACTION_FAILED, message);
    }
}
