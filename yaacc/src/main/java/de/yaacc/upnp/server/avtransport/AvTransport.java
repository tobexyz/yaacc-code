package de.yaacc.upnp.server.avtransport;/*
* Copyright (C) 2014 www.yaacc.de
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

import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;
import org.fourthline.cling.support.lastchange.LastChange;
import org.fourthline.cling.support.model.AVTransport;
import org.fourthline.cling.support.model.StorageMedium;

import de.yaacc.upnp.SynchronizationInfo;

/**
 * @author Tobias Schoene (TheOpenBit)
 */
public class AvTransport extends AVTransport {

    SynchronizationInfo synchronizationInfo = new SynchronizationInfo();

    public AvTransport(UnsignedIntegerFourBytes instanceID, LastChange lastChange, StorageMedium possiblePlayMedium) {
        super(instanceID, lastChange, possiblePlayMedium);
    }

    public SynchronizationInfo getSynchronizationInfo() {
        return synchronizationInfo;
    }

    public void setSynchronizationInfo(SynchronizationInfo synchronizationInfo) {
        this.synchronizationInfo = synchronizationInfo;
    }
}
