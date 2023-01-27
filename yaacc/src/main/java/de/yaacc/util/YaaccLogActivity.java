/*
 *
 * Copyright (C) 2014 Tobias Schoene www.yaacc.de
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
package de.yaacc.util;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import de.yaacc.R;

public class YaaccLogActivity extends AppCompatActivity {
    public static void showLog(AppCompatActivity activity) {
        activity.startActivity(new Intent(activity, YaaccLogActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_yaacc_log);


        displayLog();
    }


    /*
     * (non-Javadoc)
     *
     * @see android.app.Activity#onResume()
     */
    @Override
    protected void onResume() {
        super.onResume();
        displayLog();
    }

    private void displayLog() {
        TextView textView = findViewById(R.id.yaaccLog_content);

        try {
            SharedPreferences preferences = PreferenceManager
                    .getDefaultSharedPreferences(this);
            String logLevel = preferences.getString(
                    getString(R.string.settings_log_level_key),
                    "E");
            Process process = Runtime.getRuntime().exec("logcat -d *:" + logLevel);
            BufferedReader bufferedReader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));

            StringBuilder log = new StringBuilder();
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                log.append(line);
                log.append("\n");
            }

            textView.setText(log.toString());
            textView.setTextIsSelectable(true);
            ScrollView scrollView = findViewById(R.id.yaaccLog_scrollView);
            scrollView.post(() -> {
                scrollView.fullScroll(View.FOCUS_DOWN);
            });

        } catch (IOException e) {
            textView.setText("Error while reading log: " + e.getMessage());
        }
    }


}
