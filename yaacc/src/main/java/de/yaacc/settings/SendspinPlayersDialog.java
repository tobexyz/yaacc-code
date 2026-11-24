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

import android.content.Context;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import de.yaacc.R;
import de.yaacc.sendspin.SendspinAddress;
import de.yaacc.sendspin.SendspinHub;
import de.yaacc.sendspin.SendspinPlayers;
import de.yaacc.sendspin.hub.Endpoint;

import java.util.List;

/**
 * The list of the Sendspin players of the user: an address field with an add button, and one row per player with a button to
 * remove it. The same pattern as the other protocol dialogs (plain views in an {@link AlertDialog}); the logic is in
 * {@link SendspinPlayers}.
 */
final class SendspinPlayersDialog {

    private final Context context;
    private final SendspinHub hub;
    private final boolean servers;
    private final EditText input;
    private final LinearLayout list;

    private SendspinPlayersDialog(Context context, boolean servers) {
        this.context = context;
        this.servers = servers;
        this.hub = SendspinHub.getShared(context);
        float density = context.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, (int) (8 * density), pad, 0);
        input = new EditText(context);
        input.setSingleLine(true);
        input.setHint(servers ? R.string.sendspin_servers_hint : R.string.sendspin_players_hint);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(270)});
        root.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button add = new Button(context);
        add.setText(R.string.sendspin_players_add);
        add.setOnClickListener(v -> add());
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        addParams.gravity = Gravity.END;
        root.addView(add, addParams);
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        refresh();
        android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.addView(root);
        new AlertDialog.Builder(context)
                .setTitle(servers ? R.string.settings_sendspin_servers_title : R.string.settings_sendspin_players_title)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    static void show(Context context) {
        new SendspinPlayersDialog(context, false);
    }

    /** The same dialog for the addresses of the Sendspin servers that the phone controls. */
    static void showServers(Context context) {
        new SendspinPlayersDialog(context, true);
    }

    private SendspinPlayers addresses() {
        return servers ? hub.controllers().servers() : hub.players();
    }

    private void add() {
        SendspinPlayers.AddResult result = addresses().add(input.getText().toString());
        switch (result) {
            case ADDED:
                input.setText("");
                refresh();
                hub.notifyListChanged();
                break;
            case ALREADY_THERE:
                toast(R.string.sendspin_players_already);
                break;
            case TOO_MANY:
                toast(servers ? R.string.sendspin_servers_too_many : R.string.sendspin_players_too_many);
                break;
            case NOT_AN_ADDRESS:
            default:
                toast(R.string.sendspin_players_bad_address);
                break;
        }
    }

    private void toast(int text) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show();
    }

    private void refresh() {
        list.removeAllViews();
        List<Endpoint> players = addresses().list();
        float density = context.getResources().getDisplayMetrics().density;
        if (players.isEmpty()) {
            TextView none = new TextView(context);
            none.setText(servers ? R.string.sendspin_servers_none : R.string.sendspin_players_none);
            none.setPadding(0, (int) (12 * density), 0, (int) (12 * density));
            list.addView(none);
        }
        for (final Endpoint endpoint : players) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView address = new TextView(context);
            address.setText(SendspinAddress.format(endpoint));
            address.setTextAppearance(android.R.style.TextAppearance_Material_Medium);
            row.addView(address, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Button remove = new Button(context);
            remove.setText("✕");
            remove.setContentDescription(context.getString(R.string.sendspin_players_remove, SendspinAddress.format(endpoint)));
            remove.setOnClickListener(v -> {
                if (servers) {
                    hub.controllers().remove(endpoint);
                    hub.notifyListChanged();
                } else {
                    hub.remove(endpoint);
                }
                refresh();
            });
            row.addView(remove);
            list.addView(row);
        }
        if (servers) {
            return; // the pairings and approvals are those of players
        }
        addIds(R.string.sendspin_paired_title, hub.pairedIds(), R.string.sendspin_unpair, true);
        addIds(R.string.sendspin_approved_title, hub.approvedIds(), R.string.sendspin_revoke, false);
    }

    /** A heading and one row for each id with a button that removes the pairing or the approval of that player. */
    private void addIds(int title, List<String> ids, int removeDescription, boolean pairing) {
        float density = context.getResources().getDisplayMetrics().density;
        TextView heading = new TextView(context);
        heading.setText(title);
        heading.setTextAppearance(android.R.style.TextAppearance_Material_Subhead);
        heading.setPadding(0, (int) (20 * density), 0, (int) (4 * density));
        list.addView(heading);
        if (ids.isEmpty()) {
            TextView none = new TextView(context);
            none.setText(R.string.sendspin_list_none);
            list.addView(none);
            return;
        }
        for (final String id : ids) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            String shortId = id.substring(0, 8) + "…"; // the id of a player is its public key: enough to tell the entries apart
            String known = hub.nameOfPlayer(id);
            TextView label = new TextView(context);
            label.setText(known == null ? shortId : known + "  " + shortId);
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Button remove = new Button(context);
            remove.setText("✕");
            remove.setContentDescription(context.getString(removeDescription, known == null ? shortId : known));
            remove.setOnClickListener(v -> {
                if (pairing) {
                    hub.unpair(id);
                } else {
                    hub.revokeApproval(id);
                }
                refresh();
            });
            row.addView(remove);
            list.addView(row);
        }
    }
}
