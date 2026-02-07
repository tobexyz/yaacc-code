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
/*
 * Copyright (C) 2013 4th Line GmbH, Switzerland
 *
 * The contents of this file are subject to the terms of either the GNU
 * Lesser General Public License Version 2 or later ("LGPL") or the
 * Common Development and Distribution License Version 1 or later
 * ("CDDL") (collectively, the "License"). You may not use this file
 * except in compliance with the License. See LICENSE.txt for more
 * information.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package de.yaacc.upnp.protocol.async;

import android.content.Context;

import org.fourthline.cling.model.meta.LocalDevice;
import org.fourthline.cling.model.types.NotificationSubtype;

import java.io.IOException;

import de.yaacc.upnp.server.udp.UdpTransiver;
import de.yaacc.util.YaaccLogger;

/**
 * Sending <em>ALIVE</em> notification messages for a registered local device.
 *
 * @author Christian Bauer
 */
public class SendingNotificationAlive extends SendingNotification {


    public SendingNotificationAlive(Context context, UdpTransiver udpTransiver, LocalDevice device) {
        super(context, udpTransiver, device);
    }

    @Override
    protected void execute() throws IOException {
        YaaccLogger.v(getClass().getName(), "Sending alive messages (" + getBulkRepeat() + " times) for: " + getDevice());
        super.execute();
    }

    protected NotificationSubtype getNotificationSubtype() {
        return NotificationSubtype.ALIVE;
    }

}
