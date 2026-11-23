/*
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
package de.yaacc.browser;

/**
 * What a tap on a live stream button of the card "Play from this device" does (pure, no Android): the live stream of this phone
 * (the device audio or the screen) is sent to a receiver. If the server serves that stream already (the UPnP server is on, the
 * media provider is on and the stream is switched on with the server card) the receiver gets the server's own link. Otherwise
 * the stream is offered like other content from this device: a short-lived link of its own, made for the receiver, that needs
 * no server switch; the capture runs as long as that link lives.
 */
final class LivePickPlan {

    enum Action {
        /** This Android version has no live streams (they need Android 10). */
        NOT_SUPPORTED,
        /** The server serves the stream: send its link. */
        SEND_SERVER_LINK,
        /** The permissions are there: make a link of its own and send it. */
        SEND_OWN_LINK,
        /** The system's permissions are asked first (as on the server card), then a link of its own is made and sent. */
        ASK_PERMISSION_THEN_SEND
    }

    private LivePickPlan() {
    }

    /**
     * @param supported            Android 10 or newer
     * @param serverServesStream   the UPnP server switch, the media provider and this stream are all on
     * @param permissionsAvailable the system's screen capture permission is there (and, for the audio, the recording permission)
     */
    static Action decide(boolean supported, boolean serverServesStream, boolean permissionsAvailable) {
        if (!supported) {
            return Action.NOT_SUPPORTED;
        }
        if (serverServesStream) {
            return Action.SEND_SERVER_LINK;
        }
        return permissionsAvailable ? Action.SEND_OWN_LINK : Action.ASK_PERMISSION_THEN_SEND;
    }
}
