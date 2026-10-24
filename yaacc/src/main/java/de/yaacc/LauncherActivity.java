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
package de.yaacc;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.os.Bundle;

import de.yaacc.browser.BrowserLauncher;
import de.yaacc.browser.LauncherForwardPolicy;
import de.yaacc.browser.ShareUriPolicy;
import de.yaacc.util.YaaccLogger;

/**
 * Invisible entry point: holds the launcher, Android TV and share-target intent filters and starts
 * the browser activity of the chosen layout.
 * <p>
 * This component is exported and any app can start it with any intent, so the intent it forwards
 * is always a fresh one, built from the allowlist in {@link LauncherForwardPolicy}: a plain launch,
 * or an {@code ACTION_SEND} of {@code text/plain} with a length-capped text. No flags, data,
 * categories, selector, URIs (so no URI grants), {@code EXTRA_STREAM}, nested intents or other
 * extras are copied, and the incoming intent is never modified.
 * <p>
 * Extends the framework {@link Activity}, not AppCompat, because {@code Theme.NoDisplay} is a
 * framework theme. An activity with that theme must call {@code finish()} before {@code onResume}
 * completes (targetSdk &gt; 22), hence {@code finish()} in {@code finally}.
 */
public class LauncherActivity extends Activity {

    private static final String TAG = LauncherActivity.class.getName();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            Intent forward = new Intent(this, BrowserLauncher.targetClass(this));
            try {
                applyShareText(getIntent(), forward);
            } catch (RuntimeException e) {
                // crafted extras can throw on read: forward nothing extra, never log the payload
                YaaccLogger.w(TAG, "Could not read the incoming intent (" + e.getClass().getSimpleName() + ")");
                forward = new Intent(this, BrowserLauncher.targetClass(this));
            }
            startActivity(forward);
        } catch (RuntimeException e) {
            YaaccLogger.w(TAG, "Could not start the browser (" + e.getClass().getSimpleName() + ")");
        } finally {
            finish();
        }
    }

    /** Turns {@code forward} into a SEND of the shared text if the policy allows it. */
    private static void applyShareText(Intent incoming, Intent forward) {
        if (incoming == null) {
            return;
        }
        LauncherForwardPolicy.Decision decision = LauncherForwardPolicy.decide(
                incoming.getAction(), incoming.getType(), readSharedText(incoming));
        if (decision.isSendText()) {
            forward.setAction(Intent.ACTION_SEND);
            forward.setType("text/plain");
            forward.putExtra(Intent.EXTRA_TEXT, decision.getText());
        }
    }

    /**
     * {@code EXTRA_TEXT}, else the text of the last {@code ClipData} item that has one, as the share
     * handler reads it. A clip item's URI is never used.
     */
    private static String readSharedText(Intent incoming) {
        String text = incoming.getStringExtra(Intent.EXTRA_TEXT);
        if (text != null) {
            return text;
        }
        ClipData clipData = incoming.getClipData();
        if (clipData == null) {
            return null;
        }
        for (int i = 0; i < clipData.getItemCount(); i++) {
            ClipData.Item item = clipData.getItemAt(i);
            CharSequence itemText = item == null ? null : item.getText();
            // check the length first so that a huge text is not copied into a String
            if (itemText != null && itemText.length() <= ShareUriPolicy.MAX_TEXT_LENGTH) {
                text = itemText.toString();
            }
        }
        return text;
    }
}
