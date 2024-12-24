/*
 * Copyright (C) 2013 Tobias Schoene www.yaacc.de
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

import androidx.annotation.NonNull;

import java.io.Serializable;

/**
 * @author Christoph Hähnel (eyeless)
 */
public class Position implements Serializable {

    private final String objectId;
    private final String deviceId;
    private final String objectName;

    private final int positionId;

    public Position(int positionId, String objectId, String deviceId, String name) {

        this.deviceId = deviceId;
        this.objectId = objectId;
        this.objectName = name;
        this.positionId = positionId;
    }


    public String getObjectId() {
        return objectId;
    }

    public String getObjectName() {
        return objectName;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public int getPositionId() {
        return positionId;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @NonNull
    @Override
    public String toString() {
        return "Position ["
                + "positionId=" + positionId + ", "
                + (objectId != null ? "objectId=" + objectId + ", " : "")
                + (deviceId != null ? "device=" + deviceId : "") + "]";
    }


}
