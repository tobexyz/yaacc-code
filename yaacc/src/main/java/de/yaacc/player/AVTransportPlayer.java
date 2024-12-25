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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package de.yaacc.player;

import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.Icon;
import org.fourthline.cling.model.meta.RemoteDevice;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.support.avtransport.callback.GetPositionInfo;
import org.fourthline.cling.support.avtransport.callback.Pause;
import org.fourthline.cling.support.avtransport.callback.Play;
import org.fourthline.cling.support.avtransport.callback.Seek;
import org.fourthline.cling.support.avtransport.callback.SetAVTransportURI;
import org.fourthline.cling.support.avtransport.callback.Stop;
import org.fourthline.cling.support.contentdirectory.DIDLParser;
import org.fourthline.cling.support.model.DIDLContent;
import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.PositionInfo;
import org.fourthline.cling.support.model.item.Item;

import java.net.URI;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.upnp.ActionState;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.image.ImageDownloader;

/**
 * A Player for playing on a remote avtransport device
 *
 * @author Tobias Schoene (openbit)
 */
public class AVTransportPlayer extends AbstractPlayer {

    private String deviceId = "";
    private int id;
    private String contentType;
    private PositionInfo currentPositionInfo;
    private ActionState positionActionState = null;
    private URI albumArtUri;


    /**
     * @param upnpClient the client
     * @param name       playerName
     */
    public AVTransportPlayer(UpnpClient upnpClient, Device<?, ?, ?> receiverDevice, String name, String shortName, String contentType) {
        this(upnpClient);
        deviceId = receiverDevice.getIdentity().getUdn().getIdentifierString();
        setName(name);
        setShortName(shortName);
        this.contentType = contentType;
        id = Math.abs(UUID.randomUUID().hashCode());
        setDeviceIcon(receiverDevice);
    }

    /**
     * @param upnpClient the client
     */
    public AVTransportPlayer(UpnpClient upnpClient) {
        super(upnpClient);
    }

    protected Device<?, ?, ?> getDevice() {
        return getUpnpClient().getDevice(deviceId);
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getContentType() {
        return contentType;
    }

    /* (non-Javadoc)
     * @see de.yaacc.player.AbstractPlayer#stopItem(de.yaacc.player.PlayableItem)
     */
    @Override
    protected void stopItem(PlayableItem playableItem) {
        if (getDevice() == null) {
            Log.d(getClass().getName(),
                    "No receiver device found: "
                            + deviceId);
            return;
        }
        Service<?, ?> service = getUpnpClient().getAVTransportService(getDevice());
        if (service == null) {
            Log.d(getClass().getName(),
                    "No AVTransport-Service found on Device: "
                            + getDevice().getDisplayString());
            return;
        }
        final ActionState actionState = new ActionState();
// Now start Stopping
        Log.d(getClass().getName(), "Action Stop");
        actionState.actionFinished = false;
        Stop actionCallback = new Stop(service) {
            @Override
            public void failure(ActionInvocation actioninvocation,
                                UpnpResponse upnpresponse, String s) {
                Log.d(getClass().getName(), "Failure UpnpResponse: "
                        + upnpresponse);
                Log.d(getClass().getName(),
                        upnpresponse != null ? "UpnpResponse: "
                                + upnpresponse.getResponseDetails() : "");
                Log.d(getClass().getName(), "s: " + s);
                actionState.actionFinished = true;
            }

            @Override
            public void success(ActionInvocation actioninvocation) {
                super.success(actioninvocation);
                actionState.actionFinished = true;
            }
        };
        getUpnpClient().getControlPoint().execute(actionCallback);
    }

    /* (non-Javadoc)
     * @see de.yaacc.player.AbstractPlayer#loadItem(de.yaacc.player.PlayableItem)
     */
    @Override
    protected Object loadItem(PlayableItem playableItem) {
        return playableItem;
    }

    /* (non-Javadoc)
     * @see de.yaacc.player.AbstractPlayer#startItem(de.yaacc.player.PlayableItem, java.lang.Object)
     */
    @Override
    protected void startItem(PlayableItem playableItem, Object loadedItem) {
        if (playableItem == null || getDevice() == null)
            return;
        Log.d(getClass().getName(), "Uri: " + playableItem.getUri());
        Log.d(getClass().getName(), "Duration: " + playableItem.getDuration());
        Log.d(getClass().getName(),
                "MimeType: " + playableItem.getMimeType());
        Log.d(getClass().getName(), "Title: " + playableItem.getTitle());
        Service<?, ?> service = getUpnpClient().getAVTransportService(getDevice());
        if (service == null) {
            Log.d(getClass().getName(),
                    "No AVTransport-Service found on Device: "
                            + getDevice().getDisplayString());
            return;
        }
        Log.d(getClass().getName(), "Action SetAVTransportURI ");
        final ActionState actionState = new ActionState();
        actionState.actionFinished = false;
        Item item = playableItem.getItem();
        String metadata;
        try {
            metadata = new DIDLParser().generate((item == null) ? new DIDLContent() : new DIDLContent().addItem(item), false);

        } catch (Exception e) {
            Log.d(getClass().getName(), "Error while generating Didl-Item xml: " + e);
            metadata = "";
        }
        DIDLObject.Property<URI> albumArtUriProperty = playableItem.getItem() == null ? null : playableItem.getItem().getFirstProperty(DIDLObject.Property.UPNP.ALBUM_ART_URI.class);
        albumArtUri = (albumArtUriProperty == null) ? null : albumArtUriProperty.getValue();

        InternalSetAVTransportURI setAVTransportURI = new InternalSetAVTransportURI(
                service, playableItem.getUri().toString(), actionState, metadata);
        getUpnpClient().getControlPoint().execute(setAVTransportURI);
        waitForActionComplete(actionState);
        int tries = 1;
        if (setAVTransportURI.hasFailures) {
            //another try
            Log.d(getClass().getName(), "setAVTransportURI.hasFailures");
            while (setAVTransportURI.hasFailures && tries < 4) {
                tries++;
                Log.d(getClass().getName(), "setAVTransportURI.hasFailures retry:" + tries);
                setAVTransportURI.hasFailures = false;
                getUpnpClient().getControlPoint().execute(setAVTransportURI);
                waitForActionComplete(actionState);
            }
        }
        if (setAVTransportURI.hasFailures) {
            //another try
            Log.d(getClass().getName(), "Can't set AVTransportURI. Giving up");
            return;
        }
// Now start Playing
        Log.d(getClass().getName(), "Action Play");
        actionState.actionFinished = false;
        Play actionCallback = new Play(service) {
            @Override
            public void failure(ActionInvocation actioninvocation,
                                UpnpResponse upnpresponse, String s) {
                Log.d(getClass().getName(), "Failure UpnpResponse: "
                        + upnpresponse);
                Log.d(getClass().getName(),
                        upnpresponse != null ? "UpnpResponse: "
                                + upnpresponse.getResponseDetails() : "");
                Log.d(getClass().getName(), "s: " + s);
                actionState.actionFinished = true;
            }

            @Override
            public void success(ActionInvocation actioninvocation) {
                super.success(actioninvocation);
                actionState.actionFinished = true;
            }
        };
        getUpnpClient().getControlPoint().execute(actionCallback);
    }

    /**
     * Watchdog for async calls to complete
     */
    private void waitForActionComplete(final ActionState actionState) {
        waitForActionComplete(actionState, null);
    }

    /**
     * Watchdog for async calls to complete
     */
    private void waitForActionComplete(final ActionState actionState, Runnable fn) {
        actionState.watchdogFlag = false;
        Timer watchdogTimer = new Timer();
        watchdogTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                actionState.watchdogFlag = true;
            }
        }, 30000L); // 30sec. Watchdog
        int i = 0;
        while (!(actionState.actionFinished || actionState.watchdogFlag)) {
            if (fn != null) {
                fn.run();
            } else {
                //work around byte code optimization
                i++;
                if (i == 100000) {
                    Log.d(getClass().getName(), "wait for action finished ");
                    i = 0;
                }
            }
        }
        if (actionState.watchdogFlag) {
            Log.d(getClass().getName(), "Watchdog timeout!");
        }
        if (actionState.actionFinished) {
            Log.d(getClass().getName(), "Action completed!");
        }
    }

    /*
     * (non-Javadoc)
     * @see de.yaacc.player.AbstractPlayer#getNotificationIntent()
     */
    @Override
    public PendingIntent getNotificationIntent() {
        Intent notificationIntent = new Intent(getContext(),
                AVTransportPlayerActivity.class);
        Log.d(getClass().getName(), "Put id into intent: " + getId());
        notificationIntent.setData(Uri.parse("http://0.0.0.0/" + getId() + "")); //just for making the intents different http://stackoverflow.com/questions/10561419/scheduling-more-than-one-pendingintent-to-same-activity-using-alarmmanager
        notificationIntent.putExtra(PLAYER_ID, getId());
        return PendingIntent.getActivity(getContext(), 0,
                notificationIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_CANCEL_CURRENT);

    }

    /*
     * (non-Javadoc)
     * @see de.yaacc.player.AbstractPlayer#getNotificationId()
     */
    @Override
    protected int getNotificationId() {
        return id;
    }


    @Override
    public void pause() {
        super.pause();
        if (getDevice() == null) {
            Log.d(getClass().getName(),
                    "No receiver device found: "
                            + deviceId);
            return;
        }
        Service<?, ?> service = getUpnpClient().getAVTransportService(getDevice());
        if (service == null) {
            Log.d(getClass().getName(),
                    "No AVTransport-Service found on Device: "
                            + getDevice().getDisplayString());
            return;
        }
        Log.d(getClass().getName(), "Action Pause ");
        final ActionState actionState = new ActionState();
        actionState.actionFinished = false;
        Pause actionCallback = new Pause(service) {
            @Override
            public void failure(ActionInvocation actioninvocation,
                                UpnpResponse upnpresponse, String s) {
                Log.d(getClass().getName(), "Failure UpnpResponse: "
                        + upnpresponse);
                Log.d(getClass().getName(),
                        upnpresponse != null ? "UpnpResponse: "
                                + upnpresponse.getResponseDetails() : "");
                Log.d(getClass().getName(), "s: " + s);
                actionState.actionFinished = true;
            }

            @Override
            public void success(ActionInvocation actioninvocation) {
                super.success(actioninvocation);
                actionState.actionFinished = true;
            }
        };
        getUpnpClient().getControlPoint().execute(actionCallback);
    }

    @Override
    public URI getAlbumArt() {
        return albumArtUri;
    }

    public boolean getMute() {
        if (getDevice() == null) {
            Log.d(getClass().getName(),
                    "No receiver device found: "
                            + deviceId);
            return false;
        }
        return getUpnpClient().getMute(getDevice());
    }

    public void setMute(boolean mute) {
        if (getDevice() == null) {
            Log.d(getClass().getName(),
                    "No receiver device found: "
                            + deviceId);
            return;
        }
        getUpnpClient().setMute(getDevice(), mute);
    }

    public int getVolume() {
        if (getDevice() == null) {
            Log.d(getClass().getName(),
                    "No receiver device found: "
                            + deviceId);
            return 0;
        }
        return getUpnpClient().getVolume(getDevice());
    }

    public void setVolume(int volume) {
        if (getDevice() == null) {
            Log.d(getClass().getName(),
                    "No receiver device found: "
                            + deviceId);
            return;
        }
        getUpnpClient().setVolume(getDevice(), volume);
    }


    protected void getPositionInfo() {
        if (positionActionState != null && !positionActionState.actionFinished) {
            return;
        }
        Log.d(getClass().getName(),
                "GetPositioninfo");
        if (getDevice() == null) {
            Log.d(getClass().getName(),
                    "No receiver device found: "
                            + deviceId);
            return;
        }
        Service<?, ?> service = getUpnpClient().getAVTransportService(getDevice());
        if (service == null) {
            Log.d(getClass().getName(),
                    "No AVTransport-Service found on Device: "
                            + getDevice().getDisplayString());
            return;
        }
        Log.d(getClass().getName(), "Action get position info ");
        positionActionState = new ActionState();
        positionActionState.actionFinished = false;
        GetPositionInfo actionCallback = new GetPositionInfo(service) {
            @Override
            public void failure(ActionInvocation actioninvocation,
                                UpnpResponse upnpresponse, String s) {
                Log.d(getClass().getName(), "Failure UpnpResponse: "
                        + upnpresponse);
                Log.d(getClass().getName(),
                        upnpresponse != null ? "UpnpResponse: "
                                + upnpresponse.getResponseDetails() : "");
                Log.d(getClass().getName(), "s: " + s);
                positionActionState.actionFinished = true;
            }

            @Override
            public void success(ActionInvocation actioninvocation) {
                super.success(actioninvocation);
                positionActionState.actionFinished = true;
            }

            @Override
            public void received(ActionInvocation actionInvocation, PositionInfo positionInfo) {
                positionActionState.result = positionInfo;
                currentPositionInfo = positionInfo;
                Log.d(getClass().getName(), "received Positioninfo= RelTime: " + positionInfo.getRelTime() + " remaining time: " + positionInfo.getTrackRemainingSeconds());

            }
        };

        getUpnpClient().getControlPoint().execute(actionCallback);


    }

    @Override
    public int getIconResourceId() {
        return R.drawable.ic_baseline_devices_32;
    }


    public long getCurrentPosition() {
        if (currentPositionInfo == null) {
            getPositionInfo();
        }
        if (currentPositionInfo != null) {
            Log.v(getClass().getName(), "Elapsed time: " + currentPositionInfo.getTrackElapsedSeconds() + " in millis: " + currentPositionInfo.getTrackRemainingSeconds() * 1000);
            return currentPositionInfo.getTrackElapsedSeconds() * 1000;
        }
        return -1;

    }

    @Override
    public void seekTo(long millisecondsFromStart) {
        if (getDevice() == null) {
            Log.d(getClass().getName(),
                    "No receiver device found: "
                            + deviceId);
            return;
        }
        Service<?, ?> service = getUpnpClient().getAVTransportService(getDevice());
        if (service == null) {
            Log.d(getClass().getName(),
                    "No AVTransport-Service found on Device: "
                            + getDevice().getDisplayString());
            return;
        }
        Log.d(getClass().getName(), "Action seek ");
        final ActionState actionState = new ActionState();
        actionState.actionFinished = false;
        SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");
        dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
        String relativeTimeTarget = dateFormat.format(millisecondsFromStart);
        Seek seekAction = new Seek(service, relativeTimeTarget) {
            @Override
            public void success(ActionInvocation invocation) {
                //super.success(invocation);
                Log.d(getClass().getName(), "success seek" + invocation);
                executeCommand(new TimerTask() {
                    @Override
                    public void run() {
                        updateTimer();
                    }
                }, new Date(System.currentTimeMillis() + 2000)); //wait two seconds before reading time from renderer

            }

            @Override
            public void failure(ActionInvocation arg0, UpnpResponse arg1, String arg2) {
                Log.d(getClass().getName(), "fail seek");
            }
        };
        getUpnpClient().getControlPoint().execute(seekAction);

    }


    @Override
    public long getRemainingTime() {
        if (currentPositionInfo == null) {
            getPositionInfo();
        }
        if (currentPositionInfo != null) {
            Log.v(getClass().getName(), "Remaining time: " + currentPositionInfo.getTrackRemainingSeconds() + " in millis: " + currentPositionInfo.getTrackRemainingSeconds() * 1000);
            return currentPositionInfo.getTrackRemainingSeconds() * 1000;
        }
        return -1;
    }

    @Override
    public String getDuration() {
        if (currentPositionInfo == null) {
            getPositionInfo();
        }
        if (currentPositionInfo != null) {
            return currentPositionInfo.getTrackDuration();
        }
        return "00:00:00";
    }

    @Override
    public String getElapsedTime() {
        getPositionInfo();

        if (currentPositionInfo != null) {
            return currentPositionInfo.getRelTime();
        }
        return "00:00:00";
    }

    @Override
    public void startTimer(final long duration) {
        super.startTimer(duration);
        Yaacc yaacc = (Yaacc) getContext().getApplicationContext();
    }

    @Override
    public void onDestroy() {
        doExit();
        super.onDestroy();
    }

    private void doExit() {
        ((Yaacc) getContext().getApplicationContext()).releaseWakeLock(getWakeLockTag());
        stop();
        final ActionState actionState = new ActionState();
        actionState.actionFinished = false;
        Runnable fn = () -> {
            actionState.actionFinished = AVTransportPlayer.this.isProcessingCommand();
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        };
        waitForActionComplete(actionState, fn);

    }

    @Override
    public void exit() {
        doExit();
        super.exit();
    }

    private String getWakeLockTag() {
        return "de.yaacc.wakelock.player:" + getId();
    }


    private void setDeviceIcon(Device<?, ?, ?> device) {
        if (device instanceof RemoteDevice && device.hasIcons()) {
            if (device.hasIcons()) {
                Icon[] icons = device.getIcons();
                for (Icon icon : icons) {
                    if (120 == icon.getHeight() && 120 == icon.getWidth() && "image/png".equals(icon.getMimeType().toString())) {
                        URL iconUri = ((RemoteDevice) device).normalizeURI(icon.getUri());
                        if (iconUri != null) {
                            Log.d(getClass().getName(), "Device icon uri:" + iconUri);
                            setIcon(new ImageDownloader().retrieveImageWithCertainSize(Uri.parse(iconUri.toString()), icon.getWidth(), icon.getHeight()));
                            break;
                        }
                    }
                }
            }
        }


    }

    private static class InternalSetAVTransportURI extends SetAVTransportURI {
        public boolean hasFailures = false;
        ActionState actionState;

        private InternalSetAVTransportURI(Service<?, ?> service, String uri,
                                          ActionState actionState, String metadata) {
            super(service, uri, metadata);
            this.actionState = actionState;
        }

        @Override
        public void failure(ActionInvocation actioninvocation,
                            UpnpResponse upnpresponse, String s) {
            Log.d(getClass().getName(), "Failure UpnpResponse: " + upnpresponse);
            if (upnpresponse != null) {
                Log.d(getClass().getName(),
                        "UpnpResponse: " + upnpresponse.getResponseDetails());
                Log.d(getClass().getName(),
                        "UpnpResponse: " + upnpresponse.getStatusMessage());
                Log.d(getClass().getName(),
                        "UpnpResponse: " + upnpresponse.getStatusCode());
            }
            hasFailures = true;
            Log.d(getClass().getName(), "s: " + s);
            actionState.actionFinished = true;
        }

        @Override
        public void success(ActionInvocation actioninvocation) {
            super.success(actioninvocation);
            actionState.actionFinished = true;
        }
    }


    public boolean hasActionGetVolume() {
        if (getDevice() == null) {
            Log.d(getClass().getName(),
                    "No receiver device found: "
                            + deviceId);
            return false;
        }
        return getUpnpClient().hasActionGetVolume(getDevice());
    }

    public boolean hasActionGetMute() {
        if (getDevice() == null) {
            Log.d(getClass().getName(),
                    "No receiver device found: "
                            + deviceId);
            return false;
        }
        return getUpnpClient().hasActionGetMute(getDevice());
    }
}
