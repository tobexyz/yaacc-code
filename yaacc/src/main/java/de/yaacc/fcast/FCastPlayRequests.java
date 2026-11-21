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
package de.yaacc.fcast;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import de.yaacc.fcast.protocol.FCastMessages;
import de.yaacc.fcast.protocol.FCastPacketCodec;
import de.yaacc.fcast.protocol.FCastProtocolException;
import de.yaacc.fcast.protocol.FCastText;
import de.yaacc.player.ContentTypes;

/**
 * Builds the {@code Play} request of an item (task 4.1, design.md section 5). Pure.
 *
 * <ul>
 *   <li><b>The URL goes out exactly as it is</b> (decision D9): user information, a token in the query, a fragment, nothing is
 *   removed or rewritten. The one refusal besides a missing URL is a request that does not fit the packet of protocol v3.</li>
 *   <li>The container is the MIME type: the one the item describes, else the one of its file extension, else
 *   {@value #UNKNOWN_TYPE}; it is never empty (a v3 receiver refuses an empty one).</li>
 *   <li>The thumbnail is sent only if it is an http or https address that another device can reach (not {@code localhost}, not a
 *   loopback address) and short enough; otherwise it is left out and the item still plays.</li>
 *   <li>No headers are ever sent: no cookies or credentials leave the app.</li>
 * </ul>
 */
public final class FCastPlayRequests {

    /** The container of an item whose type is not known. */
    public static final String UNKNOWN_TYPE = "application/octet-stream";

    /** Why no request was made. */
    public enum Refusal {
        NONE,
        /** The item has no address. */
        NO_URL,
        /** The request is larger than a packet of protocol v3. */
        TOO_LARGE,
        /** The request can not be written (a value the message rules refuse). */
        BAD_REQUEST
    }

    /** The request, or the reason why there is none. */
    public static final class Result {
        private final FCastMessages.PlayRequest request;
        private final Refusal refusal;

        private Result(FCastMessages.PlayRequest request, Refusal refusal) {
            this.request = request;
            this.refusal = refusal;
        }

        /** @return the request, null if it was refused */
        public FCastMessages.PlayRequest getRequest() {
            return request;
        }

        public Refusal getRefusal() {
            return refusal;
        }
    }

    private FCastPlayRequests() {
    }

    /**
     * @param url            the address of the item, as the item has it
     * @param describedType  the MIME type the item describes (DIDL {@code protocolInfo}), may be null
     * @param extensionType  the MIME type of the file extension, may be null
     * @param title          the title, shown by the receiver (cleaned and cut by the message)
     * @param artUrl         the address of the cover or picture, may be null
     * @param startSeconds   where to start; zero, negative or NaN means from the beginning
     * @param volume         0.0 to 1.0, NaN to leave the receiver's volume alone
     */
    public static Result build(String url, String describedType, String extensionType, String title, String artUrl,
                               double startSeconds, double volume) {
        return build(url, describedType, extensionType, title, artUrl, startSeconds, volume, 1.0);
    }

    /** As above; {@code speed} 1.0 or NaN is not sent, other values are clamped by the message (the size check counts it). */
    public static Result build(String url, String describedType, String extensionType, String title, String artUrl,
                               double startSeconds, double volume, double speed) {
        if (url == null || url.trim().isEmpty()) {
            return new Result(null, Refusal.NO_URL);
        }
        String container = ContentTypes.pick(describedType, extensionType);
        FCastMessages.PlayRequest request = new FCastMessages.PlayRequest(container == null ? UNKNOWN_TYPE : container, url);
        if (!Double.isNaN(startSeconds) && startSeconds > 0) {
            request.time(startSeconds);
        }
        if (!Double.isNaN(volume)) {
            request.volume(FCastText.clamp(volume, 0.0, 1.0));
        }
        if (!Double.isNaN(speed) && speed != 1.0) {
            request.speed(speed);
        }
        request.title(title);
        if (isReachableWebAddress(artUrl)) {
            request.thumbnailUrl(artUrl);
        }
        try {
            if (request.toJson().getBytes(StandardCharsets.UTF_8).length > FCastPacketCodec.MAX_BODY) {
                return new Result(null, Refusal.TOO_LARGE);
            }
        } catch (FCastProtocolException e) {
            return new Result(null, Refusal.BAD_REQUEST);
        }
        return new Result(request, Refusal.NONE);
    }

    /** An http or https address that is short and does not point at this phone itself. */
    static boolean isReachableWebAddress(String address) {
        if (address == null || address.isEmpty() || address.length() > FCastText.MAX_URL) {
            return false;
        }
        try {
            URI uri = new URI(address);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (host == null || scheme == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                return false;
            }
            String lower = host.toLowerCase(Locale.ROOT);
            return !(lower.equals("localhost") || lower.endsWith(".localhost") || lower.startsWith("127.")
                    || lower.equals("[::1]") || lower.equals("::1") || lower.equals("0.0.0.0"));
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
