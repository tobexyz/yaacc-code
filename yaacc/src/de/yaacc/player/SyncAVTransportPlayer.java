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
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program; if not, write to the Free Software
* Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
*/
package de.yaacc.player;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;
import org.fourthline.cling.support.avtransport.callback.GetPositionInfo;
import org.fourthline.cling.support.avtransport.callback.Seek;
import org.fourthline.cling.support.avtransport.callback.SetAVTransportURI;
import org.fourthline.cling.support.contentdirectory.DIDLParser;
import org.fourthline.cling.support.model.DIDLContent;
import org.fourthline.cling.support.model.PositionInfo;
import org.fourthline.cling.support.model.item.Item;
import org.fourthline.cling.support.renderingcontrol.callback.GetMute;
import org.fourthline.cling.support.renderingcontrol.callback.GetVolume;
import org.fourthline.cling.support.renderingcontrol.callback.SetMute;
import org.fourthline.cling.support.renderingcontrol.callback.SetVolume;

import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.Future;

import de.yaacc.R;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.callback.avtransport.AdjustSyncOffset;
import de.yaacc.upnp.callback.avtransport.GetSyncOffset;
import de.yaacc.upnp.callback.avtransport.SetSyncOffset;
import de.yaacc.upnp.callback.avtransport.SyncPause;
import de.yaacc.upnp.callback.avtransport.SyncPlay;
import de.yaacc.upnp.callback.avtransport.SyncStop;
import de.yaacc.upnp.model.types.SyncOffset;
import de.yaacc.util.Watchdog;

/**
 * A Player for playing on a remote avtransport device which supports syncplay
 *
 * @author Tobias Schoene (openbit)
 */
public class SyncAVTransportPlayer extends AbstractPlayer {
    public static final String PLAYER_ID = "PlayerId";
    private String deviceId = "";
    private int id;
    private String contentType;
    private PositionInfo currentPositionInfo;
    private ActionState positionActionState = null;


    /**
     * @param receiverDevice the receiver device
     * @param upnpClient     the client
     * @param name           playerName
     */
    public SyncAVTransportPlayer(UpnpClient upnpClient, Device receiverDevice, String name, String contentType) {
        this(upnpClient);
        deviceId = receiverDevice.getIdentity().getUdn().getIdentifierString();
        setName(name);
        this.contentType = contentType;
        id = Math.abs(UUID.randomUUID().hashCode());

    }

    /**
     * @param upnpClient the client
     */
    public SyncAVTransportPlayer(UpnpClient upnpClient) {
        super(upnpClient);

    }

    private Device<?, ?, ?> getDevice() {
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
        Log.d(getClass().getName(), "Action SetAVTransportURI ");
        final ActionState actionState = new ActionState();
// Now start Stopping
        Log.d(getClass().getName(), "Action SyncStop");
        actionState.actionFinished = false;

        SyncStop actionCallback = new SyncStop(new UnsignedIntegerFourBytes(id), service, getSyncInfo().getReferencedPresentationTime(), getSyncInfo().getReferencedClockId()) {
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
            metadata = (item == null) ? "" : new DIDLParser().generate(new DIDLContent().addItem(item), false);
            if ("NOT_IMPLEMENTED".equals(metadata)) {
                metadata = "";
            }
        } catch (Exception e) {
            Log.d(getClass().getName(), "Error while generating Didl-Item xml: " + e);
            metadata = "";
        }

        InternalSetAVTransportURI setAVTransportURI = new InternalSetAVTransportURI(new UnsignedIntegerFourBytes(id),
                service, playableItem.getUri().toString(), actionState, metadata);
        getUpnpClient().getControlPoint().execute(setAVTransportURI);
        waitForActionComplete(actionState);
        int tries = 1;
        if(setAVTransportURI.hasFailures){
            //another try
            Log.d(getClass().getName(), "setAVTransportURI.hasFailures");
            while (setAVTransportURI.hasFailures && tries < 4){
                tries++;
                Log.d(getClass().getName(), "setAVTransportURI.hasFailures retry:" + tries);
                setAVTransportURI.hasFailures = false;
                getUpnpClient().getControlPoint().execute(setAVTransportURI);
                waitForActionComplete(actionState);
            }
        }
        if(setAVTransportURI.hasFailures) {
            //another try
            Log.d(getClass().getName(), "Can't set AVTransportURI. Giving up");
            return;
        }
// Now start Playing
        Log.d(getClass().getName(), "Action SyncPlay");
        actionState.actionFinished = false;
        SyncPlay actionCallback = new SyncPlay(new UnsignedIntegerFourBytes(id), service, "", "", getSyncInfo().getReferencedPresentationTime(), getSyncInfo().getReferencedClockId()) {
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
        actionState.watchdogFlag = false;
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                actionState.watchdogFlag = true;
            }
        }, 30000L); // 30sec. Watchdog
        while (!(actionState.actionFinished || actionState.watchdogFlag)) {
// wait for local device is connected
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
        PendingIntent contentIntent = PendingIntent.getActivity(getContext(), 0,
                notificationIntent, PendingIntent.FLAG_CANCEL_CURRENT);
        return contentIntent;
    }

    /*
    * (non-Javadoc)
    * @see de.yaacc.player.AbstractPlayer#getNotificationId()
    */
    @Override
    protected int getNotificationId() {
        return id;
    }

    public void callPause() {

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
        Log.d(getClass().getName(), "Action SyncPause ");
        final ActionState actionState = new ActionState();
        actionState.actionFinished = false;
        SyncPause actionCallback = new SyncPause(new UnsignedIntegerFourBytes(id), service, getSyncInfo().getOffset().toString(), getSyncInfo().getReferencedClockId()) {
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
        return null;
    }

    public void getSyncOffset() {

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
        Log.d(getClass().getName(), "Action GetSyncOffset ");
        final ActionState actionState = new ActionState();
        actionState.actionFinished = false;
        String result = "";
        GetSyncOffset actionCallback = new GetSyncOffset(new UnsignedIntegerFourBytes(id), service, result) {
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
        Future callbackFuture = getUpnpClient().getControlPoint().execute(actionCallback);
        while (!callbackFuture.isDone() || !callbackFuture.isCancelled()) ;
        if (callbackFuture.isDone()) {
            getSyncInfo().setOffset(new SyncOffset(result));
        }
    }

    public void setSyncOffset(SyncOffset offset) {
        getSyncInfo().setOffset(offset);
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
        Log.d(getClass().getName(), "Action SetSyncOffset ");
        final ActionState actionState = new ActionState();
        actionState.actionFinished = false;
        SetSyncOffset actionCallback = new SetSyncOffset(new UnsignedIntegerFourBytes(id), service, offset.toString()) {
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

    public void adjustSyncOffset(SyncOffset offset) {
        getSyncInfo().getOffset().add(offset);
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
        Log.d(getClass().getName(), "Action AdjustSyncOffset ");
        final ActionState actionState = new ActionState();
        actionState.actionFinished = false;
        AdjustSyncOffset actionCallback = new AdjustSyncOffset(new UnsignedIntegerFourBytes(id), service, offset.toString()) {
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

    /*
     * (non-Javadoc)
     *
     * @see de.yaacc.player.Player#pause()
     */
    @Override
    public void pause() {
        if (isProcessingCommand())
            return;
        setProcessingCommand(true);
        executeCommand(new TimerTask() {
            @Override
            public void run() {
                cancelTimer();
                Context context = getUpnpClient().getContext();
                if (context instanceof Activity) {
                    ((Activity) context).runOnUiThread(new Runnable() {
                        public void run() {
                            Toast toast = Toast.makeText(getContext(), getContext()
                                    .getResources().getString(R.string.pause)
                                    + getPositionString(), Toast.LENGTH_SHORT);
                            toast.show();
                        }
                    });
                }
                setPlaying(false);
                setProcessingCommand(false);

            }
        }, getExecutionTime());
        callPause();

    }

    /*
     * (non-Javadoc)
     *
     * @see de.yaacc.player.Player#start()
     */
    @Override
    public void play() {
        if (isProcessingCommand())
            return;
        setProcessingCommand(true);
        executeCommand(new TimerTask() {
            @Override
            public void run() {
                if (getCurrentIndex() < getItems().size()) {
                    Context context = getUpnpClient().getContext();
                    if (context instanceof Activity) {
                        ((Activity) context).runOnUiThread(new Runnable() {
                            public void run() {
                                Toast toast = Toast.makeText(getContext(), getContext()
                                        .getResources().getString(R.string.play)
                                        + getPositionString(), Toast.LENGTH_SHORT);
                                toast.show();
                            }
                        });
                    }
                    // Start the pictureShow
                    setPlaying(true);
                    setProcessingCommand(false);
                }

            }
        }, getExecutionTime());
        loadItem(getCurrentIndex(), getCurrentIndex());

    }

    /*
     * (non-Javadoc)
     *
     * @see de.yaacc.player.Player#stop()
     */
    @Override
    public void stop() {
        if (isProcessingCommand())
            return;
        setProcessingCommand(true);
        executeCommand(new TimerTask() {
            @Override
            public void run() {
                cancelTimer();
                setCurrentIndex(0);
                Context context = getUpnpClient().getContext();
                if (context instanceof Activity) {
                    ((Activity) context).runOnUiThread(new Runnable() {
                        public void run() {
                            Toast toast = Toast.makeText(getContext(), getContext()
                                    .getResources().getString(R.string.stop)
                                    + getPositionString(), Toast.LENGTH_SHORT);
                            toast.show();
                        }
                    });
                }
                setPlaying(false);
                setProcessingCommand(false);
            }
        }, getExecutionTime());


        stopItem(getItems().get(getCurrentIndex()));

    }

    public boolean getMute() {
        if (getDevice() == null) {
            Log.d(getClass().getName(),
                    "No receiver device found: "
                            + deviceId);
            return false;
        }
        Service<?, ?> service = getUpnpClient().getRenderingControlService(getDevice());
        if (service == null) {
            Log.d(getClass().getName(),
                    "No AVTransport-Service found on Device: "
                            + getDevice().getDisplayString());
            return false;
        }
        Log.d(getClass().getName(), "Action get Mute ");
        final ActionState actionState = new ActionState();
        actionState.actionFinished = false;
        GetMute actionCallback = new GetMute(new UnsignedIntegerFourBytes(id),service) {
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

            @Override
            public void received(ActionInvocation actionInvocation, boolean currentMute) {
                actionState.result = Boolean.valueOf(currentMute);

            }
        };
        getUpnpClient().getControlPoint().execute(actionCallback);
        Watchdog watchdog = Watchdog.createWatchdog(10000L);
        watchdog.start();

        while (!actionState.actionFinished && !watchdog.hasTimeout()) {
            //active wait
        }
        if (watchdog.hasTimeout()) {
            Log.d(getClass().getName(),"Timeout occurred");
        }else{
            watchdog.cancel();
        }
        return actionState.result == null ? false : (Boolean) actionState.result;


    }

    public void setMute(boolean mute) {
        if (getDevice() == null) {
            Log.d(getClass().getName(),
                    "No receiver device found: "
                            + deviceId);
            return;
        }
        Service<?, ?> service = getUpnpClient().getRenderingControlService(getDevice());
        if (service == null) {
            Log.d(getClass().getName(),
                    "No AVTransport-Service found on Device: "
                            + getDevice().getDisplayString());
            return;
        }
        Log.d(getClass().getName(), "Action set Mute ");
        final ActionState actionState = new ActionState();
        actionState.actionFinished = false;
        SetMute actionCallback = new SetMute(new UnsignedIntegerFourBytes(id),service, mute) {
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

    public int getVolume() {
        if (getDevice() == null) {
            Log.d(getClass().getName(),
                    "No receiver device found: "
                            + deviceId);
            return 0;
        }
        Service<?, ?> service = getUpnpClient().getRenderingControlService(getDevice());
        if (service == null) {
            Log.d(getClass().getName(),
                    "No AVTransport-Service found on Device: "
                            + getDevice().getDisplayString());
            return 0;
        }
        Log.d(getClass().getName(), "Action get Volume ");
        final ActionState actionState = new ActionState();
        actionState.actionFinished = false;
        GetVolume actionCallback = new GetVolume(new UnsignedIntegerFourBytes(id),service) {
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

            @Override
            public void received(ActionInvocation actionInvocation, int currentVolume) {
                actionState.result = Integer.valueOf(currentVolume);

            }
        };
        getUpnpClient().getControlPoint().execute(actionCallback);
        Watchdog watchdog = Watchdog.createWatchdog(10000L);
        watchdog.start();

        while (!actionState.actionFinished && !watchdog.hasTimeout()) {
            //active wait
        }
        if (watchdog.hasTimeout()) {
            Log.d(getClass().getName(),"Timeout occurred");
        }else{
            watchdog.cancel();
        }
        return actionState.result == null ? 0 : (Integer) actionState.result;


    }

    public void setVolume(int volume) {
        if (getDevice() == null) {
            Log.d(getClass().getName(),
                    "No receiver device found: "
                            + deviceId);
            return;
        }
        Service<?, ?> service = getUpnpClient().getRenderingControlService(getDevice());
        if (service == null) {
            Log.d(getClass().getName(),
                    "No AVTransport-Service found on Device: "
                            + getDevice().getDisplayString());
            return;
        }
        Log.d(getClass().getName(), "Action set Volume ");
        final ActionState actionState = new ActionState();
        actionState.actionFinished = false;
        SetVolume actionCallback = new SetVolume(new UnsignedIntegerFourBytes(id),service, volume) {
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

    private static class InternalSetAVTransportURI extends SetAVTransportURI {
        ActionState actionState = null;
        public boolean hasFailures = false;

        private InternalSetAVTransportURI(UnsignedIntegerFourBytes instanceId, Service service, String uri,
                                          ActionState actionState, String metadata) {
            super(instanceId, service, uri, metadata);
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

    private static class ActionState {
        public boolean actionFinished = false;
        public boolean watchdogFlag = false;
        public Object result = null;
    }



    @Override
    public void seekTo(long millisecondsFromStart){
        if(getDevice() == null) {
            Log.d(getClass().getName(),
                    "No receiver device found: "
                            + deviceId);
            return;
        }
        Service<?, ?> service = getUpnpClient().getAVTransportService(getDevice());
        if (service == null) {
            Log.d(getClass().getName(),
                    "No AVTransport-Service found on Device: "
                            +getDevice().getDisplayString());
            return;
        }
        Log.d(getClass().getName(), "Action seek ");
        final ActionState actionState = new ActionState();
        actionState.actionFinished = false;
        SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");
        dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
        String relativeTimeTarget =dateFormat.format(millisecondsFromStart);
        Seek seekAction = new Seek(new UnsignedIntegerFourBytes(id),service, relativeTimeTarget) {
            @Override
            public void success(ActionInvocation invocation)
            {
                //super.success(invocation);
                Log.d(getClass().getName(), "success seek");
            }
            @Override
            public void failure(ActionInvocation arg0, UpnpResponse arg1, String arg2)
            {
                Log.d(getClass().getName(), "fail seek");
            }
        };
        getUpnpClient().getControlPoint().execute(seekAction);

    }

    protected void  getPositionInfo(){
        if(positionActionState != null && !positionActionState.actionFinished){
            return;
        }
        Log.d(getClass().getName(),
                "GetPositioninfo");
        if(getDevice() == null) {
            Log.d(getClass().getName(),
                    "No receiver device found: "
                            + deviceId);
            return;
        }
        Service<?, ?> service = getUpnpClient().getAVTransportService(getDevice());
        if (service == null) {
            Log.d(getClass().getName(),
                    "No AVTransport-Service found on Device: "
                            +getDevice().getDisplayString());
            return;
        }
        Log.d(getClass().getName(), "Action get position info ");
        positionActionState = new ActionState();
        positionActionState.actionFinished = false;
        GetPositionInfo actionCallback = new GetPositionInfo(new UnsignedIntegerFourBytes(id),service) {
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
                positionActionState.result=positionInfo;
                currentPositionInfo = positionInfo;
                Log.d(getClass().getName(), "received Positioninfo= RelTime: " + positionInfo.getRelTime());

            }
        };

        getUpnpClient().getControlPoint().execute(actionCallback);



    }

    @Override
    public String getDuration() {
        if(currentPositionInfo == null){
            getPositionInfo();
        }
        if (currentPositionInfo != null){
            return currentPositionInfo.getTrackDuration();
        }
        return "00:00:00";
    }

    @Override
    public String getElapsedTime() {
        getPositionInfo();

        if (currentPositionInfo != null){
            return currentPositionInfo.getRelTime();
        }
        return "00:00:00";
    }
}

