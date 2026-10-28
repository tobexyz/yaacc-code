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
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.RequiresApi;
import androidx.core.content.IntentCompat;

import de.yaacc.browser.BrowserLauncher;
import de.yaacc.browser.LauncherForwardPolicy;
import de.yaacc.browser.ShareUriPolicy;
import de.yaacc.share.LaunchNonces;
import de.yaacc.share.SharedContentPolicy;
import de.yaacc.share.OwnProviders;
import de.yaacc.util.YaaccLogger;

/**
 * Invisible entry point: holds the launcher, Android TV and share-target intent filters and starts
 * the browser activity of the chosen layout.
 * <p>
 * This component is exported and any app can start it with any intent, so the intent it forwards
 * is always a fresh one, built from the allowlist in {@link LauncherForwardPolicy}: a plain launch,
 * an {@code ACTION_SEND} of {@code text/plain} with a length-capped text, or ONE shared file.
 * <p>
 * The text forward carries no flags, data, categories, selector, URIs, {@code EXTRA_STREAM}, nested
 * intents or other extras. The file forward is the one deliberate exception: ONE {@code content:}
 * URI as the only item of a new ClipData plus {@code FLAG_GRANT_READ_URI_PERMISSION}, and nothing
 * else (the system checks the grant for data and ClipData, never for {@code EXTRA_STREAM}, so the
 * URI is not repeated there) except one single-use launch token ({@link LaunchNonces}): the browser
 * activities are exported too and accept a file only with a token that this class mints after its
 * checks passed. It is built only after {@link LauncherForwardPolicy#decideStream}
 * agreed (URI in EXTRA_STREAM equals ClipData item 0, the incoming intent carried the read grant,
 * a {@code content:} URI of another app), after the URI was found not to belong to ANY provider of
 * this app. (From Android 15 on the initial caller is asked whether it holds read access, for the log only: via the
 * system share sheet it is not the sending app and the answer is "denied".)
 * A forward the system refuses ({@code SecurityException}) falls back to a plain launch. The
 * incoming intent is never modified. Exactly one of the paths is taken per intent.
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
            Class<? extends Activity> target = BrowserLauncher.targetClass(this);
            Intent forward = null;
            boolean streamForward = false;
            try {
                forward = buildStreamForward(getIntent(), target);
                streamForward = forward != null;
            } catch (RuntimeException e) {
                // crafted extras can throw on read: no file forward, never log the payload
                YaaccLogger.w(TAG, "Could not read the incoming file share (" + e.getClass().getSimpleName() + ")");
                forward = null;
            }
            if (forward == null) {
                if (isRejectedFileShare(getIntent())) {
                    // fixed text, no reason and no payload: the share was not taken
                    Toast.makeText(this, R.string.share_not_accepted, Toast.LENGTH_LONG).show();
                }
                forward = new Intent(this, target);
                try {
                    applyShareText(getIntent(), forward);
                } catch (RuntimeException e) {
                    // crafted extras can throw on read: forward nothing extra, never log the payload
                    YaaccLogger.w(TAG, "Could not read the incoming intent (" + e.getClass().getSimpleName() + ")");
                    forward = new Intent(this, target);
                }
            }
            try {
                startActivity(forward);
            } catch (SecurityException e) {
                if (!streamForward) {
                    throw e;
                }
                // the system did not honor the grant (the provider forbids re-granting, or the grant ended): plain launch
                YaaccLogger.w(TAG, "The shared file could not be forwarded, starting the browser without it");
                startActivity(new Intent(this, target));
            }
        } catch (RuntimeException e) {
            YaaccLogger.w(TAG, "Could not start the browser (" + e.getClass().getSimpleName() + ")");
        } finally {
            finish();
        }
    }

    /** Whether the intent tried to share a file; any crafted extra that throws counts as no. */
    private boolean isRejectedFileShare(Intent incoming) {
        try {
            ClipData clip = incoming.getClipData();
            ClipData.Item first = clip == null || clip.getItemCount() < 1 ? null : clip.getItemAt(0);
            return LauncherForwardPolicy.isFileShareAttempt(incoming.getAction(),
                    incoming.hasExtra(Intent.EXTRA_STREAM), first != null && first.getUri() != null);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The forward for a shared file, or null if the intent is none (or fails a check) and the other
     * path decides. Fresh intent to {@code target}: action SEND, the declared lower-case type, one
     * ClipData item with the URI, the read flag, nothing else.
     */
    private Intent buildStreamForward(Intent incoming, Class<? extends Activity> target) {
        // the common launches (MAIN, text) never touch the extras here
        if (incoming == null || !Intent.ACTION_SEND.equals(incoming.getAction())
                || !LauncherForwardPolicy.isStreamType(incoming.getType())) {
            return null;
        }
        Uri extraStream = IntentCompat.getParcelableExtra(incoming, Intent.EXTRA_STREAM, Uri.class);
        ClipData clip = incoming.getClipData();
        ClipData.Item first = clip == null || clip.getItemCount() < 1 ? null : clip.getItemAt(0);
        Uri clipUri = first == null ? null : first.getUri();
        String ownAuthority = OwnProviders.fileProviderAuthority(getPackageName());
        LauncherForwardPolicy.StreamDecision decision = LauncherForwardPolicy.decideStream(
                incoming.getAction(), incoming.getType(),
                extraStream == null ? null : extraStream.toString(),
                clipUri == null ? null : clipUri.toString(),
                incoming.getFlags(), ownAuthority);
        if (!decision.isForwardStream()) {
            // fixed code only: no URI, no type; tells which of the checks turned a file share away
            String reason = LauncherForwardPolicy.rejectionReason(
                    incoming.getAction(), incoming.getType(),
                    extraStream == null ? null : extraStream.toString(),
                    clipUri == null ? null : clipUri.toString(),
                    incoming.getFlags(), ownAuthority);
            YaaccLogger.w(TAG, "A shared file was not forwarded: " + reason + " (sdk " + Build.VERSION.SDK_INT + ")");
            return null;
        }
        // any provider of this app, not only the file provider: its URI would make yaacc serve its own data
        String authority = SharedContentPolicy.authorityOf(decision.getStreamUri());
        if (authority == null || OwnProviders.isOwnAuthority(getPackageManager(), getPackageName(), authority)) {
            YaaccLogger.w(TAG, "A shared file was ignored: it belongs to this app");
            return null;
        }
        // Android 15+: ask the initial caller whether it held access, for the log only. The system already checked
        // the sender when it launched this activity with the read grant flag (which decideStream requires, with the
        // URI in the ClipData): a sender that cannot read the URI gets a SecurityException on its own startActivity.
        // Through the system share sheet the initial caller is not the sending app and the answer is "denied", so
        // blocking on it turned every share from a gallery away.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            try {
                int result = senderPermission(clipUri);
                if (result != PackageManager.PERMISSION_GRANTED) {
                    YaaccLogger.i(TAG, "The initial caller of the shared file holds no read access (" + result
                            + "), not blocking: the system checked the sender when it granted the file");
                }
            } catch (RuntimeException e) {
                YaaccLogger.i(TAG, "The sender check of the shared file is not available ("
                        + e.getClass().getSimpleName() + ")");
            }
        }
        Intent forward = new Intent(this, target);
        forward.setAction(Intent.ACTION_SEND);
        forward.setType(incoming.getType());
        forward.setClipData(ClipData.newRawUri("", clipUri));
        // read only: never write, persistable or prefix
        forward.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        // The browser activities are exported, so an app could send them a file directly and skip the
        // checks above. They accept a file only with this single-use token, which only exists after
        // those checks passed and is never visible outside this process.
        forward.putExtra(LaunchNonces.EXTRA_NAME,
                ((Yaacc) getApplicationContext()).getLaunchNonces().mint());
        return forward;
    }

    /**
     * Whether the real sender (the initial caller of this activity, which is the sender because the
     * launcher is the entry point) holds read access to the URI.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private int senderPermission(Uri uri) {
        return getInitialCaller().checkContentUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
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
