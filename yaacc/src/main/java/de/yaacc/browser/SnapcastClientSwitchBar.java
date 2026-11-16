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
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.Toast;

import com.google.android.material.switchmaterial.SwitchMaterial;

import de.yaacc.R;
import de.yaacc.multiroom.client.SnapcastClientPower;
import de.yaacc.multiroom.client.SnapcastClientPowerTexts;
import de.yaacc.multiroom.client.SnapcastClientPowers;
import de.yaacc.settings.SettingsActivity;

/**
 * The row of the phone's own Snapcast client at the bottom of the first page (task 12.17): label, switch and gear like the one of the
 * UPnP server, with <b>no subtitle</b> (task 12.19, the owner). The state in words, with the reason of a session that ended, is the
 * state description of the switch (what TalkBack reads after its label); the listen test dialog shows the same text. The gear opens the Snapcast client section of the settings. Thin Android glue; the state, the start and the stop are in
 * {@link SnapcastClientPower}, the words in {@link SnapcastClientPowerTexts}. The row is shown only when the option is on and a server
 * is known ({@link SnapcastClientPower#offered()}). While the page is started the state follows the session (a poll of one second on
 * the main thread, removed with the page).
 */
final class SnapcastClientSwitchBar {

    private static final long REFRESH_MS = 1000L;

    private final Context context;
    private final View row;
    private final SwitchMaterial toggle;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean started;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            if (!started) {
                return;
            }
            bind();
            main.postDelayed(this, REFRESH_MS);
        }
    };

    private final CompoundButton.OnCheckedChangeListener onToggled = (button, checked) -> toggled(checked);

    /** @return the bar, or null when the layout has no row (the classic layout) */
    static SnapcastClientSwitchBar of(Context context, View root) {
        View row = root.findViewById(R.id.serverListSnapcastClientRow);
        if (row == null) {
            return null;
        }
        return new SnapcastClientSwitchBar(context.getApplicationContext(), row);
    }

    private SnapcastClientSwitchBar(Context context, View row) {
        this.context = context;
        this.row = row;
        this.toggle = row.findViewById(R.id.serverListSnapcastClientEnabled);
        ImageButton gear = row.findViewById(R.id.serverListSnapcastClientSettings);
        gear.setOnClickListener(v -> v.getContext().startActivity(SettingsActivity.snapcastClientIntent(v.getContext())));
        toggle.setOnCheckedChangeListener(onToggled);
    }

    /** The page is started: show the state now and keep it current. */
    void start() {
        started = true;
        main.removeCallbacks(refresh);
        refresh.run();
    }

    /** The page is stopped: nothing is polled any more. */
    void stop() {
        started = false;
        main.removeCallbacks(refresh);
    }

    /** Shows or hides the row and sets the switch and its state description from the current state. */
    void bind() {
        SnapcastClientPower power = SnapcastClientPowers.of(context);
        boolean offered = power.offered();
        row.setVisibility(offered ? View.VISIBLE : View.GONE);
        if (!offered) {
            return;
        }
        SnapcastClientPower.State state = power.state();
        // setting it from the code must not start or stop anything
        toggle.setOnCheckedChangeListener(null);
        toggle.setChecked(power.isOn());
        toggle.setOnCheckedChangeListener(onToggled);
        // the row has no visible state text: the state in words, with the reason of a session that ended ("Stopped: <reason>", also a restore
        // that failed at the start), is the state description of the switch, which TalkBack reads with its label (the content description)
        androidx.core.view.ViewCompat.setStateDescription(toggle, SnapcastClientPowerTexts.stateText(context, state));
        if (state.shown == SnapcastClientPower.Shown.STOPPED && state.unseenFailure) {
            power.markEndingSeen(); // the row had it for the screen reader; the listen test dialog shows it as text
        }
    }

    private void toggled(boolean checked) {
        SnapcastClientPower.Outcome outcome = SnapcastClientPowers.of(context).setOn(checked);
        String reason = SnapcastClientPowerTexts.refusalText(context, outcome);
        if (reason != null) {
            // the state stays on (the intent); the reason is shown like the test screen does, the row shows "stopped"
            Toast.makeText(context, reason, Toast.LENGTH_LONG).show();
        }
        bind();
    }
}
