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
package de.yaacc.upnp.server;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.CheckBox;

import androidx.appcompat.app.AppCompatActivity;

import de.yaacc.R;
import de.yaacc.settings.SettingsActivity;
import de.yaacc.util.AboutActivity;
import de.yaacc.util.NotificationId;

/**
 * Control activity for the yaacc upnp server
 *
 * @author Tobias Schoene (openbit)
 */
public class YaaccUpnpServerControlActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_yaacc_upnp_server_control);
        // initialize buttons
        Button startButton = findViewById(R.id.startServer);
        startButton.setOnClickListener(v -> start());
        Button stopButton = findViewById(R.id.stopServer);
        stopButton.setOnClickListener(v -> stop());
        SharedPreferences preferences = PreferenceManager
                .getDefaultSharedPreferences(getApplicationContext());
        boolean receiverActive = preferences.getBoolean(getString(R.string.settings_local_server_receiver_chkbx), false);
        Log.d(getClass().getName(), "receiverActive: " + receiverActive);
        CheckBox receiverCheckBox = findViewById(R.id.receiverEnabled);
        receiverCheckBox.setChecked(receiverActive);
        boolean providerActive = preferences.getBoolean(getString(R.string.settings_local_server_provider_chkbx), false);
        Log.d(getClass().getName(), "providerActive: " + providerActive);
        CheckBox providerCheckBox = findViewById(R.id.providerEnabled);
        providerCheckBox.setChecked(providerActive);


    }

    private void start() {

        YaaccUpnpServerControlActivity.this.startForegroundService(new Intent(getApplicationContext(),
                YaaccUpnpServerService.class));


        SharedPreferences preferences = PreferenceManager
                .getDefaultSharedPreferences(getApplicationContext());
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean(getString(R.string.settings_local_server_chkbx), true);
        editor.apply();
    }

    private void stop() {
        YaaccUpnpServerControlActivity.this.stopService(new Intent(getApplicationContext(),
                YaaccUpnpServerService.class));
        SharedPreferences preferences = PreferenceManager
                .getDefaultSharedPreferences(getApplicationContext());
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean(getString(R.string.settings_local_server_chkbx), false);
        editor.apply();
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.activity_yaacc_upnp_server_control,
                menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_exit) {
            exit();
            return true;
        } else if (item.getItemId() == R.id.menu_settings) {
            Intent i = new Intent(this, SettingsActivity.class);
            startActivity(i);
            return true;
        } else if (item.getItemId() == R.id.yaacc_about) {
            AboutActivity.showAbout(this);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void exit() {
        stop();
        //FIXME work around to be fixed with new ui
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        // mId allows you to update the notification later on.
        mNotificationManager.cancel(NotificationId.UPNP_SERVER.getId());
        finish();
    }


}
