/*
 * Copyright (C) 2014 Tobias Schoene www.yaacc.de
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package de.yaacc.browser;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.switchmaterial.SwitchMaterial;

import org.fourthline.cling.model.meta.Device;

import java.util.ArrayList;
import java.util.LinkedList;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.UpnpClientListener;
import de.yaacc.upnp.server.configuration.YaaccUpnpServerControlActivity;
import de.yaacc.util.FormatHelper;
import de.yaacc.util.ThemeHelper;
import de.yaacc.util.YaaccLogger;

/**
 * Activity for browsing devices and folders. Represents the entrypoint for the whole application.
 *
 * @author @author Tobias Schoene (the openbit)
 */
public class ServerListFragment extends Fragment implements
        UpnpClientListener, OnBackPressedListener {
    private UpnpClient upnpClient = null;
    private RecyclerView contentList;
    private BrowseDeviceAdapter bDeviceAdapter;
    private StreamingPermissionHelper streamingPermissionHelper;
    private ShutdownTimerController shutdownTimerController;
    private ShutdownTimerController.Display shutdownTimerDisplay;
    // true when the host shows the timer in its toolbar: top-level layout, no timer rows
    private boolean hostProvidesShutdownTimer;


    /**
     * load app preferences
     *
     * @return app preferences
     */
    private SharedPreferences getPreferences() {
        return PreferenceManager
                .getDefaultSharedPreferences(requireActivity().getApplicationContext());

    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        streamingPermissionHelper = new StreamingPermissionHelper(requireContext(), () -> {
            if (bDeviceAdapter != null) {
                bDeviceAdapter.notifyDataSetChanged();
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        // MediaProjection stop events refresh the server row only while this screen is started
        streamingPermissionHelper.registerStopCallback();
        if (shutdownTimerController != null) {
            shutdownTimerController.attach(shutdownTimerDisplay);
        }
    }

    @Override
    public void onStop() {
        streamingPermissionHelper.unregisterStopCallback();
        if (shutdownTimerController != null) {
            shutdownTimerController.detach();
        }
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        // the display lambda holds this view hierarchy; the fragment instance can outlive it
        shutdownTimerDisplay = null;
        // the client lives as long as the application and has no other way to forget us
        if (upnpClient != null) {
            upnpClient.removeUpnpClientListener(this);
        }
        super.onDestroyView();
    }

    public boolean onBackPressed() {
        YaaccLogger.d(ServerListFragment.class.getName(), "onBackPressed()");
        ((Yaacc) requireActivity().getApplicationContext()).exit();
        ServerListFragment.super.requireActivity().finish();
        return true;
    }

    /**
     * Shows all available devices in the main device list.
     */
    private void populateDeviceList() {
        //FIXME: Cache should be able to decide whether it is used for browsing or for devices lists
        //IconDownloadCacheHandler.getInstance().resetCache();
        //https://www.digitalocean.com/community/tutorials/android-recyclerview-android-cardview-example-tutorial
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                RecyclerView deviceList = contentList;
                if (deviceList.getAdapter() == null) {
                    bDeviceAdapter = new BrowseDeviceAdapter(getActivity(), deviceList, upnpClient, new ArrayList<>(upnpClient.getDevicesProvidingContentDirectoryService()));
                    // Set permission callback for streaming buttons
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        bDeviceAdapter.setPermissionCallback(() -> {
                            android.content.Intent intent = streamingPermissionHelper.createRequestIntent();
                            if (intent != null) {
                                startActivityForResult(intent, de.yaacc.upnp.server.media.MediaProjectionHelper.REQUEST_CODE_MEDIA_PROJECTION);
                            }
                        });
                    }
                    deviceList.setAdapter(bDeviceAdapter);
                } else {
                    bDeviceAdapter.setDevices(new LinkedList<>(upnpClient.getDevicesProvidingContentDirectoryService()));
                }
            });

        }
    }

    /**
     * Refreshes the shown devices when device is added.
     */
    @Override
    public void deviceAdded(Device<?, ?, ?> device) {
        populateDeviceList();
    }

    /**
     * Refreshes the shown devices when device is removed.
     */
    @Override
    public void deviceRemoved(Device<?, ?, ?> device) {
        YaaccLogger.d(this.getClass().toString(), "device removal called");

        populateDeviceList();

    }

    @Override
    public void deviceUpdated(Device<?, ?, ?> device) {
        populateDeviceList();
    }

    @Override
    public void receiverDeviceRemoved(Device<?, ?, ?> device) {

    }

    @Override
    public void receiverDeviceAdded(Device<?, ?, ?> device) {

    }

    @Override
    public void onResume() {
        super.onResume();
        // Trigger UPnP discovery to find new devices
        if (upnpClient != null && upnpClient.isInitialized()) {
            upnpClient.searchDevices();
        }
        //refresh device list
        Thread thread = new Thread(this::populateDeviceList);
        thread.start();
        setLocalServerState(getView());
    }

    private void init(Bundle savedInstanceState, View view) {
        // local server startup
        upnpClient = ((Yaacc) requireActivity().getApplicationContext()).getUpnpClient();

        // Define where to show the folder contents for media
        contentList = view.findViewById(R.id.serverList);
        contentList.setLayoutManager(new LinearLayoutManager(getActivity()));
        contentList.setFocusable(true);
        contentList.setFocusableInTouchMode(false); // Good for D-Pad primary interaction
        contentList.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        ImageButton refresh = view.findViewById(R.id.serverListRefreshButton);
        Drawable icon = ThemeHelper.tintDrawable(getResources().getDrawable(R.drawable.ic_baseline_refresh_32, getContext().getTheme()), getContext().getTheme());
        refresh.setImageDrawable(icon);
        refresh.setOnClickListener((v) -> {
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    Toast.makeText(getActivity(), R.string.search_devices, Toast.LENGTH_LONG).show();
                });
            }
            upnpClient.searchDevices();
        });
        // the top-level layout has the "Servers" header; mark it so screen readers can jump to it
        View serversHeader = view.findViewById(R.id.serverRootHeader);
        if (serversHeader != null) {
            ViewCompat.setAccessibilityHeading(serversHeader, true);
        }
        setLocalServerState(view);
        SwitchMaterial localServerEnabledSwitch = view.findViewById(R.id.serverListLocalServerEnabled);
        localServerEnabledSwitch.setOnClickListener((v -> {
            getPreferences().edit().putBoolean(v.getContext().getString(R.string.settings_local_server_chkbx), localServerEnabledSwitch.isChecked()).apply();
            if (v.getContext() instanceof BrowserHost) {
                setLocalServerState(view);
            }
        }));
        ImageButton localServerSettings = view.findViewById(R.id.serverListLocalServerSettings);
        icon = ThemeHelper.tintDrawable(getResources().getDrawable(R.drawable.ic_baseline_settings_32, getContext().getTheme()), getContext().getTheme());
        localServerSettings.setImageDrawable(icon);
        localServerSettings.setOnClickListener(v -> {
            Intent intent = new Intent(getContext(), YaaccUpnpServerControlActivity.class);
            startActivity(intent);
        });
        if (!hostProvidesShutdownTimer) {
            TextView shutdowwnRemainingTextView = view.findViewById(R.id.serverListShutdownTimerRemaining);
            SwitchMaterial shutdownTimerSwitch = (SwitchMaterial) view.findViewById(R.id.serverListShutdownTimerEnabled);
            shutdownTimerController = new ShutdownTimerController((Yaacc) requireActivity().getApplicationContext(),
                    getPreferences(), getString(R.string.settings_shutdown_timer));
            // The display holds views, so the controller only keeps it between onStart and onStop
            shutdownTimerDisplay = (millis, running) -> {
                shutdowwnRemainingTextView.setText(FormatHelper.parseMillisToTimeStringTo(millis));
                shutdownTimerSwitch.setChecked(running);
            };

            shutdownTimerSwitch.setOnClickListener(v -> {
                if (shutdownTimerSwitch.isChecked()) {
                    shutdownTimerController.start();
                } else {
                    shutdownTimerController.stop();
                }
            });
            ImageButton shutdowwnSettingsImageView = view.findViewById(R.id.serverListSetShutdownTimer);
            icon = ThemeHelper.tintDrawable(getResources().getDrawable(R.drawable.ic_baseline_settings_32, getContext().getTheme()), getContext().getTheme());
            shutdowwnSettingsImageView.setImageDrawable(icon);
            shutdowwnSettingsImageView.setOnClickListener(v ->
                    shutdownTimerController.showPicker(requireActivity().getSupportFragmentManager(), getString(R.string.shutdown_timer)));
        }

        // add ourself as listener
        upnpClient.addUpnpClientListener(this);
        Thread thread = new Thread(this::populateDeviceList);
        thread.start();
    }

    private void setLocalServerState(View view) {
        SwitchMaterial localServerEnabledSwitch = view.findViewById(R.id.serverListLocalServerEnabled);
        localServerEnabledSwitch.setChecked(PreferenceManager.getDefaultSharedPreferences(getContext()).getBoolean(getContext().getString(R.string.settings_local_server_chkbx), false));
        ImageView providerImageView = view.findViewById(R.id.serverListProviderEnabled);
        if (getPreferences().getBoolean(getContext().getString(R.string.settings_local_server_provider_chkbx), false)) {
            Drawable icon = ThemeHelper.tintDrawable(getResources().getDrawable(R.drawable.ic_baseline_sensors_32, getContext().getTheme()), getContext().getTheme());
            providerImageView.setImageDrawable(icon);
            describeStatusIcon(providerImageView, R.string.settings_local_server_provider_on);
        } else {
            Drawable icon = ThemeHelper.tintDrawable(getResources().getDrawable(R.drawable.ic_baseline_sensors_off_32, getContext().getTheme()), getContext().getTheme());
            providerImageView.setImageDrawable(icon);
            describeStatusIcon(providerImageView, R.string.settings_local_server_provider_off);
        }
        ImageView receiverImageView = view.findViewById(R.id.serverListReceiverEnabled);
        if (getPreferences().getBoolean(getContext().getString(R.string.settings_local_server_receiver_chkbx), false)) {
            Drawable icon = ThemeHelper.tintDrawable(getResources().getDrawable(R.drawable.ic_baseline_devices_24, getContext().getTheme()), getContext().getTheme());
            receiverImageView.setImageDrawable(icon);
            describeStatusIcon(receiverImageView, R.string.settings_local_server_receiver_on);
        } else {
            Drawable icon = ThemeHelper.tintDrawable(getResources().getDrawable(R.drawable.ic_baseline_desktop_access_disabled_32, getContext().getTheme()), getContext().getTheme());
            receiverImageView.setImageDrawable(icon);
            describeStatusIcon(receiverImageView, R.string.settings_local_server_receiver_off);
        }
        ImageView proxyImageView = view.findViewById(R.id.serverListProxyEnabled);
        if (getPreferences().getBoolean(getContext().getString(R.string.settings_local_server_proxy_chkbx), false)) {
            Drawable icon = ThemeHelper.tintDrawable(getResources().getDrawable(R.drawable.ic_baseline_import_export_24, getContext().getTheme()), getContext().getTheme());
            proxyImageView.setImageDrawable(icon);
            describeStatusIcon(proxyImageView, R.string.settings_local_server_proxy_on);
        } else {
            Drawable icon = ThemeHelper.tintDrawable(getResources().getDrawable(R.drawable.ic_baseline_mobiledata_off_24, getContext().getTheme()), getContext().getTheme());
            proxyImageView.setImageDrawable(icon);
            describeStatusIcon(proxyImageView, R.string.settings_local_server_proxy_off);
        }
    }

    /**
     * The status icons carry a description only in the top-level layout (fragment_server_root.xml,
     * where the footer owns them); the classic layout keeps them hidden from accessibility as before.
     */
    private void describeStatusIcon(ImageView icon, int descriptionRes) {
        if (hostProvidesShutdownTimer) {
            icon.setContentDescription(getString(descriptionRes));
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        // The top-level layout of the new browser leaves the timer rows out: its toolbar has the timer.
        hostProvidesShutdownTimer = getActivity() instanceof BrowserHost
                && ((BrowserHost) getActivity()).providesShutdownTimer();
        return inflater.inflate(hostProvidesShutdownTimer
                ? R.layout.fragment_server_root : R.layout.fragment_server_list, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        init(savedInstanceState, view);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        streamingPermissionHelper.onPermissionResult(requestCode, resultCode, data);
    }
}
