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

import org.json.JSONObject;

/**
 * Answer of {@code system/getDeviceInfo}. The ids identify a physical unit: {@link #toString()} leaves them out
 * so that a log line does not carry them.
 */
public final class YxcDeviceInfo {
    private final String deviceId;
    private final String systemId;
    private final String modelName;
    private final String systemVersion;
    private final Double apiVersion;

    YxcDeviceInfo(String deviceId, String systemId, String modelName, String systemVersion, Double apiVersion) {
        this.deviceId = deviceId;
        this.systemId = systemId;
        this.modelName = modelName;
        this.systemVersion = systemVersion;
        this.apiVersion = apiVersion;
    }

    static YxcDeviceInfo fromJson(JSONObject json) {
        return new YxcDeviceInfo(
                YxcJson.string(json, "device_id"),
                YxcJson.string(json, "system_id"),
                YxcJson.string(json, "model_name"),
                YxcJson.string(json, "system_version"),
                YxcJson.decimal(json, "api_version"));
    }

    /** @return the device id, {@code null} if the device did not report one */
    public String getDeviceId() {
        return deviceId;
    }

    /** @return the system id; a manual discovery treats a device without it as "not a YXC device" */
    public String getSystemId() {
        return systemId;
    }

    public String getModelName() {
        return modelName;
    }

    public String getSystemVersion() {
        return systemVersion;
    }

    /** @return the API version as a number (for example 1.19), {@code null} if not reported */
    public Double getApiVersion() {
        return apiVersion;
    }

    /** Features that depend on the API version (for example volume up / down from 1.17) are gated with this. */
    public boolean isApiVersionAtLeast(double minimum) {
        return apiVersion != null && apiVersion >= minimum;
    }

    @Override
    public String toString() {
        return "YxcDeviceInfo{model=" + modelName + ", api=" + apiVersion + "}";
    }
}
