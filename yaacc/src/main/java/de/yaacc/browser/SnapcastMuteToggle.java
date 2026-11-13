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
import android.view.LayoutInflater;
import android.widget.CheckBox;
import android.widget.LinearLayout;

import androidx.core.view.ViewCompat;

import de.yaacc.R;

/**
 * The mute of a Snapcast group or client as the other receiver cards show it (11.1): the speaker / muted-speaker symbol
 * ({@code sl_mute_32}) on a check box that has no box of its own, not a switch and not a plain check box. It always has a
 * description for accessibility ("Mute group", "Mute Kitchen"), and a state text ("Muted" / "Not muted") that follows the
 * state. Thin Android glue; the card's own check box is the same drawable (see {@code browse_receiver_device_item.xml}).
 */
final class SnapcastMuteToggle {

    private SnapcastMuteToggle() {
    }

    /** A new toggle; {@code description} names what it mutes (cleaned display text). */
    static CheckBox create(Context context, String description) {
        // a parent of the kind the toggle is added to (a LinearLayout) gives it the right layout parameters; it is not attached
        LinearLayout parent = new LinearLayout(context);
        CheckBox box = (CheckBox) LayoutInflater.from(context).inflate(R.layout.snapcast_mute_toggle, parent, false);
        describe(context, box, description);
        return box;
    }

    /** Gives a toggle (this one, or the check box of a receiver card) its description and its state text. */
    static void describe(Context context, CheckBox box, String description) {
        box.setContentDescription(description);
        syncState(context, box);
    }

    /** Makes the state text follow the check; call after every {@code setChecked}. */
    static void syncState(Context context, CheckBox box) {
        ViewCompat.setStateDescription(box, context.getString(box.isChecked() ? R.string.snapcast_mute_state_on
                : R.string.snapcast_mute_state_off));
    }

    /** Takes the description and the state text away: a card that was a Snapcast row and is bound to another receiver. */
    static void clear(CheckBox box) {
        box.setContentDescription(null);
        ViewCompat.setStateDescription(box, null);
    }
}
