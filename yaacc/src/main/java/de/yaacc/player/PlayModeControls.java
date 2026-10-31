/*
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
package de.yaacc.player;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.MenuItem;
import android.widget.ImageButton;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;

import de.yaacc.R;

/**
 * The repeat and shuffle controls of the player screens: which icon and text show a play mode, and the
 * binding of two buttons to a {@link Player}. The state must be readable without color (the app is
 * gray): repeat one has its own icon ("1"), and an inactive control (repeat off, shuffle off) is drawn
 * half transparent; the content description says the state in words for a screen reader.
 */
public final class PlayModeControls {

    private static final float ALPHA_ACTIVE = 1f;
    private static final float ALPHA_INACTIVE = 0.5f;

    private PlayModeControls() {
    }

    @DrawableRes
    public static int repeatIcon(PlayMode.RepeatMode repeat) {
        return repeat == PlayMode.RepeatMode.ONE ? R.drawable.ic_baseline_repeat_one_32 : R.drawable.ic_baseline_repeat_32;
    }

    @StringRes
    public static int repeatLabel(PlayMode.RepeatMode repeat) {
        switch (repeat) {
            case ALL:
                return R.string.repeat_all;
            case ONE:
                return R.string.repeat_one;
            default:
                return R.string.repeat_off;
        }
    }

    @DrawableRes
    public static int shuffleIcon() {
        return R.drawable.ic_baseline_shuffle_32;
    }

    @StringRes
    public static int shuffleLabel(boolean shuffle) {
        return shuffle ? R.string.shuffle_on : R.string.shuffle_off;
    }

    /** @return 1 for an active control (repeat all or one, shuffle on), 0.5 for an inactive one */
    public static float alpha(boolean active) {
        return active ? ALPHA_ACTIVE : ALPHA_INACTIVE;
    }

    /** Shows the repeat state of the mode as icon, title and (half transparent when off) alpha of a menu item. */
    public static void renderRepeat(Context context, MenuItem item, PlayMode mode) {
        render(context, item, repeatIcon(mode.getRepeat()), repeatLabel(mode.getRepeat()),
                mode.getRepeat() != PlayMode.RepeatMode.OFF);
    }

    /** Shows the shuffle state of the mode as icon, title and alpha of a menu item. */
    public static void renderShuffle(Context context, MenuItem item, PlayMode mode) {
        render(context, item, shuffleIcon(), shuffleLabel(mode.isShuffle()), mode.isShuffle());
    }

    private static void render(Context context, MenuItem item, int icon, int label, boolean active) {
        item.setTitle(label);
        Drawable drawable = ContextCompat.getDrawable(context, icon);
        if (drawable != null) {
            drawable = drawable.mutate();
            drawable.setAlpha(Math.round(alpha(active) * 255));
            item.setIcon(drawable);
        }
    }

    /**
     * Binds a repeat and a shuffle button to a player: shows its play mode, changes it on a click (repeat
     * cycles off, all, one) and follows changes from outside (a controller that sets the play mode).
     * Create it once per activity, {@link #attach} when the player is known, {@link #detach} in onDestroy.
     */
    public static final class Binding implements PropertyChangeListener {

        private final Activity activity;
        private final ImageButton repeatButton;
        private final ImageButton shuffleButton;
        private Player player;

        public Binding(Activity activity, ImageButton repeatButton, ImageButton shuffleButton) {
            this.activity = activity;
            this.repeatButton = repeatButton;
            this.shuffleButton = shuffleButton;
        }

        /** Follows the play mode of the player, null (no player yet) shows both buttons switched off. */
        public void attach(Player newPlayer) {
            detach();
            player = newPlayer;
            boolean enabled = newPlayer != null;
            repeatButton.setEnabled(enabled);
            shuffleButton.setEnabled(enabled);
            if (!enabled) {
                render(PlayMode.NORMAL);
                return;
            }
            newPlayer.addPropertyChangeListener(this);
            repeatButton.setOnClickListener(v -> {
                Player current = player;
                if (current != null) {
                    current.setPlayMode(current.getPlayMode().nextRepeat());
                }
            });
            shuffleButton.setOnClickListener(v -> {
                Player current = player;
                if (current != null) {
                    current.setPlayMode(current.getPlayMode().toggleShuffle());
                }
            });
            render(newPlayer.getPlayMode());
        }

        public void detach() {
            Player current = player;
            player = null;
            if (current != null) {
                current.removePropertyChangeListener(this);
            }
        }

        @Override
        public void propertyChange(PropertyChangeEvent event) {
            if (Player.PROPERTY_PLAY_MODE.equals(event.getPropertyName()) && event.getNewValue() instanceof PlayMode) {
                PlayMode mode = (PlayMode) event.getNewValue();
                activity.runOnUiThread(() -> {
                    if (player != null) {
                        render(mode);
                    }
                });
            }
        }

        private void render(PlayMode mode) {
            repeatButton.setImageResource(repeatIcon(mode.getRepeat()));
            repeatButton.setContentDescription(activity.getString(repeatLabel(mode.getRepeat())));
            repeatButton.setAlpha(alpha(mode.getRepeat() != PlayMode.RepeatMode.OFF));
            shuffleButton.setImageResource(shuffleIcon());
            shuffleButton.setContentDescription(activity.getString(shuffleLabel(mode.isShuffle())));
            shuffleButton.setAlpha(alpha(mode.isShuffle()));
        }
    }
}
