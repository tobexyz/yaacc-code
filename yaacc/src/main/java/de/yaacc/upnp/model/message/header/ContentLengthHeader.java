
/*
 * Copyright (C) 2023 Tobias Schoene www.yaacc.de
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package de.yaacc.upnp.model.message.header;

import org.fourthline.cling.model.message.header.InvalidHeaderException;
import org.fourthline.cling.model.message.header.UpnpHeader;


public class ContentLengthHeader extends UpnpHeader<Long> {

    public ContentLengthHeader() {
    }

    public ContentLengthHeader(Long value) {
        setValue(value);
    }

    public ContentLengthHeader(String s) {
        setString(s);
    }

    public String getString() {
        return "" + getValue();
    }

    public void setString(String s) throws InvalidHeaderException {
        setValue(Long.parseLong(s));
    }
}