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

import static com.google.android.material.timepicker.MaterialTimePicker.INPUT_MODE_KEYBOARD;

import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

import org.fourthline.cling.model.meta.Device;

import java.util.ArrayList;
import java.util.LinkedList;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.UpnpClientListener;
import de.yaacc.util.FormatHelper;
import de.yaacc.util.ShutdownTimerListener;
import de.yaacc.util.ThemeHelper;

/**
 * Activity for browsing devices and folders. Represents the entrypoint for the whole application.
 *
 * @author @author Tobias Schoene (the openbit)
 */
public class ServerListFragment extends Fragment implements
        UpnpClientListener, OnBackPressedListener, ShutdownTimerListener {
    private static final String SHUTDOWN_TIMER_REMAINING_TIME = "SHUTDOWN_TIMER_REMAINING_TIME";
    private UpnpClient upnpClient = null;
    private RecyclerView contentList;
    private BrowseDeviceAdapter bDeviceAdapter;


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
    }

    public boolean onBackPressed() {
        Log.d(ServerListFragment.class.getName(), "onBackPressed()");
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

        if (upnpClient.getProviderDevice() != null && upnpClient.getProviderDevice().equals(device)) {
            try {
                requireActivity();
            } catch (IllegalStateException iex) {
                Log.d(getClass().getName(), "ignoring illegal state exception on device added", iex);
                return;
            }
            if (requireActivity().getParent() instanceof TabBrowserActivity) {
                ((TabBrowserActivity) requireActivity().getParent()).setCurrentTab(BrowserTabs.CONTENT);
            }
        }
    }

    /**
     * Refreshes the shown devices when device is removed.
     */
    @Override
    public void deviceRemoved(Device<?, ?, ?> device) {
        Log.d(this.getClass().toString(), "device removal called");

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
        //refresh device list
        Thread thread = new Thread(this::populateDeviceList);
        thread.start();
        setLocalServerState(getView());
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putSerializable(SHUTDOWN_TIMER_REMAINING_TIME, ((TextView) getView().findViewById(R.id.serverListShutdownTimerRemaining)).getText().toString());
    }

    private void init(Bundle savedInstanceState, View view) {
        // local server startup
        upnpClient = ((Yaacc) requireActivity().getApplicationContext()).getUpnpClient();

        // Define where to show the folder contents for media
        contentList = view.findViewById(R.id.serverList);
        contentList.setLayoutManager(new LinearLayoutManager(getActivity()));
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
        setLocalServerState(view);
        SwitchMaterial localServerEnabledSwitch = view.findViewById(R.id.serverListLocalServerEnabled);
        localServerEnabledSwitch.setOnClickListener((v -> {
            getPreferences().edit().putBoolean(v.getContext().getString(R.string.settings_local_server_chkbx), localServerEnabledSwitch.isChecked()).apply();
            if (v.getContext() instanceof TabBrowserActivity) {
                if (localServerEnabledSwitch.isChecked()) {
                    v.getContext().getApplicationContext().startForegroundService(((TabBrowserActivity) v.getContext()).getYaaccUpnpServerService());
                } else {
                    v.getContext().getApplicationContext().stopService(((TabBrowserActivity) v.getContext()).getYaaccUpnpServerService());
                }
                setLocalServerState(view);
            }
        }));
        TextView shutdowwnRemainingTextView = view.findViewById(R.id.serverListShutdownTimerRemaining);
        if (savedInstanceState != null && savedInstanceState.containsKey(SHUTDOWN_TIMER_REMAINING_TIME)) {
            shutdowwnRemainingTextView.setText(savedInstanceState.getString(SHUTDOWN_TIMER_REMAINING_TIME));
            ((Yaacc) getContext().getApplicationContext()).setShutdownTimerListener(this);
        } else {
            long duration = getPreferences().getLong(getContext().getString(R.string.settings_shutdown_timer), 0L);
            shutdowwnRemainingTextView.setText(FormatHelper.parseMillisToTimeStringTo(duration));
        }

        SwitchMaterial shutdownTimerSwitch = (SwitchMaterial) view.findViewById(R.id.serverListShutdownTimerEnabled);
        shutdownTimerSwitch.setOnClickListener(v -> {
            long d = getPreferences().getLong(getContext().getString(R.string.settings_shutdown_timer), 0L);
            if (shutdownTimerSwitch.isChecked()) {
                ((Yaacc) getContext().getApplicationContext()).setShutdownTimerListener(this);
                ((Yaacc) getContext().getApplicationContext()).startShutdownTimer(d);
            } else {
                shutdowwnRemainingTextView.setText(FormatHelper.parseMillisToTimeStringTo(d));
                ((Yaacc) getContext().getApplicationContext()).stopShutdownTimer();
            }
        });
        ImageView shutdowwnSettingsImageView = view.findViewById(R.id.serverListSetShutdownTimer);
        icon = ThemeHelper.tintDrawable(getResources().getDrawable(R.drawable.ic_baseline_settings_32, getContext().getTheme()), getContext().getTheme());
        shutdowwnSettingsImageView.setImageDrawable(icon);
        shutdowwnSettingsImageView.setOnClickListener(v -> {
            long d = getPreferences().getLong(getContext().getString(R.string.settings_shutdown_timer), 0L);
            String durationString = FormatHelper.parseMillisToTimeStringTo(d);
            String[] splitted = durationString.split(":");
            int hours = Integer.parseInt(splitted[0]);
            int minutes = Integer.parseInt(splitted[1]);
            MaterialTimePicker.Builder builder = new MaterialTimePicker.Builder();
            MaterialTimePicker picker = builder.setTimeFormat(TimeFormat.CLOCK_24H)
                    .setHour(hours)
                    .setMinute(minutes)
                    .setTitleText(getContext().getString(R.string.shutdown_timer))
                    .setInputMode(INPUT_MODE_KEYBOARD).build();
            picker.addOnPositiveButtonClickListener(dialog -> {

                long millis = (picker.getHour() * 3600L + picker.getMinute() * 60L) * 1000L;
                Log.d(getClass().getName(), "time set: " + picker.getHour() + ":" + picker.getMinute() + " millis: " + millis);
                getPreferences().edit().putLong(getContext().getString(R.string.settings_shutdown_timer), millis).apply();
                if (shutdownTimerSwitch.isChecked()) {
                    ((Yaacc) getContext().getApplicationContext()).stopShutdownTimer();
                    shutdowwnRemainingTextView.setText(FormatHelper.parseMillisToTimeStringTo(millis));
                    ((Yaacc) getContext().getApplicationContext()).startShutdownTimer(millis);
                } else {
                    shutdowwnRemainingTextView.setText(FormatHelper.parseMillisToTimeStringTo(millis));
                }
            });
            picker.show(requireActivity().getSupportFragmentManager(), "CountdownTimer");

        });


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
        } else {
            Drawable icon = ThemeHelper.tintDrawable(getResources().getDrawable(R.drawable.ic_baseline_sensors_off_32, getContext().getTheme()), getContext().getTheme());
            providerImageView.setImageDrawable(icon);
        }
        ImageView receiverImageView = view.findViewById(R.id.serverListReceiverEnabled);
        if (getPreferences().getBoolean(getContext().getString(R.string.settings_local_server_receiver_chkbx), false)) {
            Drawable icon = ThemeHelper.tintDrawable(getResources().getDrawable(R.drawable.ic_baseline_devices_24, getContext().getTheme()), getContext().getTheme());
            receiverImageView.setImageDrawable(icon);
        } else {
            Drawable icon = ThemeHelper.tintDrawable(getResources().getDrawable(R.drawable.ic_baseline_desktop_access_disabled_32, getContext().getTheme()), getContext().getTheme());
            receiverImageView.setImageDrawable(icon);
        }
        ImageView proxyImageView = view.findViewById(R.id.serverListProxyEnabled);
        if (getPreferences().getBoolean(getContext().getString(R.string.settings_local_server_proxy_chkbx), false)) {
            Drawable icon = ThemeHelper.tintDrawable(getResources().getDrawable(R.drawable.ic_baseline_import_export_24, getContext().getTheme()), getContext().getTheme());
            proxyImageView.setImageDrawable(icon);
        } else {
            Drawable icon = ThemeHelper.tintDrawable(getResources().getDrawable(R.drawable.ic_baseline_mobiledata_off_24, getContext().getTheme()), getContext().getTheme());
            proxyImageView.setImageDrawable(icon);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        return inflater.inflate(R.layout.fragment_server_list, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        init(savedInstanceState, view);
    }

    public void setShutdownTimerRemainingTime(String s) {
        if (getView() != null) {
            TextView shutdowwnRemainingTextView = getView().findViewById(R.id.serverListShutdownTimerRemaining);
            shutdowwnRemainingTextView.setText(s);
        }
    }

    @Override
    public void onTick(long millisUntilFinished) {
        setShutdownTimerRemainingTime(FormatHelper.parseMillisToTimeStringTo(millisUntilFinished));
    }
}