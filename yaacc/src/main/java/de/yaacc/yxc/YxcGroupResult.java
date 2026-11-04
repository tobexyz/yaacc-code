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

/**
 * Outcome of a group operation. Expected failures (a device does not answer, refuses a call, or does not reach the
 * expected state in time) are values, not exceptions. {@link #getDetail()} is English text for logs, not for the UI.
 */
public final class YxcGroupResult {

    public enum Outcome {SUCCESS, FAILED, TIMEOUT}

    /** The step an operation stopped at. */
    public enum Step {
        /** The request itself is not valid (nothing was sent). */
        VALIDATE,
        /** Reading the group state or the zone status of a device. */
        READ_STATE,
        POWER_ON,
        /** A device leaves its group. */
        LEAVE_GROUP,
        /** A server ends its group. */
        CLOSE_GROUP,
        /** A client joins the group. */
        JOIN_CLIENT,
        /** The server lists clients (adds). */
        EXTEND_SERVER,
        /** The server removes clients. */
        REDUCE_SERVER,
        RENAME,
        /** A client that left was put back on its earlier input. */
        RESTORE_INPUT
    }

    private final Outcome outcome;
    private final Step step;
    private final String deviceIp;
    private final String detail;
    private final String groupId;

    private YxcGroupResult(Outcome outcome, Step step, String deviceIp, String detail, String groupId) {
        this.outcome = outcome;
        this.step = step;
        this.deviceIp = deviceIp;
        this.detail = detail;
        this.groupId = groupId;
    }

    /** @param groupId the id of the group that now exists, {@code null} if the operation ended a group or changed none */
    static YxcGroupResult success(String groupId) {
        return new YxcGroupResult(Outcome.SUCCESS, null, null, null, groupId);
    }

    public static YxcGroupResult failed(Step step, String deviceIp, String detail) {
        return new YxcGroupResult(Outcome.FAILED, step, deviceIp, detail, null);
    }

    static YxcGroupResult timeout(Step step, String deviceIp, String detail) {
        return new YxcGroupResult(Outcome.TIMEOUT, step, deviceIp, detail, null);
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public boolean isSuccess() {
        return outcome == Outcome.SUCCESS;
    }

    /** @return the step that failed, {@code null} on success */
    public Step getStep() {
        return step;
    }

    /** @return the address of the device the step was for, {@code null} on success or if no device is to blame */
    public String getDeviceIp() {
        return deviceIp;
    }

    public String getDetail() {
        return detail;
    }

    /** @return the id of the group the operation created or changed, {@code null} if there is none */
    public String getGroupId() {
        return groupId;
    }

    @Override
    public String toString() {
        return isSuccess() ? "YxcGroupResult{SUCCESS}"
                : "YxcGroupResult{" + outcome + " at " + step + " of " + deviceIp + ": " + detail + "}";
    }
}
