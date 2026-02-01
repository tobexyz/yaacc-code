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

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.Icon;
import org.fourthline.cling.model.meta.RemoteDevice;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.support.avtransport.callback.GetPositionInfo;
import org.fourthline.cling.support.avtransport.callback.GetTransportInfo;
import org.fourthline.cling.support.avtransport.callback.Pause;
import org.fourthline.cling.support.avtransport.callback.Play;
import org.fourthline.cling.support.avtransport.callback.Seek;
import org.fourthline.cling.support.avtransport.callback.SetAVTransportURI;
import org.fourthline.cling.support.avtransport.callback.Stop;
import org.fourthline.cling.support.contentdirectory.DIDLParser;
import org.fourthline.cling.support.model.DIDLContent;
import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.PositionInfo;
import org.fourthline.cling.support.model.Res;
import org.fourthline.cling.support.model.TransportInfo;
import org.fourthline.cling.support.model.TransportState;
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
import de.yaacc.settings.SettingsFragment;
import de.yaacc.upnp.ActionState;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.server.YaaccUpnpServerService;
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

        // Check transport state first and handle accordingly
        checkTransportStateForStart(playableItem, service);
    }

    private void checkTransportStateForStart(PlayableItem playableItem, Service<?, ?> service) {
        // Check current transport state first
        GetTransportInfo stateCheck = new GetTransportInfo(service) {
            @Override
            public void received(ActionInvocation actioninvocation, TransportInfo transportInfo) {
                TransportState state = transportInfo.getCurrentTransportState();
                Log.d(getClass().getName(), "Current state before Play: " + state);

                if (state == TransportState.PAUSED_PLAYBACK) {
                    Log.d(getClass().getName(), "Resuming from pause, sending Play only");
                    // For paused content, just send Play command without SetAVTransportURI
                    Play playCallback = new Play(service) {
                        @Override
                        public void failure(ActionInvocation actioninvocation, UpnpResponse upnpresponse, String s) {
                            Log.d(getClass().getName(), "Resume Play failed: " + s);
                            setProcessingCommand(false);
                        }

                        @Override
                        public void success(ActionInvocation invocation) {
                            Log.d(getClass().getName(), "Resume Play succeeded");
                            setProcessingCommand(false);
                        }
                    };
                    getUpnpClient().getControlPoint().execute(playCallback);
                } else {
                    // For stopped or playing state, do full restart
                    Log.d(getClass().getName(), "Sending Stop command to ensure clean state");
                    executeCommand(new TimerTask() {
                        @Override
                        public void run() {
                            stop();
                            // Wait a bit then proceed with SetURI
                            executeCommand(new TimerTask() {
                                @Override
                                public void run() {
                                    proceedWithSetURI(playableItem, service);
                                }
                            }, new Date(System.currentTimeMillis() + 200));
                        }
                    }, new Date());
                }
            }

            @Override
            public void failure(ActionInvocation actioninvocation, UpnpResponse upnpresponse, String s) {
                Log.d(getClass().getName(), "GetTransportInfo failed, proceeding with full restart");
                // If we can't get state, do full restart
                executeCommand(new TimerTask() {
                    @Override
                    public void run() {
                        stop();
                        executeCommand(new TimerTask() {
                            @Override
                            public void run() {
                                proceedWithSetURI(playableItem, service);
                            }
                        }, new Date(System.currentTimeMillis() + 200));
                    }
                }, new Date());
            }
        };
        getUpnpClient().getControlPoint().execute(stateCheck);
    }

    private void proceedWithSetURI(PlayableItem playableItem, Service<?, ?> service) {
        Log.d(getClass().getName(), "Action SetAVTransportURI ");
        final ActionState actionState = new ActionState();
        actionState.actionFinished = false;
        Item item = playableItem.getItem();

        // Check if this device needs server-side range management
        String deviceId = getDevice().getIdentity().getUdn().getIdentifierString();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
        String prefKey = SettingsFragment.MANAGE_EXTERNAL_SEEKING + deviceId;
        boolean useServerSideManagement = preferences.getBoolean(prefKey, false);

        Log.d(getClass().getName(), "Device ID: " + deviceId);
        Log.d(getClass().getName(), "Preference key: " + prefKey);
        Log.d(getClass().getName(), "Server-side management enabled: " + useServerSideManagement);
        Log.d(getClass().getName(), "Item is null: " + (item == null));

        if (useServerSideManagement && item != null) {
            // Make a copy of the item to avoid modifying the shared instance
            try {
                // Create a new item with the same properties but modifiable resources
                Item itemCopy = new Item(item.getId(), item.getParentID(), item.getTitle(),
                        item.getCreator(), item.getClazz());

                // Copy all properties
                for (DIDLObject.Property property : item.getProperties()) {
                    itemCopy.addProperty(property);
                }

                // Copy and modify resources
                for (Res resource : item.getResources()) {
                    String originalUri = resource.getValue();
                    String modifiedUri = modifyProxyUrlWithDeviceId(originalUri);

                    Res newResource = new Res(resource.getProtocolInfo(), resource.getSize(),
                            resource.getDuration(), resource.getBitrate(), modifiedUri);
                    itemCopy.addResource(newResource);

                    if (!originalUri.equals(modifiedUri)) {
                        Log.d(getClass().getName(), "Modified copied item resource URI: " + modifiedUri);
                    }
                }

                item = itemCopy;
            } catch (Exception e) {
                Log.e(getClass().getName(), "Failed to copy/modify item: " + e.getMessage());
                item = playableItem.getItem(); // Fall back to original
            }
        }

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
                service, modifyProxyUrlWithDeviceId(playableItem.getUri().toString()), actionState, metadata);
        Log.d(getClass().getName(), "Original URI: " + playableItem.getUri().toString());
        Log.d(getClass().getName(), "Modified URI: " + modifyProxyUrlWithDeviceId(playableItem.getUri().toString()));
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
        lastRemainingTime = -1; // Reset to ensure timer gets set for new track
        playRetryCount = 0; // Reset retry counter for new track

        // Add small delay before Play command to let renderer process URI
        executeCommand(new TimerTask() {
            @Override
            public void run() {
                // Check current state before sending Play
                GetTransportInfo stateCheck = new GetTransportInfo(service) {
                    @Override
                    public void failure(ActionInvocation actioninvocation, UpnpResponse upnpresponse, String s) {
                        Log.d(getClass().getName(), "Failed to get transport state, sending Play anyway");
                        startPlayAction(service, actionState);
                    }

                    @Override
                    public void received(ActionInvocation actioninvocation, TransportInfo transportInfo) {
                        TransportState state = transportInfo.getCurrentTransportState();
                        Log.d(getClass().getName(), "Current state before Play: " + state);

                        if (state == TransportState.STOPPED) {
                            Log.d(getClass().getName(), "Valid state for Play command, proceeding");
                            startPlayAction(service, actionState);
                        } else if (state == TransportState.PAUSED_PLAYBACK) {
                            Log.d(getClass().getName(), "Resuming from pause, sending Play only");
                            // For paused content, just send Play command without SetAVTransportURI
                            Play playCallback = new Play(service) {
                                @Override
                                public void failure(ActionInvocation actioninvocation, UpnpResponse upnpresponse, String s) {
                                    Log.d(getClass().getName(), "Resume Play failed: " + s);
                                    setProcessingCommand(false);
                                }

                                @Override
                                public void success(ActionInvocation invocation) {
                                    Log.d(getClass().getName(), "Resume Play succeeded");
                                    setProcessingCommand(false);
                                }
                            };
                            getUpnpClient().getControlPoint().execute(playCallback);
                        } else if (state == TransportState.PLAYING) {
                            Log.d(getClass().getName(), "Already playing, sending Stop first then Play");
                            Stop stopCallback = new Stop(service) {
                                @Override
                                public void failure(ActionInvocation actioninvocation, UpnpResponse upnpresponse, String s) {
                                    Log.d(getClass().getName(), "Stop before Play failed: " + s);
                                    startPlayAction(service, actionState);
                                }

                                @Override
                                public void success(ActionInvocation invocation) {
                                    Log.d(getClass().getName(), "Stop succeeded, now sending Play");
                                    executeCommand(new TimerTask() {
                                        @Override
                                        public void run() {
                                            startPlayAction(service, actionState);
                                        }
                                    }, new Date(System.currentTimeMillis() + 200));
                                }
                            };
                            getUpnpClient().getControlPoint().execute(stopCallback);
                        } else {
                            Log.d(getClass().getName(), "Unknown state: " + state + ", sending Play anyway");
                            startPlayAction(service, actionState);
                        }
                    }
                };
                getUpnpClient().getControlPoint().execute(stateCheck);
            }
        }, new Date(System.currentTimeMillis() + 200));
    }

    private void startPlayAction(Service<?, ?> service, final ActionState actionState) {
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
                setProcessingCommand(false);
            }

            @Override
            public void success(ActionInvocation actioninvocation) {
                super.success(actioninvocation);
                actionState.actionFinished = true;
                setProcessingCommand(false);

                // Check transport state after Play command
                executeCommand(new TimerTask() {
                    @Override
                    public void run() {
                        getTransportInfo();
                    }
                }, new Date(System.currentTimeMillis() + 500));
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
                    // Log.d(getClass().getName(), "wait for action finished ");
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
        if (isProcessingCommand())
            return;
        setProcessingCommand(true);

        if (getDevice() == null) {
            Log.d(getClass().getName(),
                    "No receiver device found: "
                            + deviceId);
            setProcessingCommand(false);
            return;
        }
        Service<?, ?> service = getUpnpClient().getAVTransportService(getDevice());
        if (service == null) {
            Log.d(getClass().getName(),
                    "No AVTransport-Service found on Device: "
                            + getDevice().getDisplayString());
            setProcessingCommand(false);
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
                setProcessingCommand(false);
            }

            @Override
            public void success(ActionInvocation actioninvocation) {
                super.success(actioninvocation);
                actionState.actionFinished = true;
                setProcessingCommand(false);
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

    private int playRetryCount = 0;
    private static final int MAX_PLAY_RETRIES = 3;

    private void getTransportInfo() {
        if (getDevice() == null) {
            Log.d(getClass().getName(), "No receiver device found for transport info: " + deviceId);
            return;
        }
        Service<?, ?> service = getUpnpClient().getAVTransportService(getDevice());
        if (service == null) {
            Log.d(getClass().getName(), "No AVTransport-Service found for transport info");
            return;
        }

        Log.d(getClass().getName(), "GetTransportInfo");
        GetTransportInfo actionCallback = new GetTransportInfo(service) {
            @Override
            public void failure(ActionInvocation actioninvocation, UpnpResponse upnpresponse, String s) {
                Log.d(getClass().getName(), "GetTransportInfo failure: " + s);
            }

            @Override
            public void received(ActionInvocation actioninvocation, TransportInfo info) {
                Log.d(getClass().getName(), "Transport State: " + info.getCurrentTransportState());

                // If not playing and we haven't exceeded retry limit, try Play command again
                if (info.getCurrentTransportState() != TransportState.PLAYING && playRetryCount < MAX_PLAY_RETRIES) {
                    playRetryCount++;
                    Log.d(getClass().getName(), "Renderer not playing, sending Play command again (attempt " + playRetryCount + ")");
                    executeCommand(new TimerTask() {
                        @Override
                        public void run() {
                            sendPlayCommand();
                        }
                    }, new Date(System.currentTimeMillis() + 500));
                } else {
                    Log.d(getClass().getName(), "Checking position (retries: " + playRetryCount + ")");
                    getPositionInfo();
                }
            }
        };
        getUpnpClient().getControlPoint().execute(actionCallback);
    }

    private void sendPlayCommand() {
        if (getDevice() == null) {
            Log.d(getClass().getName(), "No receiver device found for Play command: " + deviceId);
            return;
        }
        Service<?, ?> service = getUpnpClient().getAVTransportService(getDevice());
        if (service == null) {
            Log.d(getClass().getName(), "No AVTransport-Service found for Play command");
            return;
        }

        Log.d(getClass().getName(), "Sending additional Play command");
        Play actionCallback = new Play(service) {
            @Override
            public void failure(ActionInvocation actioninvocation, UpnpResponse upnpresponse, String s) {
                Log.d(getClass().getName(), "Additional Play command failed: " + s);
                getPositionInfo(); // Check position anyway
            }

            @Override
            public void success(ActionInvocation actioninvocation) {
                super.success(actioninvocation);
                Log.d(getClass().getName(), "Additional Play command succeeded");

                // Check transport state again after second Play command
                executeCommand(new TimerTask() {
                    @Override
                    public void run() {
                        getTransportInfo();
                    }
                }, new Date(System.currentTimeMillis() + 1000)); // Wait 1 second then check state
            }
        };
        getUpnpClient().getControlPoint().execute(actionCallback);
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

                long currentRemainingTime = positionInfo.getTrackRemainingSeconds();

                // Update server-side position management only for external URLs on basic renderers
                if (positionInfo.getRelTime() != null && !positionInfo.getRelTime().isEmpty()) {
                    int currentIndex = getCurrentItemIndex();
                    if (currentIndex >= 0 && currentIndex < getItems().size()) {
                        PlayableItem currentPlayableItem = getItems().get(currentIndex);
                        String itemUri = currentPlayableItem.getUri().toString();
                        boolean isExternalUrl = itemUri != null && itemUri.contains("/proxy/");

                        if (isExternalUrl) {
                            try {
                                String[] timeParts = positionInfo.getRelTime().split(":");
                                if (timeParts.length >= 3) {
                                    long hours = Long.parseLong(timeParts[0]);
                                    long minutes = Long.parseLong(timeParts[1]);
                                    long seconds = Long.parseLong(timeParts[2]);
                                    long timeMs = (hours * 3600 + minutes * 60 + seconds) * 1000;

                                    // Extract content key from proxy URL for renderer state key
                                    String contentKey = itemUri.substring(itemUri.lastIndexOf("/") + 1);

                                    // Check if position is stuck (paused)
                                    boolean isPaused = (lastRemainingTime != -1 && currentRemainingTime == lastRemainingTime);

                                    de.yaacc.upnp.server.YaaccUpnpServerServiceHttpHandler.updateRendererPosition(
                                            "test_renderer_" + contentKey, timeMs, isPaused);
                                }
                            } catch (Exception e) {
                                Log.w(getClass().getName(), "Failed to parse position time: " + positionInfo.getRelTime(), e);
                            }
                        }
                    }
                }

                // Set timer on first position info OR when remaining time changes significantly
                if (lastRemainingTime == -1 && currentRemainingTime > 1) {
                    // First position check - set timer only if we have valid remaining time
                    lastRemainingTime = currentRemainingTime;
                    updateTimer();
                } else if (currentRemainingTime > 1 && Math.abs(currentRemainingTime - lastRemainingTime) > 5) {
                    // Subsequent updates - only if remaining time changed significantly
                    lastRemainingTime = currentRemainingTime;
                    updateTimer();
                }
            }
        };

        getUpnpClient().getControlPoint().execute(actionCallback);


    }

    @Override
    public int getIconResourceId() {
        return R.drawable.ic_baseline_devices_32;
    }


    private long lastPositionUpdate = 0;
    private long lastRemainingTime = -1;
    private static final long POSITION_UPDATE_INTERVAL = 1000; // 1 second for better track completion detection


    public long getCurrentPosition() {
        long currentTime = System.currentTimeMillis();
        if (currentPositionInfo == null || (currentTime - lastPositionUpdate) > POSITION_UPDATE_INTERVAL) {
            getPositionInfo();
            lastPositionUpdate = currentTime;
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
        // Check if the service supports seek action
        if (service.getAction("Seek") == null) {
            Log.w(getClass().getName(), "Player does not support Seek action");
            Context context = getUpnpClient().getContext();
            if (context instanceof Activity) {
                ((Activity) context).runOnUiThread(() -> {
                    Toast.makeText(context, "Seek not supported by this player", Toast.LENGTH_SHORT).show();
                });
            }
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

                // Update server-side position for external URLs
                int currentIndex = getCurrentItemIndex();
                if (currentIndex >= 0 && currentIndex < getItems().size()) {
                    PlayableItem currentPlayableItem = getItems().get(currentIndex);
                    String itemUri = currentPlayableItem.getUri().toString();
                    boolean isExternalUrl = itemUri != null && itemUri.contains("/proxy/");

                    if (isExternalUrl) {
                        String contentKey = itemUri.substring(itemUri.lastIndexOf("/") + 1);
                        String deviceId = getDevice().getIdentity().getUdn().getIdentifierString();
                        de.yaacc.upnp.server.YaaccUpnpServerServiceHttpHandler.updateRendererPosition(
                                deviceId + "_" + contentKey, millisecondsFromStart, false);

                        // Also save to preferences for HTTP handler
                        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
                        preferences.edit().putLong("server_position_" + deviceId, millisecondsFromStart).apply();

                        Log.d(getClass().getName(), "Updated server position after seek: " + millisecondsFromStart + "ms");
                    }
                }

                // Don't schedule position check - let normal position polling handle it
            }

            @Override
            public void failure(ActionInvocation arg0, UpnpResponse arg1, String arg2) {
                Log.w(getClass().getName(), "Seek failed - Player may not support seeking");
                Log.w(getClass().getName(), "UpnpResponse: " + (arg1 != null ? arg1.getResponseDetails() : "null"));
                Log.w(getClass().getName(), "Error: " + arg2);

                // Some players don't support seeking, just log and continue
                Context context = getUpnpClient().getContext();
                if (context instanceof Activity) {
                    ((Activity) context).runOnUiThread(() -> {
                        Toast.makeText(context, "Seek not supported by this player", Toast.LENGTH_SHORT).show();
                    });
                }
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

    private String modifyProxyUrlWithDeviceId(String originalUrl) {
        // Check if it's a proxy URL from the same YAACC server
        if (originalUrl != null && originalUrl.contains("/proxy/")) {
            try {
                // Check if URL is from this YAACC server by comparing IP
                java.net.URL url = new java.net.URL(originalUrl);
                String urlHost = url.getHost();

                // Get local server IP using YAACC method
                String[] ifAndIp = YaaccUpnpServerService.getIfAndIpAddress(getUpnpClient().getContext());
                String localIP = ifAndIp != null && ifAndIp.length > 0 ? ifAndIp[0] : null;

                Log.d(getClass().getName(), "URL host: '" + urlHost + "', Local IP: '" + localIP + "'");
                Log.d(getClass().getName(), "IP comparison: urlHost.equals(localIP) = " + urlHost.equals(localIP));

                if (urlHost.equals(localIP) || urlHost.equals("localhost") || urlHost.equals("127.0.0.1")) {
                    // Get device UUID and URL-encode it for safe URL usage
                    String deviceId = getDevice().getIdentity().getUdn().getIdentifierString();
                    String encodedDeviceId = java.net.URLEncoder.encode(deviceId, "UTF-8");

                    // Replace /proxy/contentKey with /proxy/encodedDeviceId/contentKey
                    String[] parts = originalUrl.split("/proxy/");
                    if (parts.length == 2) {
                        String modifiedUrl = parts[0] + "/proxy/" + encodedDeviceId + "/" + parts[1];
                        Log.d(getClass().getName(), "Modified proxy URL: " + originalUrl + " -> " + modifiedUrl);
                        return modifiedUrl;
                    }
                }
            } catch (Exception e) {
                Log.w(getClass().getName(), "Failed to modify proxy URL", e);
            }
        }
        return originalUrl;
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
    }

    @Override
    public void onDestroy() {
        doExit();
        super.onDestroy();
    }

    private void doExit() {
        stop();
        final ActionState actionState = new ActionState();
        actionState.actionFinished = false;
        Runnable fn = () -> {
            actionState.actionFinished = AVTransportPlayer.this.isProcessingCommand();
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Log.w(getClass().getName(), e);
            }
        };
        waitForActionComplete(actionState, fn);

    }

    @Override
    public void exit() {
        doExit();
        super.exit();
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
            Log.d(getClass().getName(), "InternalSetAVTransportURI created with URI: " + uri);
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
