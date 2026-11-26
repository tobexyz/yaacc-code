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
import android.text.InputFilter;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AlertDialog;

import de.yaacc.R;
import de.yaacc.sendspin.SendspinHub;
import de.yaacc.sendspin.SendspinPolicy;
import de.yaacc.sendspin.hub.Endpoint;
import de.yaacc.sendspin.secure.PairingToken;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.util.UUID;

/**
 * The dialog of a Sendspin player's card that needs pairing (issue #325): the operator pastes the pairing token that the player
 * shows. The hub checks that the token belongs to the player of this card (the key in the token must be the id the player
 * presented, as the specification demands), keeps the Pairing PSK and connects again, which runs the pairing. The token is a
 * credential: it is neither logged nor shown again.
 */
final class SendspinPairDialog {

    /** A token is about 110 characters; room for what a paste may bring along. */
    private static final int MAX_INPUT = 400;

    private SendspinPairDialog() {
    }

    static void show(Context context, Endpoint endpoint, String playerName) {
        if (!(context instanceof Activity) || ((Activity) context).isFinishing()) {
            return;
        }
        float density = context.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, (int) (8 * density), pad, 0);
        final EditText input = new EditText(context);
        input.setHint(R.string.sendspin_pair_hint);
        input.setMinLines(2);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_INPUT)});
        root.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        String name = playerName == null || playerName.isEmpty() ? endpoint.host() : playerName;
        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.sendspin_pair_title, name))
                .setMessage(R.string.sendspin_pair_message)
                .setView(root)
                .setPositiveButton(R.string.sendspin_pair_button, null)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.sendspin_pair_scan, null)
                .create();
        // the dialog stays open on a refused token, so that the person can correct it
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> scan(context, input, dialog));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            SendspinPolicy.PairOutcome outcome = SendspinHub.getShared(context).pair(endpoint, input.getText().toString());
            switch (outcome) {
                case STARTED:
                    Toast.makeText(context, R.string.sendspin_pair_started, Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                    break;
                case WRONG_PLAYER:
                    PairingToken pasted = PairingToken.decode(input.getText().toString());
                    Toast.makeText(context, context.getString(R.string.sendspin_pair_wrong_player,
                            shortId(pasted == null ? null : pasted.clientId()),
                            shortId(SendspinHub.getShared(context).connectedClientId(endpoint))), Toast.LENGTH_LONG).show();
                    break;
                case NOT_KNOWN:
                    Toast.makeText(context, R.string.sendspin_pair_not_known, Toast.LENGTH_LONG).show();
                    break;
                case NO_ROOM:
                    Toast.makeText(context, R.string.sendspin_pair_no_room, Toast.LENGTH_LONG).show();
                    break;
                case NOT_A_TOKEN:
                default:
                    Toast.makeText(context, R.string.sendspin_pair_not_a_token, Toast.LENGTH_LONG).show();
                    break;
            }
            });
        });
        dialog.show();
    }

    /**
     * Scans the QR code of a pairing token with the camera (the camera permission is asked by the scanner when it opens) and enters
     * the text as if it was pasted; the pairing then runs through the same check (the key of the token must be the id of the player of
     * the card). The result comes back through the activity's registry, which is used on the tap, so that no field has to exist before
     * the activity starts.
     */
    private static void scan(Context context, final EditText input, final AlertDialog dialog) {
        if (!(context instanceof ComponentActivity)) {
            return;
        }
        final ActivityResultLauncher<ScanOptions>[] launcher = newLauncherHolder();
        launcher[0] = ((ComponentActivity) context).getActivityResultRegistry().register("sendspin-pair-scan-" + UUID.randomUUID(),
                new ScanContract(), result -> {
                    launcher[0].unregister();
                    String text = result == null ? null : result.getContents();
                    if (text != null && !text.isEmpty()) {
                        input.setText(text);
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                    }
                });
        launcher[0].launch(new ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(context.getString(R.string.sendspin_pair_scan_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(false));
    }

    @SuppressWarnings("unchecked")
    private static ActivityResultLauncher<ScanOptions>[] newLauncherHolder() {
        return new ActivityResultLauncher[1];
    }

    /** The first characters of a client id: enough to tell two devices apart in a message, not a secret (the id is public). */
    static String shortId(String id) {
        if (id == null || id.isEmpty()) {
            return "?";
        }
        return id.length() <= 8 ? id : id.substring(0, 8);
    }
}
