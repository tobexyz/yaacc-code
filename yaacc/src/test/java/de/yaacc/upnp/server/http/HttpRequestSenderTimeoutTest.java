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
package de.yaacc.upnp.server.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

/** Which requests get the longer response timeout. Plain JVM. */
public class HttpRequestSenderTimeoutTest {

    @Test
    public void contentDirectoryActionsAreRecognisedByTheirSoapAction() {
        assertTrue(HttpRequestSender.isContentDirectoryAction("\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\""));
        assertTrue(HttpRequestSender.isContentDirectoryAction("\"urn:schemas-upnp-org:service:ContentDirectory:2#Search\""));
    }

    @Test
    public void otherActionsKeepTheShortTimeout() {
        assertFalse(HttpRequestSender.isContentDirectoryAction("\"urn:schemas-upnp-org:service:AVTransport:1#Play\""));
        assertFalse(HttpRequestSender.isContentDirectoryAction("\"urn:schemas-upnp-org:service:RenderingControl:1#GetVolume\""));
        assertFalse(HttpRequestSender.isContentDirectoryAction(null));
    }

    @Test
    public void theLongTimeoutIsLongerThanTheDefaultSocketTimeoutOfTenSeconds() {
        assertEquals(30, HttpRequestSender.CONTENT_DIRECTORY_RESPONSE_TIMEOUT.toSeconds());
        assertTrue(HttpRequestSender.CONTENT_DIRECTORY_RESPONSE_TIMEOUT.toMilliseconds() > TimeUnit.SECONDS.toMillis(10));
    }
}
