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
import android.util.Log;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

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
        TextView localServerControlInterface = findViewById(R.id.localServerControlInterface);
        String[] ipConfig = YaaccUpnpServerService.getIfAndIpAddress(this);
        localServerControlInterface.setText(ipConfig[1] + "@" + ipConfig[0]);

        RecyclerView recyclerView = findViewById(R.id.folders_recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setNestedScrollingEnabled(false);
        TypedValue typedValue = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.colorBackground, typedValue, true);
        recyclerView.setBackgroundColor(typedValue.data);

        TreeViewHolderFactory factory = (v, layout) -> new TreeViewHolder(v);

        TreeViewAdapter treeViewAdapter = new TreeViewAdapter(factory);
        recyclerView.setAdapter(treeViewAdapter);

        TreeNode javaDirectory = new TreeNode("Java", R.layout.file_list_item);
        javaDirectory.addChild(new TreeNode("FileJava1.java", R.layout.file_list_item));
        javaDirectory.addChild(new TreeNode("FileJava2.java", R.layout.file_list_item));
        javaDirectory.addChild(new TreeNode("FileJava3.java", R.layout.file_list_item));

        TreeNode gradleDirectory = new TreeNode("Gradle", R.layout.file_list_item);
        gradleDirectory.addChild(new TreeNode("FileGradle1.gradle", R.layout.file_list_item));
        gradleDirectory.addChild(new TreeNode("FileGradle2.gradle", R.layout.file_list_item));
        gradleDirectory.addChild(new TreeNode("FileGradle3.gradle", R.layout.file_list_item));

        javaDirectory.addChild(gradleDirectory);

        TreeNode lowLevelRoot = new TreeNode("LowLevel", R.layout.file_list_item);

        TreeNode cDirectory = new TreeNode("C", R.layout.file_list_item);
        cDirectory.addChild(new TreeNode("FileC1.c", R.layout.file_list_item));
        cDirectory.addChild(new TreeNode("FileC2.c", R.layout.file_list_item));
        cDirectory.addChild(new TreeNode("FileC3.c", R.layout.file_list_item));

        TreeNode cppDirectory = new TreeNode("Cpp", R.layout.file_list_item);
        cppDirectory.addChild(new TreeNode("FileCpp1.cpp", R.layout.file_list_item));
        cppDirectory.addChild(new TreeNode("FileCpp2.cpp", R.layout.file_list_item));
        cppDirectory.addChild(new TreeNode("FileCpp3.cpp", R.layout.file_list_item));

        TreeNode goDirectory = new TreeNode("Go", R.layout.file_list_item);
        goDirectory.addChild(new TreeNode("FileGo1.go", R.layout.file_list_item));
        goDirectory.addChild(new TreeNode("FileGo2.go", R.layout.file_list_item));
        goDirectory.addChild(new TreeNode("FileGo3.go", R.layout.file_list_item));

        lowLevelRoot.addChild(cDirectory);
        lowLevelRoot.addChild(cppDirectory);
        lowLevelRoot.addChild(goDirectory);

        TreeNode cSharpDirectory = new TreeNode("C#", R.layout.file_list_item);
        cSharpDirectory.addChild(new TreeNode("FileCs1.cs", R.layout.file_list_item));
        cSharpDirectory.addChild(new TreeNode("FileCs2.cs", R.layout.file_list_item));
        cSharpDirectory.addChild(new TreeNode("FileCs3.cs", R.layout.file_list_item));

        TreeNode gitFolder = new TreeNode(".git", R.layout.file_list_item);

        List<TreeNode> fileRoots = new ArrayList<>();
        fileRoots.add(javaDirectory);
        fileRoots.add(lowLevelRoot);
        fileRoots.add(cSharpDirectory);
        fileRoots.add(gitFolder);

        treeViewAdapter.updateTreeNodes(fileRoots);

        treeViewAdapter.setTreeNodeClickListener((treeNode, nodeView) -> {
            Log.d(getClass().getName(), "Click on TreeNode with value " + treeNode.getValue().toString());
        });

        treeViewAdapter.setTreeNodeLongClickListener((treeNode, nodeView) -> {
            Log.d(getClass().getName(), "LongClick on TreeNode with value " + treeNode.getValue().toString());
            return true;
        });


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
