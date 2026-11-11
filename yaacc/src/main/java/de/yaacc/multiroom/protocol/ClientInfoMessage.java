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

/** What the client tells the server about itself when it changes: the volume (0 to 100) and the mute flag. */
public final class ClientInfoMessage implements SnapcastBinaryMessage {

    private final int volume;
    private final boolean muted;

    public ClientInfoMessage(int volume, boolean muted) {
        this.volume = ServerSettingsMessage.clamp(volume, SnapcastProtocolLimits.MIN_VOLUME, SnapcastProtocolLimits.MAX_VOLUME);
        this.muted = muted;
    }

    public int getVolume() {
        return volume;
    }

    public boolean isMuted() {
        return muted;
    }

    @Override
    public MessageType type() {
        return MessageType.CLIENT_INFO;
    }

    byte[] encodeBody() {
        try {
            JSONObject json = new JSONObject();
            json.put("volume", volume);
            json.put("muted", muted);
            return JsonBodies.write(json.toString());
        } catch (JSONException e) {
            throw new IllegalStateException("could not write the client info", e);
        }
    }

    static ClientInfoMessage decode(ByteReader reader) throws SnapcastProtocolException {
        JSONObject json = JsonBodies.read(reader);
        return new ClientInfoMessage(
                SnapcastJson.integer(json, "volume", SnapcastProtocolLimits.MIN_VOLUME, SnapcastProtocolLimits.MAX_VOLUME,
                        SnapcastProtocolLimits.MAX_VOLUME),
                SnapcastJson.bool(json, "muted", false));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ClientInfoMessage && volume == ((ClientInfoMessage) other).volume
                && muted == ((ClientInfoMessage) other).muted;
    }

    @Override
    public int hashCode() {
        return volume * 2 + (muted ? 1 : 0);
    }
}
