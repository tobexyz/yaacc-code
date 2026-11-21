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
 * Builds the {@link AutoCloseRule.Facts} of the background idle timer from the raw settings and states. Pure, so that the
 * table of the rule can be tested without Android.
 *
 * <p>The renderer ("receiving") and the media proxy checkboxes only take effect while the master switch of the local UPnP
 * service ("UPnP Server") is on: with the master switch off the service is stopped and the content handlers refuse, so
 * nothing is served although the sub-checkboxes stay ticked. They therefore count as running only with the master switch.
 * The master switch itself keeps the app open as before (the provider checkbox is not asked: the service runs and
 * announces the device with the master switch alone).
 */
final class IdleFacts {

    private IdleFacts() {
    }

    /**
     * @param upnpServerActive     the master switch of the local UPnP service (settings_local_server_chkbx)
     * @param playersActive        at least one player exists
     * @param snapcastClientActive a Snapcast client session is starting or running
     * @param snapcastFeedActive   a Snapcast feed (a player into a group) exists
     * @param autostartTicked      the setting "autostart on boot"
     */
    static AutoCloseRule.Facts of(boolean upnpServerActive, boolean snapcastClientTicked, boolean playersActive,
                                  boolean snapcastClientActive, boolean snapcastFeedActive, boolean autostartTicked) {
        return new AutoCloseRule.Facts()
                .playersActive(playersActive)
                .upnpServerEnabled(upnpServerActive)
                .snapcastClientEnabled(snapcastClientTicked)
                .snapcastClientSessionActive(snapcastClientActive)
                .snapcastFeedActive(snapcastFeedActive)
                .autostartEnabled(autostartTicked);
    }
}
