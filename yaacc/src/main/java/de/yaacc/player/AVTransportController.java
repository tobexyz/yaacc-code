/*
 * Copyright (C) 2019 Tobias Schoene www.yaacc.de
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

package de.yaacc.player;

import android.content.ComponentName;
import android.os.IBinder;
import de.yaacc.util.YaaccLogger;

import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.Service;
import de.yaacc.upnp.callback.avtransport.Next;
import de.yaacc.upnp.callback.avtransport.Play;
import de.yaacc.upnp.callback.avtransport.Previous;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.yaacc.R;
import de.yaacc.upnp.ActionState;
import de.yaacc.upnp.UpnpClient;

public class AVTransportController extends AVTransportPlayer {

    public static final String DEVICE_ID = "DEVICE_ID";
    private final ExecutorService executorService;

    public AVTransportController(UpnpClient upnpClient, Device<?, ?, ?> receiverDevice) {

        super(upnpClient, receiverDevice, "", "", null);
        executorService = Executors.newFixedThreadPool(20);
        String deviceName = receiverDevice.getDetails().getFriendlyName() + " - " + receiverDevice.getDisplayString();
        deviceName = upnpClient.getContext()
                .getString(R.string.playerNameAvTransport)
                + "@" + deviceName;
        setName(deviceName);
        setShortName(receiverDevice.getDetails().getFriendlyName());
    }

    public void onServiceConnected(ComponentName className, IBinder binder) {
        if (binder instanceof PlayerService.PlayerServiceBinder) {
            YaaccLogger.d(getClass().getName(), "ignore service connected");

        }
    }


    public void onServiceDisconnected(ComponentName className) {
        YaaccLogger.d(getClass().getName(), "ignore service disconnected");

    }


    @Override
    public void exit() {

    }

    @Override
    public void stop() {
        stopItem(null);
    }


    @Override
    public void next() {
        if (getDevice() == null)
            return;
        Service<?, ?> service = getUpnpClient().getAVTransportService(getDevice());
        if (service == null) {
            YaaccLogger.d(getClass().getName(),
                    "No AVTransport-Service found on Device: "
                            + getDevice().getDisplayString());
            return;
        }

        YaaccLogger.d(getClass().getName(), "Action Next");
        final ActionState actionState = new ActionState();
        actionState.actionFinished = false;
        Next actionCallback = new Next(service, getHttpRequestSender()) {
            @Override
            public void failure(ActionInvocation actioninvocation,
                                UpnpResponse upnpresponse, String s) {
                YaaccLogger.d(getClass().getName(), "Failure UpnpResponse: "
                        + upnpresponse);
                YaaccLogger.d(getClass().getName(),
                        upnpresponse != null ? "UpnpResponse: "
                                + upnpresponse.getResponseDetails() : "");
                YaaccLogger.d(getClass().getName(), "s: " + s);
                actionState.actionFinished = true;
            }

            @Override
            public void success(ActionInvocation actioninvocation) {
                super.success(actioninvocation);
                actionState.actionFinished = true;
            }
        };
        executorService.execute(actionCallback);
    }

    @Override
    public void previous() {
        if (getDevice() == null)
            return;
        Service<?, ?> service = getUpnpClient().getAVTransportService(getDevice());
        if (service == null) {
            YaaccLogger.d(getClass().getName(),
                    "No AVTransport-Service found on Device: "
                            + getDevice().getDisplayString());
            return;
        }

        YaaccLogger.d(getClass().getName(), "Action Previous");
        final ActionState actionState = new ActionState();
        actionState.actionFinished = false;
        Previous actionCallback = new Previous(service, getHttpRequestSender()) {
            @Override
            public void failure(ActionInvocation actioninvocation,
                                UpnpResponse upnpresponse, String s) {
                YaaccLogger.d(getClass().getName(), "Failure UpnpResponse: "
                        + upnpresponse);
                YaaccLogger.d(getClass().getName(),
                        upnpresponse != null ? "UpnpResponse: "
                                + upnpresponse.getResponseDetails() : "");
                YaaccLogger.d(getClass().getName(), "s: " + s);
                actionState.actionFinished = true;
            }

            @Override
            public void success(ActionInvocation actioninvocation) {
                super.success(actioninvocation);
                actionState.actionFinished = true;
            }
        };
        executorService.execute(actionCallback);
    }

    @Override
    public void play() {
        if (getDevice() == null)
            return;

        Service<?, ?> service = getUpnpClient().getAVTransportService(getDevice());
        if (service == null) {
            YaaccLogger.d(getClass().getName(),
                    "No AVTransport-Service found on Device: "
                            + getDevice().getDisplayString());
            return;
        }

        YaaccLogger.d(getClass().getName(), "Action Play");
        final ActionState actionState = new ActionState();
        actionState.actionFinished = false;
        Play actionCallback = new Play(service, getHttpRequestSender()) {
            @Override
            public void failure(ActionInvocation actioninvocation,
                                UpnpResponse upnpresponse, String s) {
                YaaccLogger.d(getClass().getName(), "Failure UpnpResponse: "
                        + upnpresponse);
                YaaccLogger.d(getClass().getName(),
                        upnpresponse != null ? "UpnpResponse: "
                                + upnpresponse.getResponseDetails() : "");
                YaaccLogger.d(getClass().getName(), "s: " + s);
                actionState.actionFinished = true;
            }

            @Override
            public void success(ActionInvocation actioninvocation) {
                super.success(actioninvocation);
                actionState.actionFinished = true;
            }
        };
        executorService.execute(actionCallback);
    }
}
