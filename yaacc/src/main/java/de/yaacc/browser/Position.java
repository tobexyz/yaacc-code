/*
 * Copyright (C) 2013 www.yaacc.de 
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
package de.yaacc.browser;

import org.fourthline.cling.model.meta.Device;

import java.io.Serializable;

/**
 * @author Christoph Hähnel (eyeless)
 */
public class Position implements Serializable {

    private String objectId;
    private String deviceId;

    public Position(String objectId, String deviceId) {

        this.deviceId = deviceId;
        this.objectId = objectId;
    }


    public String getObjectId() {
        return objectId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "Position ["
                + (objectId != null ? "objectId=" + objectId + ", " : "")
                + (deviceId != null ? "device=" + deviceId : "") + "]";
    }


}
