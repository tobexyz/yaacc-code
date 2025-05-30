/*
 * Copyright (C) 2013 Tobias Schoene www.yaacc.de
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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import de.yaacc.R;
import de.yaacc.settings.SettingsActivity;
import de.yaacc.util.AboutActivity;
import de.yaacc.util.YaaccLogActivity;

/**
 * A multi content player activity based on the multi content player.
 *
 * @author Tobias Schoene (openbit)
 */
public class MultiContentPlayerActivity extends AppCompatActivity implements ServiceConnection {

    private PlayerService playerService;

    public void onServiceConnected(ComponentName className, IBinder binder) {
        if (binder instanceof PlayerService.PlayerServiceBinder) {
            Log.d(getClass().getName(), "PlayerService connected");
            playerService = ((PlayerService.PlayerServiceBinder) binder).getService();
            initialize();
        }
    }
    //binder comes from server to communicate with method's of

    public void onServiceDisconnected(ComponentName className) {
        Log.d(getClass().getName(), "PlayerService disconnected");
        playerService = null;
    }

    protected void initialize() {
        Player player = getPlayer();

        ImageButton btnPrev = findViewById(R.id.multiContentPlayerActivityControlPrev);
        ImageButton btnNext = findViewById(R.id.multiContentPlayerActivityControlNext);
        ImageButton btnStop = findViewById(R.id.multiContentPlayerActivityControlStop);
        ImageButton btnPlay = findViewById(R.id.multiContentPlayerActivityControlPlay);
        ImageButton btnPause = findViewById(R.id.multiContentPlayerActivityControlPause);
        ImageButton btnPlaylist = (ImageButton) findViewById(R.id.multiContentPlayerActivityControlPlaylist);
        ImageButton btnExit = findViewById(R.id.multiContentPlayerActivityControlExit);
        if (player == null) {
            btnPrev.setActivated(false);
            btnNext.setActivated(false);
            btnStop.setActivated(false);
            btnPlay.setActivated(false);
            btnPause.setActivated(false);
            btnPlaylist.setActivated(false);
            btnExit.setActivated(false);
        } else {
            btnPrev.setActivated(true);
            btnNext.setActivated(true);
            btnStop.setActivated(true);
            btnPlay.setActivated(true);
            btnPause.setActivated(true);
            btnPlaylist.setActivated(true);
            btnExit.setActivated(true);
        }
        btnPrev.setOnClickListener(v -> {
            Player p = getPlayer();
            if (p != null) {
                p.previous();
            }

        });
        btnNext.setOnClickListener(v -> {
            Player p = getPlayer();
            if (p != null) {
                p.next();
            }

        });
        btnPlay.setOnClickListener(v -> {
            Player p = getPlayer();
            if (p != null) {
                p.play();
            }

        });
        btnPause.setOnClickListener(v -> {
            Player p = getPlayer();
            if (p != null) {
                p.pause();
            }

        });
        btnStop.setOnClickListener(v -> {
            Player p = getPlayer();
            if (p != null) {
                p.stop();
                MultiContentPlayerActivity.this.runOnUiThread(() -> Toast.makeText(
                                MultiContentPlayerActivity.this,
                                R.string.stop_not_supported
                                , Toast.LENGTH_LONG)
                        .show());
            }

        });
        btnExit.setOnClickListener(v -> exit());
        btnPlaylist.setOnClickListener(v -> showPlaylistDialog());
    }

    public void showPlaylistDialog() {
        PlaylistDialogFragment.show(getSupportFragmentManager(), getPlayer());
    }

    private void exit() {
        Player player = getPlayer();
        if (player != null) {
            player.exit();
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindService(this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_multi_content_player);
        this.bindService(new Intent(this, PlayerService.class),
                this, Context.BIND_AUTO_CREATE);
    }

    private Player getPlayer() {
        return playerService.getFirstCurrentPlayerOfType(MultiContentPlayer.class);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.activity_multi_content_player, menu);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_exit) {

            exit();
            return true;
        }
        if (item.getItemId() == R.id.menu_settings) {
            Intent i = new Intent(this, SettingsActivity.class);
            startActivity(i);
            return true;
        }
        if (item.getItemId() == R.id.yaacc_about) {
            AboutActivity.showAbout(this);
            return true;
        }
        if (item.getItemId() == R.id.yaacc_log) {
            YaaccLogActivity.showLog(this);
            return true;
        }
        return super.onOptionsItemSelected(item);

    }

}
