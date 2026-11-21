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
package de.yaacc.settings;

import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import de.yaacc.R;
import de.yaacc.fcast.discovery.FCastAddReceiver;
import de.yaacc.fcast.discovery.FCastAddReceiverText;
import de.yaacc.fcast.discovery.FCastHub;
import de.yaacc.fcast.discovery.FCastReceiverAdmin;
import de.yaacc.fcast.discovery.FCastReceiverRows;
import de.yaacc.multiroom.SnapcastDiscoveryGate;

/**
 * The list of FCast receivers of the settings (tasks 5.4, 5.5, 5.7), the counterpart of {@link SnapcastServersDialog}: one input that
 * takes an address, the name of a device or a pasted connection link ({@link FCastAddReceiver}), the receivers that are listed (those
 * typed in can be removed), and the discovery report with a button that copies it. While the dialog is open the discovery runs if the
 * switch is on (the dialog counts as a page), so the report is live and a name can be found by an announcement. Thin Android glue:
 * every decision is in the pure classes; the work that touches the network or the stores runs on a worker and the answer is posted
 * back to the main thread. The report is for the screen and the clipboard only; the note beside the button says that it names the
 * receivers and their addresses.
 */
final class FCastReceiversDialog {

    private static final long REPORT_REFRESH_MS = 1000L;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "fcast-receivers");
        thread.setDaemon(true);
        return thread;
    });
    private final FCastHub hub;
    private final FCastAddReceiver addReceiver;

    private final EditText input;
    private final Button addButton;
    private final LinearLayout list;
    private final TextView report;
    private AlertDialog dialog;
    private boolean shown;
    private boolean adding;
    private int ticks;
    private String drawnRows;
    private String reportText = "";
    /** The scan of the discovery, leased while the dialog shows; given back once, on every path that ends the dialog. */
    private SnapcastDiscoveryGate.Lease lease;
    private Lifecycle observedLifecycle;
    private LifecycleEventObserver lifecycleObserver;

    private final Runnable reportLoop = new Runnable() {
        @Override
        public void run() {
            if (!shown) {
                return;
            }
            // the window is gone without a dismiss (the activity was destroyed): end the scan and the loop here too
            if (ticks++ > 0 && !dialog.getWindow().getDecorView().isAttachedToWindow()) {
                closeScan();
                return;
            }
            refresh();
            main.postDelayed(this, REPORT_REFRESH_MS);
        }
    };

    private FCastReceiversDialog(Context context) {
        this.context = context;
        this.hub = FCastHub.getShared(context);
        this.addReceiver = hub.newAddReceiver();
        float density = context.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, (int) (8 * density), pad, 0);
        input = new EditText(context);
        input.setSingleLine(true);
        input.setHint(R.string.fcast_receivers_add_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4200)});
        root.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addButton = new Button(context);
        addButton.setText(R.string.fcast_receivers_add);
        addButton.setOnClickListener(v -> add());
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        addParams.gravity = Gravity.END;
        root.addView(addButton, addParams);
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(list);
        TextView reportTitle = new TextView(context);
        reportTitle.setText(R.string.fcast_receivers_discovery_title);
        reportTitle.setTextAppearance(android.R.style.TextAppearance_Material_Medium);
        reportTitle.setPadding(0, (int) (16 * density), 0, (int) (4 * density));
        content.addView(reportTitle);
        report = new TextView(context);
        report.setTypeface(Typeface.MONOSPACE);
        report.setTextSize(10);
        report.setTextIsSelectable(true);
        content.addView(report);
        Button copy = new Button(context);
        copy.setText(R.string.fcast_receivers_discovery_copy);
        copy.setOnClickListener(v -> copyReport());
        content.addView(copy, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView note = new TextView(context);
        note.setText(R.string.fcast_receivers_discovery_note);
        note.setTextSize(12);
        content.addView(note);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.settings_fcast_receivers_title)
                .setView(root)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.setOnDismissListener(d -> closeScan());
    }

    /** Ends everything the open dialog holds: the refresh loop, the lease of the scan and the worker. Idempotent. */
    private void closeScan() {
        shown = false;
        main.removeCallbacks(reportLoop);
        if (lease != null) {
            lease.close();
            lease = null;
        }
        if (observedLifecycle != null && lifecycleObserver != null) {
            observedLifecycle.removeObserver(lifecycleObserver);
        }
        observedLifecycle = null;
        lifecycleObserver = null;
        worker.shutdown();
    }

    /** Ties the dialog to the lifecycle of the screen it was opened from: when that stops, the dialog goes. */
    private void observeScreen() {
        Context walk = context;
        while (walk instanceof ContextWrapper && !(walk instanceof LifecycleOwner)) {
            walk = ((ContextWrapper) walk).getBaseContext();
        }
        if (!(walk instanceof LifecycleOwner)) {
            return;
        }
        observedLifecycle = ((LifecycleOwner) walk).getLifecycle();
        lifecycleObserver = (owner, event) -> {
            if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                closeScan();
                try {
                    dialog.dismiss();
                } catch (RuntimeException alreadyGone) {
                    // the window is gone with its activity
                }
            }
        };
        observedLifecycle.addObserver(lifecycleObserver);
    }

    /** Opens the dialog. */
    static void show(Context context) {
        FCastReceiversDialog opened = new FCastReceiversDialog(context);
        opened.dialog.show();
        opened.shown = true;
        opened.lease = FCastHub.getShared(context).diagnosticsOpened();
        opened.observeScreen();
        opened.reportLoop.run();
    }

    /** Puts the report on the clipboard, flagged as sensitive: it names the receivers found, their addresses and the phone model. */
    private void copyReport() {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || reportText.isEmpty()) {
            return;
        }
        clipboard.setPrimaryClip(SensitiveClip.plainText("fcast discovery", reportText));
        Toast.makeText(context, R.string.fcast_receivers_discovery_copied, Toast.LENGTH_SHORT).show();
    }

    private void add() {
        if (adding) {
            return;
        }
        final String text = input.getText().toString();
        adding = true;
        addButton.setEnabled(false);
        addButton.setText(R.string.fcast_receivers_adding);
        try {
            worker.execute(() -> {
                final FCastAddReceiver.Result result = addReceiver.add(text); // may wait for the network
                main.post(() -> {
                    adding = false;
                    addButton.setEnabled(true);
                    addButton.setText(R.string.fcast_receivers_add);
                    if (result.getOutcome() == FCastAddReceiver.Outcome.ADDED) {
                        input.setText("");
                        input.setError(null);
                        Toast.makeText(context, context.getString(R.string.fcast_receivers_added,
                                result.getName().isEmpty() ? result.getEndpoint().toString() : result.getName()), Toast.LENGTH_SHORT).show();
                    } else {
                        input.setError(messageFor(result));
                    }
                    refresh();
                });
            });
        } catch (RejectedExecutionException closed) {
            adding = false; // the dialog was dismissed meanwhile
        }
    }

    /** The lines of a result that is not a success, as one text. */
    private String messageFor(FCastAddReceiver.Result result) {
        StringBuilder text = new StringBuilder();
        for (FCastAddReceiverText.Line line : FCastAddReceiverText.lines(result)) {
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(context.getString(line.getResId(), line.getArgs()));
        }
        return text.toString();
    }

    private void refresh() {
        try {
            worker.execute(() -> {
                final List<FCastReceiverRows.Row> rows = FCastReceiverRows.of(hub.entries());
                final String text = hub.discoveryReport();
                main.post(() -> {
                    showReport(text);
                    draw(rows);
                });
            });
        } catch (RejectedExecutionException closed) {
            // the dialog was dismissed meanwhile
        }
    }

    private void showReport(String text) {
        reportText = text; // the copy button always has the newest
        boolean selecting = report.hasSelection();
        if (!selecting && !text.contentEquals(report.getText())) { // a selection in progress is not dropped by a refresh
            report.setText(text); // plain text, cleaned by the report: nothing of the network is a link or markup
        }
    }

    private void draw(List<FCastReceiverRows.Row> rows) {
        // the list is redrawn only when it changed: the report refreshes every second, and a redraw would eat a tap on "remove"
        String signature = FCastReceiverRows.signature(rows);
        if (signature.equals(drawnRows)) {
            return;
        }
        drawnRows = signature;
        list.removeAllViews();
        float density = context.getResources().getDisplayMetrics().density;
        if (rows.isEmpty()) {
            TextView none = new TextView(context);
            none.setText(R.string.fcast_receivers_empty);
            none.setPadding(0, (int) (12 * density), 0, (int) (12 * density));
            list.addView(none);
            return;
        }
        for (final FCastReceiverRows.Row row : rows) {
            LinearLayout line = new LinearLayout(context);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setMinimumHeight((int) (56 * density));
            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView title = new TextView(context);
            // the name came from the network: cleaned by the directory, plain setText
            title.setText(row.getName().isEmpty() ? row.getEndpointText() : row.getEndpointText() + "  " + row.getName());
            title.setTextAppearance(android.R.style.TextAppearance_Material_Medium);
            texts.addView(title);
            TextView kind = new TextView(context);
            kind.setText(kindText(row));
            kind.setTextSize(12);
            texts.addView(kind);
            line.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (row.isRemovable()) {
                ImageButton remove = new ImageButton(context);
                remove.setImageResource(R.drawable.ic_snapcast_remove);
                remove.setContentDescription(context.getString(R.string.fcast_receivers_remove, row.getEndpointText()));
                TypedValue ripple = new TypedValue();
                if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)) {
                    remove.setBackgroundResource(ripple.resourceId);
                }
                remove.setMinimumWidth((int) (48 * density));
                remove.setMinimumHeight((int) (48 * density));
                remove.setOnClickListener(v -> remove(row));
                line.addView(remove);
            }
            list.addView(line);
        }
    }

    private static int kindText(FCastReceiverRows.Row row) {
        switch (row.getKind()) {
            case MANUAL:
                return row.isAnnounced() ? R.string.fcast_receivers_kind_manual_found : R.string.fcast_receivers_kind_manual;
            case REMEMBERED:
                return R.string.fcast_receivers_kind_remembered;
            case DISCOVERED:
            default:
                return R.string.fcast_receivers_kind_found;
        }
    }

    private void remove(final FCastReceiverRows.Row row) {
        try {
            worker.execute(() -> {
                final FCastReceiverAdmin.Result result = hub.removeReceiver(row.getEndpoint());
                final List<FCastReceiverRows.Row> rows = FCastReceiverRows.of(hub.entries());
                main.post(() -> {
                    if (result.getSelectionRemoved() > 0) {
                        Toast.makeText(context, R.string.fcast_receivers_removed, Toast.LENGTH_LONG).show();
                    }
                    draw(rows);
                });
            });
        } catch (RejectedExecutionException closed) {
            // the dialog was dismissed meanwhile
        }
    }
}
