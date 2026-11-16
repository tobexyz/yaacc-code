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
package de.yaacc.multiroom.protocol;

import de.yaacc.multiroom.control.SnapcastJson;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The settings the server pushes to this client. The values are clamped on the way in (the server is not trusted):
 * {@code bufferMs} 0 to 10 000, {@code latency} -10 000 to 10 000, {@code volume} 0 to 100.
 */
public final class ServerSettingsMessage implements SnapcastBinaryMessage {

    private final int bufferMs;
    private final int latencyMs;
    private final boolean muted;
    private final int volume;

    public ServerSettingsMessage(int bufferMs, int latencyMs, boolean muted, int volume) {
        this.bufferMs = clamp(bufferMs, SnapcastProtocolLimits.MIN_BUFFER_MS, SnapcastProtocolLimits.MAX_BUFFER_MS);
        this.latencyMs = clamp(latencyMs, SnapcastProtocolLimits.MIN_LATENCY_MS, SnapcastProtocolLimits.MAX_LATENCY_MS);
        this.muted = muted;
        this.volume = clamp(volume, SnapcastProtocolLimits.MIN_VOLUME, SnapcastProtocolLimits.MAX_VOLUME);
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** How long the server buffers: a chunk is played this long after it was recorded. */
    public int getBufferMs() {
        return bufferMs;
    }

    /** The latency setting of this client in milliseconds: positive values play earlier. */
    public int getLatencyMs() {
        return latencyMs;
    }

    public boolean isMuted() {
        return muted;
    }

    /** 0 to 100. */
    public int getVolume() {
        return volume;
    }

    @Override
    public MessageType type() {
        return MessageType.SERVER_SETTINGS;
    }

    byte[] encodeBody() {
        try {
            JSONObject json = new JSONObject();
            json.put("bufferMs", bufferMs);
            json.put("latency", latencyMs);
            json.put("muted", muted);
            json.put("volume", volume);
            return JsonBodies.write(json.toString());
        } catch (JSONException e) {
            throw new IllegalStateException("could not write the settings", e);
        }
    }

    static ServerSettingsMessage decode(ByteReader reader) throws SnapcastProtocolException {
        JSONObject json = JsonBodies.read(reader);
        return new ServerSettingsMessage(
                SnapcastJson.integer(json, "bufferMs", SnapcastProtocolLimits.MIN_BUFFER_MS,
                        SnapcastProtocolLimits.MAX_BUFFER_MS, SnapcastProtocolLimits.DEFAULT_BUFFER_MS),
                SnapcastJson.integer(json, "latency", SnapcastProtocolLimits.MIN_LATENCY_MS,
                        SnapcastProtocolLimits.MAX_LATENCY_MS, 0),
                SnapcastJson.bool(json, "muted", false),
                SnapcastJson.integer(json, "volume", SnapcastProtocolLimits.MIN_VOLUME, SnapcastProtocolLimits.MAX_VOLUME,
                        SnapcastProtocolLimits.MAX_VOLUME));
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ServerSettingsMessage)) {
            return false;
        }
        ServerSettingsMessage that = (ServerSettingsMessage) other;
        return bufferMs == that.bufferMs && latencyMs == that.latencyMs && muted == that.muted && volume == that.volume;
    }

    @Override
    public int hashCode() {
        return ((bufferMs * 31 + latencyMs) * 31 + (muted ? 1 : 0)) * 31 + volume;
    }
}
