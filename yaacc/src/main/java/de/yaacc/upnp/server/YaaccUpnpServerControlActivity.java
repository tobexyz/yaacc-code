/*
 * Copyright (C) 2025 Tobias Schoene www.yaacc.de
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
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.switchmaterial.SwitchMaterial;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.yaacc.R;
import de.yaacc.settings.SettingsActivity;
import de.yaacc.upnp.server.contentdirectory.MediaPathFilter;
import de.yaacc.util.AboutActivity;
import de.yaacc.util.NotificationId;
import de.yaacc.util.YaaccLogActivity;

/**
 * Control activity for the yaacc upnp server
 *
 * @author Tobias Schoene (openbit)
 */
public class YaaccUpnpServerControlActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_OPEN_DOCUMENT_TREE = 1001;
    private TreeViewAdapter treeViewAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_yaacc_upnp_server_control);
        SharedPreferences preferences = PreferenceManager
                .getDefaultSharedPreferences(getApplicationContext());
        boolean receiverActive = preferences.getBoolean(getString(R.string.settings_local_server_receiver_chkbx), false);
        CheckBox receiverCheckBox = findViewById(R.id.receiverEnabled);
        receiverCheckBox.setChecked(receiverActive);
        receiverCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            SharedPreferences.Editor editor = preferences.edit();
            editor.putBoolean(getApplicationContext().getString(R.string.settings_local_server_receiver_chkbx), isChecked);
            editor.apply();
        });
        boolean providerActive = preferences.getBoolean(getString(R.string.settings_local_server_provider_chkbx), false);
        CheckBox providerCheckBox = findViewById(R.id.providerEnabled);
        providerCheckBox.setChecked(providerActive);
        providerCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            SharedPreferences.Editor editor = preferences.edit();
            editor.putBoolean(getApplicationContext().getString(R.string.settings_local_server_provider_chkbx), isChecked);
            editor.apply();
        });
        boolean proxyActive = preferences.getBoolean(getString(R.string.settings_local_server_proxy_chkbx), false);
        CheckBox proxyCheckBox = findViewById(R.id.proxyEnabled);
        proxyCheckBox.setChecked(proxyActive);
        proxyCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            SharedPreferences.Editor editor = preferences.edit();
            editor.putBoolean(getApplicationContext().getString(R.string.settings_local_server_proxy_chkbx), isChecked);
            editor.apply();
        });

        boolean filterActive = preferences.getBoolean(getString(R.string.settings_local_server_media_filter_chkbx), true);
        CheckBox mediaFilterCheckBox = findViewById(R.id.filterEnabled);
        mediaFilterCheckBox.setChecked(filterActive);
        mediaFilterCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            SharedPreferences.Editor editor = preferences.edit();
            editor.putBoolean(getApplicationContext().getString(R.string.settings_local_server_media_filter_chkbx), isChecked);
            editor.apply();
        });


        SwitchMaterial localServerEnabledSwitch = findViewById(R.id.serverOnOff);
        localServerEnabledSwitch.setChecked(preferences.getBoolean(getApplicationContext().getString(R.string.settings_local_server_chkbx), false));
        localServerEnabledSwitch.setOnClickListener((v -> {
            SharedPreferences.Editor editor = preferences.edit();
            editor.putBoolean(v.getContext().getString(R.string.settings_local_server_chkbx), localServerEnabledSwitch.isChecked());
            editor.apply();
            if (localServerEnabledSwitch.isChecked()) {
                start();
            } else {
                stop();
            }
        }));
        Button resetButton = findViewById(R.id.sharedFoldersReset);
        resetButton.setOnClickListener(v -> MediaPathFilter.resetMediaPaths(getApplicationContext()));

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
        treeViewAdapter = new TreeViewAdapter(factory);
        recyclerView.setAdapter(treeViewAdapter);
        buildFileSystemTree(treeViewAdapter);
    }


    private void buildFileSystemTree(TreeViewAdapter treeViewAdapter) {
        List<TreeNode> fileRoots = new ArrayList<>();
        File externalStorageRoot = Environment.getExternalStorageDirectory(); // Or any other root path
        // Check if external storage is readable
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState()) ||
                Environment.MEDIA_MOUNTED_READ_ONLY.equals(Environment.getExternalStorageState())) {

            if (externalStorageRoot.exists() && externalStorageRoot.isDirectory()) {
                // Add top-level directories from the chosen root
                File[] topLevelFiles = externalStorageRoot.listFiles();
                if (topLevelFiles != null) {
                    for (File file : topLevelFiles) {
                        TreeNode node = buildFileSystemNode(file, R.layout.file_list_item);
                        if (node != null) {
                            fileRoots.add(node);
                        }
                    }
                } else {
                    Log.e(getClass().getName(), "Could not list files in root: " + externalStorageRoot.getAbsolutePath());
                }
            } else {
                Log.e(getClass().getName(), "Root directory does not exist or is not a directory: " + externalStorageRoot.getAbsolutePath());
            }
        } else {
            Log.e(getClass().getName(), "External storage not readable.");
        }

        if (fileRoots.isEmpty()) {
            Log.w(getClass().getName(), "No file system roots found or storage unavailable. Adding a placeholder.");
        }
// --- SAF: lade persistierte Tree URIs aus SharedPreferences und füge als DocumentFile Wurzeln hinzu ---
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        Set<String> safUris = prefs.getStringSet("saf_tree_uris", new HashSet<>());
        if (safUris != null) {
            for (String uriString : safUris) {
                try {
                    Uri uri = Uri.parse(uriString);
                    DocumentFile docRoot = DocumentFile.fromTreeUri(this, uri);
                    if (docRoot != null && docRoot.exists()) {
                        TreeNode node = buildFileSystemNode(docRoot, R.layout.file_list_item);
                        if (node != null) {
                            fileRoots.add(node);
                        }
                    } else {
                        Log.w(getClass().getName(), "SAF root not accessible: " + uriString);
                    }
                } catch (Exception e) {
                    Log.e(getClass().getName(), "Error restoring SAF uri: " + uriString, e);
                }
            }
        }

        // Wenn keine Wurzeln gefunden wurden, starte SAF-Picker, damit Benutzer USB/externen Pfad wählen kann
        if (fileRoots.isEmpty()) {
            Log.w(getClass().getName(), "No file system roots found or storage unavailable. Starting SAF picker.");
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, REQUEST_CODE_OPEN_DOCUMENT_TREE);
        }
        treeViewAdapter.updateTreeNodes(fileRoots);


        treeViewAdapter.setTreeNodeClickListener((treeNode, nodeView) -> {
            Log.d(getClass().getName(), "Click on TreeNode with value " + treeNode.getValue().toString());
            Object value = treeNode.getValue();
            if (value instanceof File) {
                File file = (File) value;

                if (file.isDirectory() && file.listFiles() != null && treeNode.getChildren().size() != file.listFiles().length) {
                    File[] children = file.listFiles();
                    if (children != null) {
                        for (File childFile : children) {
                            TreeNode childNode = buildFileSystemNode(childFile, treeNode.getLayoutId());
                            if (childNode != null) {
                                treeNode.addChild(childNode);
                            }
                        }
                    }
                }
                Log.d(getClass().getName(), "Clicked on file: " + file.getAbsolutePath());
            } else if (value instanceof DocumentFile) {
                DocumentFile doc = (DocumentFile) value;
                if (doc.isDirectory()) {
                    DocumentFile[] children = doc.listFiles();
                    if (children != null && treeNode.getChildren().size() != children.length) {
                        for (DocumentFile childDoc : children) {
                            TreeNode childNode = buildFileSystemNode(childDoc, treeNode.getLayoutId());
                            if (childNode != null) {
                                treeNode.addChild(childNode);
                            }
                        }
                    }
                }
                Log.d(getClass().getName(), "Clicked on document file: " + doc.getUri());
            }
        });

        treeViewAdapter.setTreeNodeLongClickListener((treeNode, nodeView) -> {
            Log.d(getClass().getName(), "LongClick on TreeNode with value " + treeNode.getValue().toString());
            return true;
        });
    }

    /**
     * Recursively builds a TreeNode structure from the file system or a DocumentFile.
     *
     * @param fileObj  The current File or DocumentFile.
     * @param layoutId The layout resource ID for the TreeNode.
     * @return A TreeNode representing the file/directory, or null if it should be skipped.
     */
    private TreeNode buildFileSystemNode(Object fileObj, int layoutId) {
        if (fileObj == null) {
            return null;
        }

        if (fileObj instanceof File) {
            File file = (File) fileObj;
            if (!file.exists()) {
                return null;
            }
            return new TreeNode(file, layoutId);
        } else if (fileObj instanceof DocumentFile) {
            DocumentFile doc = (DocumentFile) fileObj;
            if (!doc.exists()) {
                return null;
            }
            return new TreeNode(doc, layoutId);
        }

        return null;
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
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_OPEN_DOCUMENT_TREE && resultCode == RESULT_OK) {
            if (data != null) {
                Uri treeUri = data.getData();
                if (treeUri != null) {
                    try {
                        final int takeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        getContentResolver().takePersistableUriPermission(treeUri, takeFlags);
                    } catch (Exception e) {
                        Log.w(getClass().getName(), "Could not take persistable uri permission", e);
                    }
                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
                    Set<String> uriSet = prefs.getStringSet("saf_tree_uris", new HashSet<>());
                    if (uriSet == null) {
                        uriSet = new HashSet<>();
                    } else {
                        uriSet = new HashSet<>(uriSet);
                    }
                    uriSet.add(treeUri.toString());
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putStringSet("saf_tree_uris", uriSet);
                    editor.apply();

                    // rebuild tree with newly added SAF root
                    buildFileSystemTree(treeViewAdapter);
                }
            }
        }
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
        } else if (item.getItemId() == R.id.yaacc_log) {
            YaaccLogActivity.showLog(this);
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
