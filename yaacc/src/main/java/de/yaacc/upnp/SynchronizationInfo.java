package de.yaacc.upnp;/*
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

import de.yaacc.upnp.model.types.SyncOffset;

/**
 * @author Tobias Schoene (TheOpenBit)
 */
public class SynchronizationInfo {
    String speed = "";
    String referencedPositionUnits = "";
    String referencedPosition = "";
    String referencedPresentationTime = "";
    String referencedClockId = "";


    SyncOffset offset = new SyncOffset();

    public SynchronizationInfo() {
    }

    public SynchronizationInfo(String speed, String referencedPositionUnits, String referencedPosition, String referencedPresentationTime, String referencedClockId) {
        this.speed = speed;
        this.referencedPositionUnits = referencedPositionUnits;
        this.referencedPosition = referencedPosition;
        this.referencedPresentationTime = referencedPresentationTime;
        this.referencedClockId = referencedClockId;
    }

    public String getReferencedClockId() {
        return referencedClockId;
    }

    public void setReferencedClockId(String referencedClockId) {
        this.referencedClockId = referencedClockId;
    }

    public String getSpeed() {
        return speed;
    }

    public void setSpeed(String speed) {
        this.speed = speed;
    }

    public String getReferencedPositionUnits() {
        return referencedPositionUnits;
    }

    public void setReferencedPositionUnits(String referencedPositionUnits) {
        this.referencedPositionUnits = referencedPositionUnits;
    }

    public String getReferencedPosition() {
        return referencedPosition;
    }

    public void setReferencedPosition(String referencedPosition) {
        this.referencedPosition = referencedPosition;
    }

    public String getReferencedPresentationTime() {
        return referencedPresentationTime;
    }

    public void setReferencedPresentationTime(String referencedPresentationTime) {
        this.referencedPresentationTime = referencedPresentationTime;
    }

    public SyncOffset getReferencedPresentationTimeOffset() {
        return new SyncOffset(referencedPresentationTime);
    }

    public SyncOffset getOffset() {
        return offset;
    }

    public void setOffset(SyncOffset offset) {
        this.offset = offset;
    }
}
