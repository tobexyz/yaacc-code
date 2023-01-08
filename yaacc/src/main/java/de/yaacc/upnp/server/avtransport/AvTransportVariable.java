/*
 * Copyright (C) 2014 Tobias Schoene www.yaacc.de
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
package de.yaacc.upnp.server.avtransport;

import org.fourthline.cling.support.lastchange.EventedValueEnumArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author Tobias Schoene (TheOpenBit)
 */
public class AvTransportVariable {
    public static class CurrentTransportActions extends EventedValueEnumArray<TransportAction> {
        public CurrentTransportActions(TransportAction[] e) {
            super(e);
        }

        public CurrentTransportActions(Map.Entry<String, String>[] attributes) {
            super(attributes);
        }

        @Override
        protected TransportAction[] enumValueOf(String[] names) {
            if (names == null) return new TransportAction[0];
            List<TransportAction> list = new ArrayList<>();
            for (String s : names) {
                list.add(TransportAction.valueOf(s));
            }
            return list.toArray(new TransportAction[]{});
        }
    }

}
