/*
 * Copyright (C) 2013 www.yaacc.de 
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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ImageButton;

import de.yaacc.R;
import de.yaacc.settings.SettingsActivity;
import de.yaacc.util.AboutActivity;
import de.yaacc.util.YaaccLogActivity;

/**
 * A music player activity based on a background music service.
 *
 * @author Tobias Schoene (openbit)
 */
public class ThirdPartieMusicPlayerActivity extends Activity implements ServiceConnection {

    private PlayerService playerService;
    public void onServiceConnected(ComponentName className, IBinder binder) {
        if(binder instanceof PlayerService.PlayerServiceBinder) {
            Log.d(getClass().getName(), "PlayerService connected");
            playerService = ((PlayerService.PlayerServiceBinder) binder).getService();
            initialize();
        }
    }
    //binder comes from server to communicate with method's of

    public void onServiceDisconnected(ComponentName className) {
        Log.d(getClass().getName(),"PlayerService disconnected");
        playerService = null;
    }

    protected void initialize(){
        // initialize buttons
        Player player = getPlayer();
        ImageButton btnPrev = (ImageButton) findViewById(R.id.thirdPratieMusicActivityControlPrev);
        ImageButton btnNext = (ImageButton) findViewById(R.id.thirdPratieMusicActivityControlNext);
        ImageButton btnStop = (ImageButton) findViewById(R.id.thirdPratieMusicActivityControlStop);
        ImageButton btnPlay = (ImageButton) findViewById(R.id.thirdPratieMusicActivityControlPlay);
        ImageButton btnPause = (ImageButton) findViewById(R.id.thirdPratieMusicActivityControlPause);
        ImageButton btnExit = (ImageButton) findViewById(R.id.thirdPratieMusicActivityControlExit);
        if (player == null) {
            btnPrev.setActivated(false);
            btnNext.setActivated(false);
            btnStop.setActivated(false);
            btnPlay.setActivated(false);
            btnPause.setActivated(false);
            btnExit.setActivated(false);
        } else {
            btnPrev.setActivated(true);
            btnNext.setActivated(true);
            btnStop.setActivated(true);
            btnPlay.setActivated(true);
            btnPause.setActivated(true);
            btnExit.setActivated(true);
        }
        btnPrev.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                Player player = getPlayer();
                if (player != null) {
                    player.previous();
                }

            }
        });
        btnNext.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                Player player = getPlayer();
                if (player != null) {
                    player.next();
                }

            }
        });
        btnPlay.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                Player player = getPlayer();
                if (player != null) {
                    player.play();
                }

            }
        });
        btnPause.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                Player player = getPlayer();
                if (player != null) {
                    player.pause();
                }

            }
        });
        btnStop.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                Player player = getPlayer();
                if (player != null) {
                    player.stop();
                }

            }
        });
        btnExit.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                Player player = getPlayer();
                if (player != null) {
                    player.exit();
                }
                finish();
            }
        });
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        this.bindService(new Intent(this, PlayerService.class),
                this, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        this.bindService(new Intent(this, PlayerService.class),
                this, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_third_partie_music_player);
        this.bindService(new Intent(this, PlayerService.class),
                this, Context.BIND_AUTO_CREATE);
    }

    private Player getPlayer() {
        return playerService
                .getFirstCurrentPlayerOfType(LocalThirdPartieMusicPlayer.class);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.activity_third_partie_music_player, menu);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_settings:
                Intent i = new Intent(this, SettingsActivity.class);
                startActivity(i);
                return true;
            case R.id.yaacc_about:
                AboutActivity.showAbout(this);
                return true;
            case R.id.yaacc_log:
                YaaccLogActivity.showLog(this);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }
}
