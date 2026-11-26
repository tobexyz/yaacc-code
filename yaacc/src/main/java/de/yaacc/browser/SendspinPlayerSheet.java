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
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.List;

import de.yaacc.R;
import de.yaacc.sendspin.SendspinAddress;
import de.yaacc.sendspin.SendspinHub;
import de.yaacc.sendspin.SendspinReceiverDevice;
import de.yaacc.sendspin.SendspinRowText;
import de.yaacc.sendspin.hub.HubConnection;
import de.yaacc.sendspin.session.SendspinMessages;

/**
 * The sheet of the gear on the card of a Sendspin player: who it is (name, address, whether it connected to this phone), how far it is
 * trusted (paired, approved or not yet; unencrypted), what it plays, and the buttons for what the operator may undo or has to do (enter
 * the pairing token, forget the pairing, withdraw the approval). Plain views in an {@link AlertDialog}; the texts that come from the
 * player are cleaned.
 */
final class SendspinPlayerSheet {

    private SendspinPlayerSheet() {
    }

    static void show(final Context context, final SendspinReceiverDevice device) {
        if (!(context instanceof Activity) || ((Activity) context).isFinishing()) {
            return;
        }
        final SendspinHub hub = SendspinHub.getShared(context);
        final HubConnection connection = hub.connection(device.getEndpoint());
        float density = context.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, (int) (8 * density), pad, 0);

        final String announced = connection == null || connection.hello() == null ? null : connection.hello().name;
        addText(root, context.getString(R.string.sendspin_player_info_address, SendspinAddress.format(device.getEndpoint())));
        if (device.getEndpoint().isInbound()) {
            addText(root, context.getString(R.string.sendspin_player_info_inbound));
        }
        final String clientId = connection == null ? null : connection.clientId();
        boolean paired = clientId != null && hub.pairedIds().contains(clientId);
        boolean approved = clientId != null && hub.approvedIds().contains(clientId);
        if (connection != null && connection.isLegacy()) {
            addText(root, context.getString(R.string.sendspin_player_info_legacy));
        }
        if (connection != null && clientId != null) {
            addText(root, context.getString(paired ? R.string.sendspin_player_info_paired
                    : approved ? R.string.sendspin_player_info_approved : R.string.sendspin_player_info_not_approved));
        }
        SendspinMessages.ClientHello hello = connection == null ? null : connection.hello();
        if (hello != null && !hello.playerFormats.isEmpty()) {
            List<String> formats = new ArrayList<>();
            for (SendspinMessages.Format format : hello.playerFormats) {
                formats.add(SendspinRowText.clean(format.codec) + " " + format.sampleRate + " Hz, " + format.bitDepth + " bit, " + format.channels
                        + " ch");
            }
            addText(root, context.getString(R.string.sendspin_player_info_audio, join(formats)));
        }

        final AlertDialog[] dialog = new AlertDialog[1];
        SendspinRowText.State state = SendspinRowText.stateOf(connection == null ? null : connection.phase(),
                connection == null ? null : connection.endReason(),
                connection == null ? HubConnection.PairingFailure.NONE : connection.pairingFailure(),
                connection != null && connection.isPaired(), false, false);
        if (SendspinRowText.offersTokenEntry(state, connection != null && clientId != null, paired)) {
            root.addView(button(context, R.string.sendspin_player_enter_token, () -> {
                dismiss(dialog);
                SendspinPairDialog.show(context, device.getEndpoint(), announced);
            }));
        }
        if (paired) {
            root.addView(button(context, R.string.sendspin_player_forget_pairing, () -> {
                dismiss(dialog);
                hub.unpair(clientId);
            }));
        } else if (approved) {
            root.addView(button(context, R.string.sendspin_player_revoke, () -> {
                dismiss(dialog);
                hub.revokeApproval(clientId);
            }));
        }
        String name = SendspinRowText.clean(announced);
        dialog[0] = new AlertDialog.Builder(context)
                .setTitle(name.isEmpty() ? device.getName() : name)
                .setView(root)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private static void dismiss(AlertDialog[] dialog) {
        if (dialog[0] != null) {
            dialog[0].dismiss();
        }
    }

    private static String join(List<String> parts) {
        StringBuilder text = new StringBuilder();
        for (String part : parts) {
            if (text.length() > 0) {
                text.append("; ");
            }
            text.append(part);
        }
        return text.toString();
    }

    private static void addText(LinearLayout root, String text) {
        TextView view = new TextView(root.getContext());
        view.setText(text);
        view.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        view.setPadding(0, 0, 0, (int) (6 * root.getContext().getResources().getDisplayMetrics().density));
        root.addView(view);
    }

    private static Button button(Context context, int text, final Runnable action) {
        Button button = new Button(context);
        button.setText(text);
        button.setOnClickListener(v -> action.run());
        button.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return button;
    }
}
