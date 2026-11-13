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

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.yaacc.R;
import de.yaacc.multiroom.SnapcastHub;
import de.yaacc.multiroom.SnapcastServerAdmin;
import de.yaacc.multiroom.ui.SnapcastServerListModel;

/**
 * The list of Snapcast servers of the settings: a field to add a server by address (validated strictly by
 * {@link SnapcastServerListModel}, no credentials, at most 16) and the servers that are listed, each removable when the
 * user typed it in or it was remembered. Thin Android glue; every decision is in the model. The model's calls run on a
 * worker (removing a server closes its connection), and the result is posted back to the main thread.
 *
 * <p>Below the list is the discovery report (task 11.6, {@link de.yaacc.multiroom.discovery.SnapcastDiscoveryReport}): what the
 * scan browses, what the system reported, the state of the switch, Wi-Fi, permission and multicast lock, with a button that
 * copies it. While the dialog is open the scan runs if the switch "Find Snapcast servers" is on (the dialog counts as a page of
 * the discovery, not of the connections), so the report is live; the text is refreshed every second. The report is for the
 * screen and the clipboard only: it names the servers found and their addresses, which the note beside the button says.
 */
final class SnapcastServersDialog {

    private final Context context;
    private final SnapcastServerListModel model;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "snapcast-servers");
        thread.setDaemon(true);
        return thread;
    });
    private static final long REPORT_REFRESH_MS = 1000L;

    private final EditText input;
    private final LinearLayout list;
    private final TextView report;
    private AlertDialog dialog;
    private boolean shown;
    private String drawnRows;
    private String reportText = "";

    private final Runnable reportLoop = new Runnable() {
        @Override
        public void run() {
            if (!shown) {
                return;
            }
            refresh();
            main.postDelayed(this, REPORT_REFRESH_MS);
        }
    };

    private SnapcastServersDialog(Context context) {
        this.context = context;
        this.model = SnapcastHub.getShared(context).newServerListModel();
        float density = context.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, (int) (8 * density), pad, 0);
        input = new EditText(context);
        input.setSingleLine(true);
        input.setHint(R.string.snapcast_servers_add_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(300)});
        root.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button add = new Button(context);
        add.setText(R.string.snapcast_servers_add);
        add.setOnClickListener(v -> add());
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        addParams.gravity = Gravity.END;
        root.addView(add, addParams);
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        // the list and the report scroll together: a phone in landscape has room for little else
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(list);
        TextView reportTitle = new TextView(context);
        reportTitle.setText(R.string.snapcast_servers_discovery_title);
        reportTitle.setTextAppearance(android.R.style.TextAppearance_Material_Medium);
        reportTitle.setPadding(0, (int) (16 * density), 0, (int) (4 * density));
        content.addView(reportTitle);
        report = new TextView(context);
        report.setTypeface(Typeface.MONOSPACE);
        report.setTextSize(10);
        report.setTextIsSelectable(true);
        content.addView(report);
        Button copy = new Button(context);
        copy.setText(R.string.snapcast_servers_discovery_copy);
        copy.setOnClickListener(v -> copyReport());
        content.addView(copy, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView note = new TextView(context);
        note.setText(R.string.snapcast_servers_discovery_note);
        note.setTextSize(12);
        content.addView(note);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.settings_snapcast_servers_title)
                .setView(root)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.setOnDismissListener(d -> {
            shown = false;
            main.removeCallbacks(reportLoop);
            SnapcastHub.getShared(this.context).diagnosticsClosed();
            worker.shutdown();
        });
    }

    /** Opens the dialog. */
    static void show(Context context) {
        SnapcastServersDialog opened = new SnapcastServersDialog(context);
        opened.dialog.show();
        opened.shown = true;
        SnapcastHub.getShared(context).diagnosticsOpened();
        opened.reportLoop.run();
    }

    /** Puts the report on the clipboard. It names the servers found and their addresses; the note beside the button says so. */
    private void copyReport() {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || reportText.isEmpty()) {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("snapcast discovery", reportText));
        Toast.makeText(context, R.string.snapcast_servers_discovery_copied, Toast.LENGTH_SHORT).show();
    }

    private void add() {
        final String text = input.getText().toString();
        worker.execute(() -> {
            final SnapcastServerListModel.Outcome outcome = model.add(text);
            main.post(() -> {
                if (outcome == SnapcastServerListModel.Outcome.ADDED) {
                    input.setText("");
                    input.setError(null);
                } else {
                    input.setError(context.getString(messageFor(outcome)));
                }
                refresh();
            });
        });
    }

    /** The text for an outcome that is not a success. */
    static int messageFor(SnapcastServerListModel.Outcome outcome) {
        switch (outcome) {
            case EMPTY:
                return R.string.snapcast_servers_error_empty;
            case HAS_CREDENTIALS:
                return R.string.snapcast_servers_error_credentials;
            case FULL:
                return R.string.snapcast_servers_error_full;
            case ALREADY_LISTED:
                return R.string.snapcast_servers_already;
            case INVALID:
            default:
                return R.string.snapcast_servers_error_invalid;
        }
    }

    private void refresh() {
        try {
            worker.execute(() -> {
                final List<SnapcastServerListModel.Row> rows = model.rows();
                final String text = SnapcastHub.getShared(context).discoveryReport();
                main.post(() -> {
                    showReport(text);
                    draw(rows);
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException closed) {
            // the dialog was dismissed meanwhile
        }
    }

    private void showReport(String text) {
        reportText = text;
        if (!text.contentEquals(report.getText())) {
            report.setText(text); // plain text, cleaned by the report: nothing of the network is a link or markup
        }
    }

    private void draw(List<SnapcastServerListModel.Row> rows) {
        // the list is redrawn only when it changed: the report refreshes every second, and a redraw would eat a tap on "remove"
        StringBuilder signature = new StringBuilder();
        for (SnapcastServerListModel.Row row : rows) {
            signature.append(row.getEndpointText()).append('|').append(row.getName()).append('|').append(row.getKind())
                    .append('|').append(row.isAnnounced()).append('\n');
        }
        if (signature.toString().equals(drawnRows)) {
            return;
        }
        drawnRows = signature.toString();
        list.removeAllViews();
        float density = context.getResources().getDisplayMetrics().density;
        if (rows.isEmpty()) {
            TextView none = new TextView(context);
            none.setText(R.string.snapcast_servers_empty);
            none.setPadding(0, (int) (12 * density), 0, (int) (12 * density));
            list.addView(none);
            return;
        }
        for (final SnapcastServerListModel.Row row : rows) {
            LinearLayout line = new LinearLayout(context);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setMinimumHeight((int) (56 * density));
            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView endpoint = new TextView(context);
            // the name came from the network: cleaned by the model, plain setText
            endpoint.setText(row.getName().isEmpty() ? row.getEndpointText() : row.getEndpointText() + "  " + row.getName());
            endpoint.setTextAppearance(android.R.style.TextAppearance_Material_Medium);
            texts.addView(endpoint);
            TextView kind = new TextView(context);
            kind.setText(kindText(row));
            kind.setTextSize(12);
            texts.addView(kind);
            line.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (row.isRemovable()) {
                ImageButton remove = new ImageButton(context);
                remove.setImageResource(R.drawable.ic_snapcast_remove);
                remove.setContentDescription(context.getString(R.string.snapcast_servers_remove, row.getEndpointText()));
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

    private static int kindText(SnapcastServerListModel.Row row) {
        switch (row.getKind()) {
            case MANUAL:
                return row.isAnnounced() ? R.string.snapcast_servers_kind_manual_found : R.string.snapcast_servers_kind_manual;
            case REMEMBERED:
                return R.string.snapcast_servers_kind_remembered;
            case DISCOVERED:
            default:
                return R.string.snapcast_servers_kind_found;
        }
    }

    private void remove(final SnapcastServerListModel.Row row) {
        worker.execute(() -> {
            final SnapcastServerAdmin.Result result = model.remove(row.getKey());
            final List<SnapcastServerListModel.Row> rows = model.rows();
            main.post(() -> {
                if (result.getSelectionRemoved() > 0) {
                    Toast.makeText(context, R.string.snapcast_servers_removed, Toast.LENGTH_LONG).show();
                }
                draw(rows);
            });
        });
    }
}
