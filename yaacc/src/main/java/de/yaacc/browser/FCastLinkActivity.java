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
package de.yaacc.browser;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import de.yaacc.LauncherActivity;
import de.yaacc.R;
import de.yaacc.fcast.discovery.FCastAddReceiver;
import de.yaacc.fcast.discovery.FCastAddReceiverText;
import de.yaacc.fcast.discovery.FCastHub;
import de.yaacc.fcast.discovery.FCastLinkIntake;
import de.yaacc.fcast.discovery.FCastLinkText;
import de.yaacc.multiroom.control.MonotonicClock;

/**
 * Opens {@code fcast://r/...} links (task 5.6): the QR code that an FCast receiver shows, scanned with the device's own camera or
 * scanner app, or a link from any other app or web page. YAACC needs no scanner, no camera permission and no new dependency for it.
 *
 * <p><b>The link is untrusted.</b> The activity reads {@code getDataString()} only; {@link FCastLinkIntake} decides what it is. Nothing is
 * connected, played or stored before the user taps <i>Add</i> in the confirmation dialog, which shows the name, the addresses and a note
 * on the protocol. A malformed link shows "not an FCast link" and nothing else; the same link twice within two seconds is ignored;
 * the link text is never logged. <i>Add</i> goes through the same input as a typed-in receiver ({@link FCastAddReceiver}: the addresses
 * are tried in order, the first that answers is stored) and opens the receivers page. If another installed app registers the scheme
 * as well (the FCast sender, Grayjay) the system shows its chooser and the user picks YAACC.
 */
public class FCastLinkActivity extends AppCompatActivity {

    /** One intake for the whole app: a second link arrives in this activity (singleTop) or in a new one, the repeat rule is the same. */
    private static final FCastLinkIntake INTAKE = new FCastLinkIntake(MonotonicClock.SYSTEM);

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "fcast-link");
        thread.setDaemon(true);
        return thread;
    });
    private AlertDialog dialog;
    private boolean adding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
        super.onDestroy();
    }

    private void handle(Intent intent) {
        String data = intent == null ? null : intent.getDataString();
        FCastLinkIntake.Decision decision = INTAKE.accept(data);
        switch (decision.getKind()) {
            case IGNORE:
                if (dialog == null || !dialog.isShowing()) {
                    finish();
                }
                break;
            case CONFIRM:
                confirm(decision);
                break;
            case NOT_A_LINK:
            default:
                Toast.makeText(this, R.string.fcast_link_not_a_link, Toast.LENGTH_LONG).show();
                if (dialog == null || !dialog.isShowing()) {
                    finish();
                }
                break;
        }
    }

    private void confirm(final FCastLinkIntake.Decision decision) {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss(); // a new link replaces the one that is shown
        }
        final Context context = this;
        String message = FCastLinkText.message(decision.getParsed(), new FCastLinkText.Words() {
            @Override
            public String name(String name) {
                return context.getString(R.string.fcast_link_name, name);
            }

            @Override
            public String unnamed() {
                return context.getString(R.string.fcast_link_unnamed);
            }

            @Override
            public String addresses() {
                return context.getString(R.string.fcast_link_addresses);
            }

            @Override
            public String note(boolean withKey) {
                return context.getString(withKey ? R.string.fcast_link_note_v4 : R.string.fcast_link_note);
            }
        });
        dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.fcast_link_title)
                .setMessage(message) // plain text: the name came from the link
                .setPositiveButton(R.string.fcast_receivers_add, null) // set below: the dialog stays open while the receiver is tested
                .setNegativeButton(android.R.string.cancel, (d, which) -> finish())
                .setOnCancelListener(d -> finish())
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> add(decision.getLink()));
    }

    private void add(final String link) {
        if (adding) {
            return;
        }
        adding = true;
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(R.string.fcast_receivers_adding);
        final FCastAddReceiver addReceiver = FCastHub.getShared(this).newAddReceiver();
        try {
            worker.execute(() -> {
                final FCastAddReceiver.Result result = addReceiver.add(link); // tries the addresses: may wait for the network
                main.post(() -> {
                    adding = false;
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    if (result.getOutcome() == FCastAddReceiver.Outcome.ADDED) {
                        Toast.makeText(this, getString(R.string.fcast_receivers_added,
                                result.getName().isEmpty() ? result.getEndpoint().toString() : result.getName()), Toast.LENGTH_SHORT).show();
                        startActivity(new Intent(this, LauncherActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
                        finish();
                    } else {
                        showFailure(result);
                    }
                });
            });
        } catch (RejectedExecutionException closed) {
            adding = false;
        }
    }

    private void showFailure(FCastAddReceiver.Result result) {
        StringBuilder text = new StringBuilder();
        for (FCastAddReceiverText.Line line : FCastAddReceiverText.lines(result)) {
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(getString(line.getResId(), line.getArgs()));
        }
        if (dialog != null && dialog.isShowing()) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(R.string.fcast_receivers_add);
        }
        Toast.makeText(this, text.toString(), Toast.LENGTH_LONG).show();
    }
}
