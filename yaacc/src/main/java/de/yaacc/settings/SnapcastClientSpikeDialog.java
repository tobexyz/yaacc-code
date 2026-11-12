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
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import de.yaacc.R;
import de.yaacc.multiroom.SnapcastHub;
import de.yaacc.multiroom.client.SnapcastClientRuntime;
import de.yaacc.multiroom.client.SnapcastClientService;
import de.yaacc.multiroom.client.SnapcastClientSpike;
import de.yaacc.multiroom.client.SnapcastClientStatusText;
import de.yaacc.multiroom.client.SnapcastClientTraceReport;
import de.yaacc.multiroom.ui.SnapcastServerListModel;

/**
 * The test screen of the client spike: the address of the server, the manual latency, Start, Stop and a live status
 * text with the numbers to report after a listen test. Thin Android glue; the checks are in
 * {@link SnapcastClientSpike} and the text in {@link SnapcastClientStatusText}. The status is read from the process-wide
 * {@link SnapcastClientRuntime}, so the screen can be closed (and the screen switched off) while the service runs, and
 * shows the current state when it is opened again.
 */
final class SnapcastClientSpikeDialog {

    private static final long REFRESH_MS = 500L;

    private final Context context;
    private final SharedPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final EditText host;
    private final EditText latency;
    private final EditText writeAhead;
    private final TextView warning;
    private final Button start;
    private final Button stop;
    private final Button apply;
    private final TextView status;
    private final TextView summary;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "snapcast-client-spike");
        thread.setDaemon(true);
        return thread;
    });
    private AlertDialog dialog;
    private boolean shown;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            if (!shown) {
                return;
            }
            boolean active = SnapcastClientRuntime.isActive();
            start.setEnabled(!active);
            stop.setEnabled(active);
            apply.setEnabled(active);
            SnapcastClientRuntime.View view = SnapcastClientRuntime.get();
            status.setText(SnapcastClientStatusText.of(view, System.currentTimeMillis()));
            summary.setText(SnapcastClientTraceReport.summary(view.engine, deviceDescription()));
            main.postDelayed(this, REFRESH_MS);
        }
    };

    private SnapcastClientSpikeDialog(Context context) {
        this.context = context;
        this.preferences = PreferenceManager.getDefaultSharedPreferences(context);
        float density = context.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, (int) (8 * density), pad, 0);

        TextView note = new TextView(context);
        note.setText(R.string.snapcast_client_note);
        note.setTextSize(12);
        root.addView(note);

        host = new EditText(context);
        host.setSingleLine(true);
        host.setHint(R.string.snapcast_client_host_hint);
        host.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        host.setFilters(new InputFilter[]{new InputFilter.LengthFilter(300)});
        host.setText(preferences.getString(SnapcastClientSpike.KEY_LAST_HOST, ""));
        root.addView(host, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        warning = new TextView(context);
        warning.setText(R.string.snapcast_client_warning_public);
        warning.setTextSize(12);
        warning.setTypeface(Typeface.DEFAULT_BOLD);
        warning.setVisibility(android.view.View.GONE);
        root.addView(warning);
        host.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable text) {
                showWarningIfPublic(text.toString());
            }
        });
        showWarningIfPublic(host.getText().toString());

        latency = new EditText(context);
        latency.setSingleLine(true);
        latency.setHint(R.string.snapcast_client_latency_hint);
        latency.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        latency.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8)});
        latency.setText(String.valueOf(preferences.getInt(SnapcastClientSpike.KEY_LAST_LATENCY, 0)));
        root.addView(latency, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        writeAhead = new EditText(context);
        writeAhead.setSingleLine(true);
        writeAhead.setHint(R.string.snapcast_client_write_ahead_hint);
        writeAhead.setInputType(InputType.TYPE_CLASS_NUMBER);
        writeAhead.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        writeAhead.setText(String.valueOf(preferences.getInt(SnapcastClientSpike.KEY_LAST_WRITE_AHEAD,
                SnapcastClientSpike.DEFAULT_WRITE_AHEAD_MS)));
        root.addView(writeAhead, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        start = new Button(context);
        start.setText(R.string.snapcast_client_start);
        start.setOnClickListener(v -> onStart());
        stop = new Button(context);
        stop.setText(R.string.snapcast_client_stop);
        stop.setOnClickListener(v -> SnapcastClientService.stop(context));
        apply = new Button(context);
        apply.setText(R.string.snapcast_client_apply_latency);
        apply.setOnClickListener(v -> onApplyLatency());
        buttons.addView(start);
        buttons.addView(stop);
        buttons.addView(apply);
        root.addView(buttons);

        LinearLayout traceRow = new LinearLayout(context);
        traceRow.setOrientation(LinearLayout.HORIZONTAL);
        Button copyTrace = new Button(context);
        copyTrace.setText(R.string.snapcast_client_copy_trace);
        copyTrace.setOnClickListener(v -> onCopyTrace());
        traceRow.addView(copyTrace);
        summary = new TextView(context);
        summary.setTypeface(Typeface.MONOSPACE);
        summary.setTextSize(10);
        summary.setTextIsSelectable(true);
        traceRow.addView(summary, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(traceRow);

        status = new TextView(context);
        status.setTypeface(Typeface.MONOSPACE);
        status.setTextSize(11);
        status.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(status);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.snapcast_client_title)
                .setView(root)
                .setPositiveButton(R.string.snapcast_client_close, null)
                .create();
        dialog.setOnDismissListener(d -> {
            shown = false;
            main.removeCallbacks(refresh);
            worker.shutdown();
        });
    }

    /** Opens the screen. */
    static void show(Context context) {
        SnapcastClientSpikeDialog opened = new SnapcastClientSpikeDialog(context);
        opened.dialog.show();
        opened.shown = true;
        opened.refresh.run();
        opened.prefillFromServers();
    }

    /** With nothing remembered, offers the host of the first server of the Snapcast server list (the port is 1704 then). */
    private void prefillFromServers() {
        if (host.getText().length() > 0) {
            return;
        }
        try {
            worker.execute(() -> {
                List<SnapcastServerListModel.Row> rows = SnapcastHub.getShared(context).newServerListModel().rows();
                if (rows.isEmpty()) {
                    return;
                }
                String name = rows.get(0).getKey().getEndpoint().getHost();
                final String text = name.indexOf(':') >= 0 ? "[" + name + "]" : name;
                main.post(() -> {
                    if (shown && host.getText().length() == 0) {
                        host.setText(text);
                    }
                });
            });
        } catch (RejectedExecutionException closed) {
            // dismissed meanwhile
        }
    }

    /** An IP literal outside the local ranges gets a warning (a host name is not looked up here; that is the user's choice). */
    private void showWarningIfPublic(String text) {
        de.yaacc.multiroom.discovery.SnapcastEndpoint endpoint = de.yaacc.multiroom.discovery.SnapcastEndpoint
                .parse(text.trim(), de.yaacc.multiroom.protocol.SnapcastConnection.DEFAULT_PORT);
        boolean isPublic = endpoint != null && SnapcastClientSpike.localityOf(endpoint) == SnapcastClientSpike.Locality.PUBLIC;
        warning.setVisibility(isPublic ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private void onStart() {
        SnapcastClientSpike.Request request = SnapcastClientSpike.check(host.getText().toString(), latency.getText().toString());
        host.setError(null);
        latency.setError(null);
        switch (request.getOutcome()) {
            case EMPTY_HOST:
                host.setError(context.getString(R.string.snapcast_client_error_empty_host));
                return;
            case INVALID_HOST:
                host.setError(context.getString(R.string.snapcast_client_error_invalid_host));
                return;
            case INVALID_LATENCY:
                latency.setError(context.getString(R.string.snapcast_client_error_invalid_latency));
                return;
            default:
                break;
        }
        writeAhead.setError(null);
        Integer ahead = SnapcastClientSpike.parseWriteAhead(writeAhead.getText().toString());
        if (ahead == null) {
            writeAhead.setError(context.getString(R.string.snapcast_client_error_invalid_write_ahead));
            return;
        }
        preferences.edit().putString(SnapcastClientSpike.KEY_LAST_HOST, host.getText().toString().trim())
                .putInt(SnapcastClientSpike.KEY_LAST_LATENCY, request.getLatencyMs())
                .putInt(SnapcastClientSpike.KEY_LAST_WRITE_AHEAD, ahead).apply();
        SnapcastClientService.start(context, host.getText().toString().trim(), request.getLatencyMs(), ahead);
    }

    private void onApplyLatency() {
        Integer value = SnapcastClientSpike.parseLatency(latency.getText().toString());
        if (value == null) {
            latency.setError(context.getString(R.string.snapcast_client_error_invalid_latency));
            return;
        }
        preferences.edit().putInt(SnapcastClientSpike.KEY_LAST_LATENCY, value).apply();
        SnapcastClientService.setLatency(context, value);
    }

    /** Device model and API level for the report; the model name is the device's, never the server's. */
    private static String deviceDescription() {
        return android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + " / API " + android.os.Build.VERSION.SDK_INT;
    }

    /** Puts the summary, the status text and the event trace on the clipboard, to paste into a report. */
    private void onCopyTrace() {
        SnapcastClientRuntime.View view = SnapcastClientRuntime.get();
        if (view.engine == null || view.engine.trace == null || view.engine.trace.size() == 0) {
            Toast.makeText(context, R.string.snapcast_client_trace_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        String report = SnapcastClientTraceReport.of(view, deviceDescription(), System.currentTimeMillis());
        clipboard.setPrimaryClip(ClipData.newPlainText("snapcast trace", report));
        Toast.makeText(context, R.string.snapcast_client_trace_copied, Toast.LENGTH_SHORT).show();
    }
}
