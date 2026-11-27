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

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import de.yaacc.R;
import de.yaacc.sendspin.QrScan;
import de.yaacc.sendspin.SendspinHub;
import de.yaacc.sendspin.hub.Endpoint;
import de.yaacc.sendspin.hub.HubConnection;
import de.yaacc.sendspin.pairing.CodePairingServer;
import de.yaacc.sendspin.session.SendspinMessages;

/**
 * Pairing a Sendspin player with a code (issue #325, task 11.1): the operator chooses one of the methods the player offers (the
 * code the device shows, as digits or as a QR code, or its fixed code), starts the attempt, triggers pairing on the device with its
 * own gesture and enters the code. The dialog follows the attempt (it polls the connection twice a second): it tells what the
 * device waits for, asks for the code, says when the code was not right and closes when the pairing is done. The specification
 * demands that the operator is told to trigger pairing on the device before reading the code, so the text says it.
 */
final class SendspinCodePairDialog {

    private final Context context;
    private final SendspinHub hub;
    private final Endpoint endpoint;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextView status;
    private final TextView instruction;
    private final RadioGroup methods;
    private final EditText input;
    private final LinearLayout entryRow;
    private final Button scan;
    private final Button enter;
    private final Button start;
    private final Button cancel;
    private AlertDialog dialog;
    private boolean methodsBuilt;
    private CodePairingServer.Method scanned;

    static void show(Context context, Endpoint endpoint, String playerName) {
        if (!(context instanceof Activity) || ((Activity) context).isFinishing()) {
            return;
        }
        new SendspinCodePairDialog(context, endpoint, playerName);
    }

    private SendspinCodePairDialog(Context context, Endpoint endpoint, String playerName) {
        this.context = context;
        this.endpoint = endpoint;
        this.hub = SendspinHub.getShared(context);
        float density = context.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, (int) (8 * density), pad, 0);
        status = new TextView(context);
        status.setTextAppearance(android.R.style.TextAppearance_Material_Medium);
        root.addView(status);
        instruction = new TextView(context);
        instruction.setPadding(0, (int) (8 * density), 0, (int) (8 * density));
        root.addView(instruction);
        methods = new RadioGroup(context);
        root.addView(methods);
        start = new Button(context);
        start.setText(R.string.sendspin_codepair_start);
        start.setOnClickListener(v -> startAttempt());
        root.addView(start, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        input = new EditText(context);
        input.setHint(R.string.sendspin_codepair_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(120)});
        root.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        entryRow = new LinearLayout(context);
        entryRow.setOrientation(LinearLayout.HORIZONTAL);
        entryRow.setGravity(android.view.Gravity.END);
        scan = new Button(context);
        scan.setText(R.string.sendspin_pair_scan);
        scan.setOnClickListener(v -> QrScan.scan(context, text -> {
            input.setText(text);
            submit();
        }));
        enter = new Button(context);
        enter.setText(R.string.sendspin_codepair_enter);
        enter.setOnClickListener(v -> submit());
        entryRow.addView(scan);
        entryRow.addView(enter);
        root.addView(entryRow);
        cancel = new Button(context);
        cancel.setText(R.string.sendspin_codepair_cancel);
        cancel.setOnClickListener(v -> {
            HubConnection c = hub.connection(endpoint);
            if (c != null) {
                c.cancelCodePairing();
            }
        });
        root.addView(cancel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.addView(root);
        String name = playerName == null || playerName.isEmpty() ? endpoint.host() : playerName;
        dialog = new AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.sendspin_codepair_title, name))
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.setOnDismissListener(d -> main.removeCallbacksAndMessages(null));
        dialog.show();
        refresh();
    }

    private HubConnection connection() {
        return hub.connection(endpoint);
    }

    private void startAttempt() {
        HubConnection c = connection();
        int checked = methods.getCheckedRadioButtonId();
        CodePairingServer.Method method = checked == -1 ? null : (CodePairingServer.Method) methods.findViewById(checked).getTag();
        if (c == null || method == null || !c.startCodePairing(method)) {
            Toast.makeText(context, R.string.sendspin_codepair_cannot_start, Toast.LENGTH_LONG).show();
        }
        refresh();
    }

    private void submit() {
        HubConnection c = connection();
        if (c == null || !c.submitCode(input.getText().toString())) {
            Toast.makeText(context, R.string.sendspin_codepair_bad_entry, Toast.LENGTH_LONG).show();
            return;
        }
        input.setText("");
    }

    private void buildMethods(SendspinMessages.ClientHello hello) {
        methods.removeAllViews();
        boolean first = true;
        if (hello.codeFormats.contains("digits")) {
            first = add(CodePairingServer.Method.DYNAMIC_DIGITS, R.string.sendspin_codepair_method_digits, first);
        }
        if (hello.codeFormats.contains("qr_code")) {
            first = add(CodePairingServer.Method.DYNAMIC_QR, R.string.sendspin_codepair_method_qr, first);
        }
        if (hello.staticCodeOffered) {
            add(CodePairingServer.Method.STATIC, R.string.sendspin_codepair_method_static, first);
        }
        methodsBuilt = true;
    }

    private boolean add(CodePairingServer.Method method, int label, boolean checked) {
        RadioButton button = new RadioButton(context);
        button.setId(View.generateViewId());
        button.setText(label);
        button.setTag(method);
        methods.addView(button);
        if (checked) {
            button.setChecked(true);
        }
        return false;
    }

    /** Draws what the attempt is at and schedules the next look. */
    private void refresh() {
        HubConnection c = connection();
        SendspinMessages.ClientHello hello = c == null ? null : c.hello();
        CodePairingServer attempt = c == null ? null : c.codePairing();
        boolean running = attempt != null;
        if (hello == null || !hello.offersCodePairing()) {
            status.setText(R.string.sendspin_codepair_none);
            setVisible(false, false, false);
        } else if (!running) {
            if (!methodsBuilt) {
                buildMethods(hello);
            }
            boolean failed = c.phase() == HubConnection.Phase.PAIRING_FAILED;
            status.setText(failed && c.pairingFailure() == HubConnection.PairingFailure.CODE_REJECTED ? R.string.sendspin_codepair_rejected
                    : failed ? R.string.sendspin_codepair_aborted : R.string.sendspin_codepair_choose);
            instruction.setText(R.string.sendspin_codepair_instruction_dynamic);
            instruction.setVisibility(View.VISIBLE);
            setVisible(true, false, false);
            if (hub.pairedIds().contains(c.clientId())) {
                Toast.makeText(context, R.string.sendspin_codepair_done, Toast.LENGTH_LONG).show();
                dialog.dismiss();
                return;
            }
        } else {
            CodePairingServer.State s = attempt.state();
            boolean isStatic = attempt.method() == CodePairingServer.Method.STATIC;
            boolean waitingForWindow = s == CodePairingServer.State.WAITING_WINDOW || s == CodePairingServer.State.ACTIVATED;
            String device = attempt.deviceMessage();
            if (waitingForWindow) {
                status.setText(context.getString(R.string.sendspin_codepair_waiting_window, device == null ? "" : SendspinRowTextClean.of(device)));
            } else if (s == CodePairingServer.State.WAITING_CODE) {
                status.setText(attempt.round() > 1 ? context.getString(R.string.sendspin_codepair_round, attempt.round())
                        : context.getString(R.string.sendspin_codepair_enter_now));
            } else {
                status.setText(R.string.sendspin_codepair_working);
            }
            instruction.setText(isStatic ? R.string.sendspin_codepair_instruction_static : R.string.sendspin_codepair_instruction_dynamic);
            instruction.setVisibility(View.VISIBLE);
            boolean canEnter = s == CodePairingServer.State.WAITING_CODE || (isStatic && waitingForWindow);
            setVisible(false, canEnter, true);
            scan.setVisibility(attempt.method() == CodePairingServer.Method.DYNAMIC_QR ? View.VISIBLE : View.GONE);
            input.setHint(attempt.method() == CodePairingServer.Method.DYNAMIC_QR ? R.string.sendspin_codepair_hint_qr
                    : isStatic ? R.string.sendspin_codepair_hint_static : R.string.sendspin_codepair_hint);
        }
        main.postDelayed(this::refresh, 500L);
    }

    private void setVisible(boolean selecting, boolean entering, boolean cancelable) {
        methods.setVisibility(selecting ? View.VISIBLE : View.GONE);
        start.setVisibility(selecting ? View.VISIBLE : View.GONE);
        input.setVisibility(entering ? View.VISIBLE : View.GONE);
        entryRow.setVisibility(entering ? View.VISIBLE : View.GONE);
        cancel.setVisibility(cancelable ? View.VISIBLE : View.GONE);
    }

    /** The cleaned text of the device (it comes from an unauthenticated peer, so it is shown cleaned and clearly as the device's). */
    private static final class SendspinRowTextClean {
        static String of(String text) {
            return de.yaacc.sendspin.SendspinRowText.clean(text);
        }
    }
}
