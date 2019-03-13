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
import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Device;
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

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.Watchdog;

/**
 * A Player for playing on a remote avtransport device
 * @author Tobias Schoene (openbit)
 *
 */
public class AVTransportPlayer extends AbstractPlayer {

    private String deviceId="";
    private int id;
    private String contentType;
    private PositionInfo currentPositionInfo;
    private ActionState positionActionState = null;
    private URI albumArtUri;

    /**
     * @param upnpClient the client
     * @param name playerName
     *
     */
    public AVTransportPlayer(UpnpClient upnpClient, Device receiverDevice, String name, String contentType) {
        this(upnpClient);
        deviceId = receiverDevice.getIdentity().getUdn().getIdentifierString();
        setName(name);
        this.contentType = contentType;
        id =  Math.abs(UUID.randomUUID().hashCode());
    }
    protected Device<?, ?, ?> getDevice(){
        return getUpnpClient().getDevice(deviceId);
    }
    /**
     * @param upnpClient the client
     */
    public AVTransportPlayer(UpnpClient upnpClient) {
        super(upnpClient);
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
			metadata = (item == null)? "" : new DIDLParser().generate(new DIDLContent().addItem(item), false);
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
            if (fn != null){
                fn.run();
            }else {
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
    private static class InternalSetAVTransportURI extends SetAVTransportURI {
        ActionState actionState = null;
        public boolean hasFailures = false;
        private InternalSetAVTransportURI(Service service, String uri,
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
    protected static class ActionState {
        public boolean actionFinished = false;
        public boolean watchdogFlag = false;
        public Object result = null;
    }
    /*
    * (non-Javadoc)
    * @see de.yaacc.player.AbstractPlayer#getNotificationIntent()
    */
    @Override
    public PendingIntent getNotificationIntent(){
        Intent notificationIntent = new Intent(getContext(),
                AVTransportPlayerActivity.class);
        Log.d(getClass().getName(), "Put id into intent: " + getId());
        notificationIntent.setData(Uri.parse("http://0.0.0.0/"+getId()+"")); //just for making the intents different http://stackoverflow.com/questions/10561419/scheduling-more-than-one-pendingintent-to-same-activity-using-alarmmanager
        notificationIntent.putExtra(PLAYER_ID, getId());
        PendingIntent contentIntent = PendingIntent.getActivity(getContext(), 0 ,
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
    /* (non-Javadoc)
    * @see de.yaacc.player.AbstractPlayer#pause()
    */
    @Override
    public void pause() {
        super.pause();
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

    public boolean getMute(){
        if(getDevice() == null) {
            Log.d(getClass().getName(),
                    "No receiver device found: "
                            + deviceId);
            return false;
        }
        Service<?, ?> service = getUpnpClient().getRenderingControlService(getDevice());
        if (service == null) {
            Log.d(getClass().getName(),
                    "No AVTransport-Service found on Device: "
                            +getDevice().getDisplayString());
            return false;
        }
        Log.d(getClass().getName(), "Action get Mute ");
        final ActionState actionState = new ActionState();
        actionState.actionFinished = false;
        GetMute actionCallback = new GetMute(service) {
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
                actionState.result=Boolean.valueOf(currentMute);

            }
        };
       getUpnpClient().getControlPoint().execute(actionCallback);
        Watchdog watchdog = Watchdog.createWatchdog(10000L);
        watchdog.start();

        int i=0;
        while (!actionState.actionFinished && !watchdog.hasTimeout()) {
            //active wait
            i++;
            if( i== 100000) {
                Log.d(getClass().getName(), "wait for action finished ");
                i=0;
            }
        }
        if (watchdog.hasTimeout()) {
            Log.d(getClass().getName(),"Timeout occurred");
        }else{
            watchdog.cancel();
        }
        return actionState.result == null ? false : (Boolean) actionState.result;
    } 
    public void setMute(boolean mute){
        if(getDevice() == null) {
            Log.d(getClass().getName(),
                    "No receiver device found: "
                            + deviceId);
            return;
        }
        Service<?, ?> service = getUpnpClient().getRenderingControlService(getDevice());
        if (service == null) {
            Log.d(getClass().getName(),
                    "No AVTransport-Service found on Device: "
                            +getDevice().getDisplayString());
            return;
        }
        Log.d(getClass().getName(), "Action set Mute ");
        final ActionState actionState = new ActionState();
        actionState.actionFinished = false;
        SetMute actionCallback = new SetMute(service, mute) {
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

    public void setVolume(int volume){
        if(getDevice() == null) {
            Log.d(getClass().getName(),
                    "No receiver device found: "
                            + deviceId);
            return;
        }
        Service<?, ?> service = getUpnpClient().getRenderingControlService(getDevice());
        if (service == null) {
            Log.d(getClass().getName(),
                    "No RenderingControl-Service found on Device: "
                            +getDevice().getDisplayString());
            return;
        }
        Log.d(getClass().getName(), "Action set Volume ");
        final ActionState actionState = new ActionState();
        actionState.actionFinished = false;
        SetVolume actionCallback = new SetVolume(service, volume) {
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

    public int getVolume(){
        if(getDevice() == null) {
            Log.d(getClass().getName(),
                    "No receiver device found: "
                            + deviceId);
            return 0;
        }
        Service<?, ?> service = getUpnpClient().getRenderingControlService(getDevice());
        if (service == null) {
            Log.d(getClass().getName(),
                    "No RenderingControl-Service found on Device: "
                            +getDevice().getDisplayString());
            return 0;
        }
        Log.d(getClass().getName(), "Action get Volume ");
        final ActionState actionState = new ActionState();
        actionState.actionFinished = false;
        GetVolume actionCallback = new GetVolume(service) {
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
                actionState.result=Integer.valueOf(currentVolume);

            }
        };

        getUpnpClient().getControlPoint().execute(actionCallback);
        Watchdog watchdog = Watchdog.createWatchdog(10000L);
        watchdog.start();
        int i = 0;
        while (!actionState.actionFinished && !watchdog.hasTimeout()) {
            //active wait
            i++;
            if( i== 100000) {
                Log.d(getClass().getName(), "wait for action finished ");
                i=0;
            }
        }
        if (watchdog.hasTimeout()) {
            Log.d(getClass().getName(),"Timeout occurred");
        }else{
            watchdog.cancel();
        }
        return actionState.result == null ? 0 : (Integer) actionState.result;

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
                positionActionState.result=positionInfo;
                currentPositionInfo = positionInfo;
                Log.d(getClass().getName(), "received Positioninfo= RelTime: " + positionInfo.getRelTime());

            }
        };

        getUpnpClient().getControlPoint().execute(actionCallback);



    }

    @Override
    public int getIconResourceId(){
        return R.drawable.device_48_48;
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
        Seek seekAction = new Seek(service, relativeTimeTarget) {
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
    @Override
    public void startTimer(final long duration) {
        super.startTimer(duration);
        Yaacc yaacc = (Yaacc) getContext().getApplicationContext();
        if(yaacc.isUnplugged() && getItems().size()>1){
            yaacc.aquireWakeLock(duration + 1000L, getWakeLockTag());
            //bring current player to front
            Intent i = new Intent(yaacc, AVTransportPlayerActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP| Intent.FLAG_ACTIVITY_NEW_TASK);
            i.setData(Uri.parse("http://0.0.0.0/"+getId()+"")); //just for making the intents different http://stackoverflow.com/questions/10561419/scheduling-more-than-one-pendingintent-to-same-activity-using-alarmmanager
            i.putExtra(PLAYER_ID, getId());
            yaacc.startActivity(i);
        }
    }

    @Override
    public void onDestroy(){
        doExit();
        super.onDestroy();
    }

    private void doExit(){
        ((Yaacc)getContext().getApplicationContext()).releaseWakeLock(getWakeLockTag());
        stop();
        final ActionState actionState = new ActionState();
        actionState.actionFinished = false;
        Runnable fn = new Runnable() {
            @Override
            public void run() {
                actionState.actionFinished = AVTransportPlayer.this.isProcessingCommand();
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        };
        waitForActionComplete(actionState,fn);
    }
    @Override
    public void exit(){
        doExit();
        super.exit();
    }

    private String getWakeLockTag() {
        return "de.yaacc.wakelock.player:" + getId();
    }
}
