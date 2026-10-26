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

import android.content.Intent;

import de.yaacc.browser.BrowserLayoutChoice.Layout;
import de.yaacc.util.YaaccLogger;

/**
 * Keeps a browser activity from acting on a shared item that is not meant for it. Both browsers
 * read the share payload of their intent in {@code onCreate}/{@code onNewIntent}, BEFORE the resume
 * check relaunches into the other layout, and {@code TabBrowserActivity} is exported, so any app
 * can send it an explicit {@code ACTION_SEND} even while the user has chosen the new layout. The
 * share handler behaves by the calling activity (classic plays a link on this device without a
 * receiver pick), which would bypass the picker of the new layout. An activity therefore hands the
 * payload to the handler only when it renders the effective layout; otherwise the payload is
 * consumed (and the relaunch uses a fresh intent, so nothing is replayed).
 * <p>
 * The launcher forward is not affected: it always starts the activity of the effective layout.
 */
final class ShareIntakeGate {

    private static final String TAG = ShareIntakeGate.class.getName();

    private ShareIntakeGate() {
    }

    /**
     * @param own       the layout the calling activity renders
     * @param effective the layout {@code BrowserLauncher#effectiveLayout} reports now
     * @return true only if the activity renders the effective layout (the same rule that decides
     * the relaunch, so an activity that is about to be replaced never takes the payload)
     */
    static boolean admits(Layout own, Layout effective) {
        return effective != null && !BrowserLayoutSwitch.shouldRelaunch(own, effective);
    }

    /**
     * @return true if the activity may hand {@code intent} to the share handler; false if the
     * payload was consumed instead (nothing in the intent is read, only removed)
     */
    static boolean admitOrDiscard(Layout own, Layout effective, Intent intent) {
        if (admits(own, effective)) {
            return true;
        }
        YaaccLogger.w(TAG, "A shared item arrived at the activity of the other layout, dropping it");
        if (intent != null) {
            ShareIntentHandler.discard(intent);
        }
        return false;
    }
}
