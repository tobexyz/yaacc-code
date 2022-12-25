/*
 *
 * Copyright (C) 2013 www.yaacc.de 
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
package de.yaacc.upnp.server.avtransport;

import android.util.Log;

import org.fourthline.cling.binding.annotations.UpnpAction;
import org.fourthline.cling.binding.annotations.UpnpInputArgument;
import org.fourthline.cling.binding.annotations.UpnpOutputArgument;
import org.fourthline.cling.binding.annotations.UpnpService;
import org.fourthline.cling.binding.annotations.UpnpServiceId;
import org.fourthline.cling.binding.annotations.UpnpServiceType;
import org.fourthline.cling.binding.annotations.UpnpStateVariable;
import org.fourthline.cling.binding.annotations.UpnpStateVariables;
import org.fourthline.cling.model.ModelUtil;
import org.fourthline.cling.model.types.ErrorCode;
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;
import org.fourthline.cling.support.avtransport.AVTransportErrorCode;
import org.fourthline.cling.support.avtransport.AVTransportException;
import org.fourthline.cling.support.avtransport.impl.AVTransportService;
import org.fourthline.cling.support.avtransport.impl.AVTransportStateMachine;
import org.fourthline.cling.support.avtransport.impl.state.AbstractState;
import org.fourthline.cling.support.avtransport.lastchange.AVTransportLastChangeParser;
import org.fourthline.cling.support.avtransport.lastchange.AVTransportVariable;
import org.fourthline.cling.support.lastchange.LastChange;
import org.fourthline.cling.support.lastchange.LastChangeDelegator;
import org.fourthline.cling.support.model.AVTransport;
import org.fourthline.cling.support.model.DeviceCapabilities;
import org.fourthline.cling.support.model.MediaInfo;
import org.fourthline.cling.support.model.PlayMode;
import org.fourthline.cling.support.model.PositionInfo;
import org.fourthline.cling.support.model.RecordMediumWriteStatus;
import org.fourthline.cling.support.model.RecordQualityMode;
import org.fourthline.cling.support.model.SeekMode;
import org.fourthline.cling.support.model.StorageMedium;
import org.fourthline.cling.support.model.TransportInfo;
import org.fourthline.cling.support.model.TransportSettings;
import org.fourthline.cling.support.model.TransportState;
import org.fourthline.cling.support.model.TransportStatus;
import org.seamless.statemachine.StateMachineBuilder;
import org.seamless.statemachine.TransitionException;

import java.beans.PropertyChangeSupport;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.model.types.SyncOffset;


/**
 * Implementation of an avtransport service version 3 mainly copied from cling example implementation.
 *
 * @author Tobias Schöne (openbit)
 */
@UpnpService(
        serviceId = @UpnpServiceId("AVTransport"),
        serviceType = @UpnpServiceType(value = "AVTransport", version = 1), //needed for backward compatibility
        stringConvertibleTypes = LastChange.class
)
@UpnpStateVariables({
        @UpnpStateVariable(
                name = "TransportState",
                sendEvents = false,
                allowedValuesEnum = TransportState.class),
        @UpnpStateVariable(
                name = "TransportStatus",
                sendEvents = false,
                allowedValuesEnum = TransportStatus.class),
        @UpnpStateVariable(
                name = "PlaybackStorageMedium",
                sendEvents = false,
                defaultValue = "NONE",
                allowedValuesEnum = StorageMedium.class),
        @UpnpStateVariable(
                name = "RecordStorageMedium",
                sendEvents = false,
                defaultValue = "NOT_IMPLEMENTED",
                allowedValuesEnum = StorageMedium.class),
        @UpnpStateVariable(
                name = "PossiblePlaybackStorageMedia",
                sendEvents = false,
                datatype = "string",
                defaultValue = "NETWORK"),
        @UpnpStateVariable(
                name = "PossibleRecordStorageMedia",
                sendEvents = false,
                datatype = "string",
                defaultValue = "NOT_IMPLEMENTED"),
        @UpnpStateVariable( // TODO
                name = "CurrentPlayMode",
                sendEvents = false,
                defaultValue = "NORMAL",
                allowedValuesEnum = PlayMode.class),
        @UpnpStateVariable( // TODO
                name = "TransportPlaySpeed",
                sendEvents = false,
                datatype = "string",
                defaultValue = "1"), // 1, 1/2, 2, -1, 1/10, etc.
        @UpnpStateVariable(
                name = "RecordMediumWriteStatus",
                sendEvents = false,
                defaultValue = "NOT_IMPLEMENTED",
                allowedValuesEnum = RecordMediumWriteStatus.class),
        @UpnpStateVariable(
                name = "CurrentRecordQualityMode",
                sendEvents = false,
                defaultValue = "NOT_IMPLEMENTED",
                allowedValuesEnum = RecordQualityMode.class),
        @UpnpStateVariable(
                name = "PossibleRecordQualityModes",
                sendEvents = false,
                datatype = "string",
                defaultValue = "NOT_IMPLEMENTED"),
        @UpnpStateVariable(
                name = "NumberOfTracks",
                sendEvents = false,
                datatype = "ui4",
                defaultValue = "0"),
        @UpnpStateVariable(
                name = "CurrentTrack",
                sendEvents = false,
                datatype = "ui4",
                defaultValue = "0"),
        @UpnpStateVariable(
                name = "CurrentTrackDuration",
                sendEvents = false,
                datatype = "string"), // H+:MM:SS[.F+] or H+:MM:SS[.F0/F1]
        @UpnpStateVariable(
                name = "CurrentMediaDuration",
                sendEvents = false,
                datatype = "string",
                defaultValue = "00:00:00"),
        @UpnpStateVariable(
                name = "CurrentTrackMetaData",
                sendEvents = false,
                datatype = "string",
                defaultValue = "NOT_IMPLEMENTED"),
        @UpnpStateVariable(
                name = "CurrentTrackURI",
                sendEvents = false,
                datatype = "string"),
        @UpnpStateVariable(
                name = "AVTransportURI",
                sendEvents = false,
                datatype = "string"),
        @UpnpStateVariable(
                name = "AVTransportURIMetaData",
                sendEvents = false,
                datatype = "string",
                defaultValue = "NOT_IMPLEMENTED"),
        @UpnpStateVariable(
                name = "NextAVTransportURI",
                sendEvents = false,
                datatype = "string",
                defaultValue = "NOT_IMPLEMENTED"),
        @UpnpStateVariable(
                name = "NextAVTransportURIMetaData",
                sendEvents = false,
                datatype = "string",
                defaultValue = "NOT_IMPLEMENTED"),
        @UpnpStateVariable(
                name = "RelativeTimePosition",
                sendEvents = false,
                datatype = "string"), // H+:MM:SS[.F+] or H+:MM:SS[.F0/F1] (in track)
        @UpnpStateVariable(
                name = "AbsoluteTimePosition",
                sendEvents = false,
                datatype = "string"), // H+:MM:SS[.F+] or H+:MM:SS[.F0/F1] (in media)
        @UpnpStateVariable(
                name = "RelativeCounterPosition",
                sendEvents = false,
                datatype = "i4",
                defaultValue = "2147483647"), // Max value means not implemented
        @UpnpStateVariable(
                name = "AbsoluteCounterPosition",
                sendEvents = false,
                datatype = "i4",
                defaultValue = "2147483647"), // Max value means not implemented
        @UpnpStateVariable(
                name = "CurrentTransportActions",
                sendEvents = false,
                datatype = "string"), // Play, Stop, Pause, Seek, Next, Previous and Record
        @UpnpStateVariable(
                name = "A_ARG_TYPE_SeekMode",
                sendEvents = false,
                allowedValuesEnum = SeekMode.class), // The 'type' of seek we can perform (or should perform)
        @UpnpStateVariable(
                name = "A_ARG_TYPE_SeekTarget",
                sendEvents = false,
                datatype = "string"), // The actual seek (offset or whatever) value
        @UpnpStateVariable(
                name = "A_ARG_TYPE_InstanceID",
                sendEvents = false,
                datatype = "ui4"),
        @UpnpStateVariable(
                name = "SyncOffset",
                sendEvents = false,
                datatype = "string"),
        @UpnpStateVariable(
                name = "PauseTime",
                sendEvents = false,
                datatype = "string"),
        @UpnpStateVariable(
                name = "ReferenceClockId",
                sendEvents = false,
                datatype = "string"),
        @UpnpStateVariable(
                name = "ReferencePresentationTime",
                sendEvents = false,
                datatype = "string"),
        @UpnpStateVariable(
                name = "StopTime",
                sendEvents = false,
                datatype = "string")

})
public class YaaccAVTransportService implements LastChangeDelegator {

    private UpnpClient upnpClient = null;


    private Map<Long, AVTransportStateMachine> stateMachines = new ConcurrentHashMap();

    Class<? extends AVTransportStateMachine> stateMachineDefinition = null;
    Class<? extends AbstractState> initialState = null;
    Class<? extends AVTransport> transportClass = null;


    @UpnpStateVariable(eventMaximumRateMilliseconds = 200)
    private LastChange lastChange = new LastChange(new AVTransportLastChangeParser());
    protected PropertyChangeSupport propertyChangeSupport = null;


    protected YaaccAVTransportService() {
        this.propertyChangeSupport = new PropertyChangeSupport(this);


    }

    protected YaaccAVTransportService(LastChange lastChange) {
        this.propertyChangeSupport = new PropertyChangeSupport(this);
        this.lastChange = lastChange ;
    }

    protected YaaccAVTransportService(PropertyChangeSupport propertyChangeSupport) {
        this.propertyChangeSupport = propertyChangeSupport;

    }

    protected YaaccAVTransportService(PropertyChangeSupport propertyChangeSupport, LastChange lastChange) {
        this.propertyChangeSupport = propertyChangeSupport;
        this.lastChange = lastChange;
    }


    protected YaaccAVTransportService(Class<? extends AVTransportStateMachine> stateMachineDefinition,
                                      Class<? extends AbstractState> initialState) {
        this(stateMachineDefinition, initialState, (Class<? extends AVTransport>) AVTransport.class);
    }

    protected YaaccAVTransportService(Class<? extends AVTransportStateMachine> stateMachineDefinition,
                                      Class<? extends AbstractState> initialState,
                                      Class<? extends AVTransport> transportClass) {
        this.stateMachineDefinition = stateMachineDefinition;
        this.initialState = initialState;
        this.transportClass = transportClass;
    }

    /**
     *
     */
    public YaaccAVTransportService(UpnpClient upnpClient) {
        this(AvTransportStateMachine.class,
                AvTransportMediaRendererNoMediaPresent.class);
        this.upnpClient = upnpClient;
    }


    @Override
    public LastChange getLastChange() {
        if(lastChange == null){
            lastChange = new LastChange(new AVTransportLastChangeParser());
        }
        return lastChange;
    }

    @Override
    public void appendCurrentState(LastChange lc, UnsignedIntegerFourBytes instanceId) throws Exception {

        MediaInfo mediaInfo = getMediaInfo(instanceId);
        TransportInfo transportInfo = getTransportInfo(instanceId);
        TransportSettings transportSettings = getTransportSettings(instanceId);
        PositionInfo positionInfo = getPositionInfo(instanceId);
        DeviceCapabilities deviceCaps = getDeviceCapabilities(instanceId);

        lc.setEventedValue(
                instanceId,
                new AVTransportVariable.AVTransportURI(URI.create(mediaInfo.getCurrentURI())),
                new AVTransportVariable.AVTransportURIMetaData(mediaInfo.getCurrentURIMetaData()),
                new AVTransportVariable.CurrentMediaDuration(mediaInfo.getMediaDuration()),
                new AVTransportVariable.CurrentPlayMode(transportSettings.getPlayMode()),
                new AVTransportVariable.CurrentRecordQualityMode(transportSettings.getRecQualityMode()),
                new AVTransportVariable.CurrentTrack(positionInfo.getTrack()),
                new AVTransportVariable.CurrentTrackDuration(positionInfo.getTrackDuration()),
                new AVTransportVariable.CurrentTrackMetaData(positionInfo.getTrackMetaData()),
                new AVTransportVariable.CurrentTrackURI(URI.create(positionInfo.getTrackURI())),
                new AvTransportVariable.CurrentTransportActions(getPossibleTransportActions(instanceId)),
                new AVTransportVariable.NextAVTransportURI(URI.create(mediaInfo.getNextURI())),
                new AVTransportVariable.NextAVTransportURIMetaData(mediaInfo.getNextURIMetaData()),
                new AVTransportVariable.NumberOfTracks(mediaInfo.getNumberOfTracks()),
                new AVTransportVariable.PossiblePlaybackStorageMedia(deviceCaps.getPlayMedia()),
                new AVTransportVariable.PossibleRecordQualityModes(deviceCaps.getRecQualityModes()),
                new AVTransportVariable.PossibleRecordStorageMedia(deviceCaps.getRecMedia()),
                new AVTransportVariable.RecordMediumWriteStatus(mediaInfo.getWriteStatus()),
                new AVTransportVariable.RecordStorageMedium(mediaInfo.getRecordMedium()),
                new AVTransportVariable.TransportPlaySpeed(transportInfo.getCurrentSpeed()),
                new AVTransportVariable.TransportState(transportInfo.getCurrentTransportState()),
                new AVTransportVariable.TransportStatus(transportInfo.getCurrentTransportStatus())
        );
    }

    public PropertyChangeSupport getPropertyChangeSupport() {
        return propertyChangeSupport;
    }

    public static UnsignedIntegerFourBytes getDefaultInstanceID() {
        return new UnsignedIntegerFourBytes(0);
    }

    /**
     * Create a StateMachine for AVTransport
     *
     */
    protected AVTransportStateMachine createStateMachine(
            UnsignedIntegerFourBytes instanceId) {
        return StateMachineBuilder.build(
                AvTransportStateMachine.class,
                AvTransportMediaRendererNoMediaPresent.class, new Class[]{
                AvTransport.class, UpnpClient.class}, new Object[]{
                new AvTransport(instanceId, getLastChange(), StorageMedium.NETWORK),
                upnpClient});
    }

    @UpnpAction(name = "GetSyncOffset",
            out = {@UpnpOutputArgument(name = "CurrentSyncOffset", stateVariable = "SyncOffset", getterName = "toString")})
    public SyncOffset getSyncOffset(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId) throws AVTransportException {
        return ((AvTransport)findStateMachine(instanceId).getCurrentState().getTransport()).getSynchronizationInfo().getOffset();
    }

    @UpnpAction(name = "SetSyncOffset")
    public void setSyncOffset(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId,
                              @UpnpInputArgument(name = "NewSyncOffset", stateVariable = "SyncOffset") String offset) throws AVTransportException {
        ((AvTransport)findStateMachine(instanceId).getCurrentState().getTransport()).getSynchronizationInfo().setOffset(new SyncOffset(offset));
    }

    @UpnpAction(name = "AdjustSyncOffset")
    public void adjustSyncOffset(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId,
                                 @UpnpInputArgument(name = "Adjustment", stateVariable = "SyncOffset") String offset) throws AVTransportException {
        ((AvTransport)findStateMachine(instanceId).getCurrentState().getTransport()).getSynchronizationInfo().getOffset().add(new SyncOffset(offset));
    }


    @UpnpAction(name = "SyncPlay")
    public void syncPlay(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId,
                         @UpnpInputArgument(name = "Speed", stateVariable = "TransportPlaySpeed") String speed,
                         @UpnpInputArgument(name = "ReferencePositionUnits", stateVariable = "A_ARG_TYPE_SeekMode") String referencedPositionUnits,
                         @UpnpInputArgument(name = "ReferencePosition", stateVariable = "A_ARG_TYPE_SeekTarget") String referencedPosition,
                         @UpnpInputArgument(name = "ReferencePresentationTime", stateVariable = "A_ARG_TYPE_PresentationTime") String referencedPresentationTime,
                         @UpnpInputArgument(name = "ReferenceClockId", stateVariable = "A_ARG_TYPE_ClockId") String referencedClockId) throws AVTransportException {
        try {
            ((AvTransportStateMachine) findStateMachine(instanceId)).syncPlay(speed, referencedPositionUnits, referencedPosition, referencedPresentationTime, referencedClockId);
        } catch (TransitionException ex) {
            throw new AVTransportException(AVTransportErrorCode.TRANSITION_NOT_AVAILABLE, ex.getMessage());
        }
    }


    @UpnpAction(name = "SyncStop")
    public void syncStop(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId,
                         @UpnpInputArgument(name = "StopTime", stateVariable = "A_ARG_TYPE_PresentationTime") String referencedPresentationTime,
                         @UpnpInputArgument(name = "ReferenceClockId", stateVariable = "A_ARG_TYPE_ClockId") String referencedClockId) throws AVTransportException {
        try {
            ((AvTransportStateMachine) findStateMachine(instanceId)).syncStop(referencedPresentationTime, referencedClockId);
        } catch (TransitionException ex) {
            throw new AVTransportException(AVTransportErrorCode.TRANSITION_NOT_AVAILABLE, ex.getMessage());
        }
    }

    @UpnpAction(name = "SyncPause")
    public void syncPause(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId,
                          @UpnpInputArgument(name = "PauseTime", stateVariable = "A_ARG_TYPE_PresentationTime") String referencedPresentationTime,
                          @UpnpInputArgument(name = "ReferenceClockId", stateVariable = "A_ARG_TYPE_ClockId") String referencedClockId) throws AVTransportException {
        try {
            ((AvTransportStateMachine) findStateMachine(instanceId)).syncStop(referencedPresentationTime, referencedClockId);
        } catch (TransitionException ex) {
            throw new AVTransportException(AVTransportErrorCode.TRANSITION_NOT_AVAILABLE, ex.getMessage());
        }
    }


    @UpnpAction(name = "GetCurrentTransportActions", out = @UpnpOutputArgument(name = "Actions", stateVariable = "CurrentTransportActions"))
    public String getCurrentTransportActionsString(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId)
            throws AVTransportException {
        try {
            return ModelUtil.toCommaSeparatedList(getCurrentTransportActions(instanceId));
        } catch (Exception ex) {
            return ""; // TODO: Empty string is not defined in spec but seems reasonable for no available action?
        }
    }


    protected TransportAction[] getPossibleTransportActions(UnsignedIntegerFourBytes instanceId) throws Exception {
        AVTransportStateMachine stateMachine = findStateMachine(instanceId);
        try {
            return ((YaaccState) stateMachine.getCurrentState()).getPossibleTransportActions();
        } catch (TransitionException ex) {
            Log.d(getClass().getName(),"Exception in state transition ignoring it", ex);
            return new TransportAction[0];
        }
    }



    @UpnpAction
    public void setAVTransportURI(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId,
                                  @UpnpInputArgument(name = "CurrentURI", stateVariable = "AVTransportURI") String currentURI,
                                  @UpnpInputArgument(name = "CurrentURIMetaData", stateVariable = "AVTransportURIMetaData") String currentURIMetaData) throws AVTransportException {


        URI uri;
        try {
            uri = new URI(currentURI);
        } catch (Exception ex) {
            throw new AVTransportException(
                    ErrorCode.INVALID_ARGS, "CurrentURI can not be null or malformed"
            );
        }

        try {
            AVTransportStateMachine transportStateMachine = findStateMachine(instanceId, true);
            transportStateMachine.setTransportURI(uri, currentURIMetaData);
        } catch (TransitionException ex) {
            throw new AVTransportException(AVTransportErrorCode.TRANSITION_NOT_AVAILABLE, ex.getMessage());
        }
        Log.d(getClass().getName(),"setAVTransportURI: " + uri + " currentURIMetaData: " + currentURIMetaData );
    }

    @UpnpAction
    public void setNextAVTransportURI(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId,
                                      @UpnpInputArgument(name = "NextURI", stateVariable = "AVTransportURI") String nextURI,
                                      @UpnpInputArgument(name = "NextURIMetaData", stateVariable = "AVTransportURIMetaData") String nextURIMetaData)
            throws AVTransportException {

        URI uri;
        try {
            uri = new URI(nextURI);
        } catch (Exception ex) {
            throw new AVTransportException(
                    ErrorCode.INVALID_ARGS, "NextURI can not be null or malformed"
            );
        }

        try {
            AVTransportStateMachine transportStateMachine = findStateMachine(instanceId, true);
            transportStateMachine.setNextTransportURI(uri, nextURIMetaData);
        } catch (TransitionException ex) {
            throw new AVTransportException(AVTransportErrorCode.TRANSITION_NOT_AVAILABLE, ex.getMessage());
        }
    }

    @UpnpAction
    public void setPlayMode(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId,
                            @UpnpInputArgument(name = "NewPlayMode", stateVariable = "CurrentPlayMode") String newPlayMode)
            throws AVTransportException {
        AVTransport transport = findStateMachine(instanceId).getCurrentState().getTransport();
        try {
            transport.setTransportSettings(
                    new TransportSettings(
                            PlayMode.valueOf(newPlayMode),
                            transport.getTransportSettings().getRecQualityMode()
                    )
            );
        } catch (IllegalArgumentException ex) {
            throw new AVTransportException(
                    AVTransportErrorCode.PLAYMODE_NOT_SUPPORTED, "Unsupported play mode: " + newPlayMode
            );
        }
    }

    @UpnpAction
    public void setRecordQualityMode(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId,
                                     @UpnpInputArgument(name = "NewRecordQualityMode", stateVariable = "CurrentRecordQualityMode") String newRecordQualityMode)
            throws AVTransportException {
        AVTransport transport = findStateMachine(instanceId).getCurrentState().getTransport();
        try {
            transport.setTransportSettings(
                    new TransportSettings(
                            transport.getTransportSettings().getPlayMode(),
                            RecordQualityMode.valueOrExceptionOf(newRecordQualityMode)
                    )
            );
        } catch (IllegalArgumentException ex) {
            throw new AVTransportException(
                    AVTransportErrorCode.RECORDQUALITYMODE_NOT_SUPPORTED, "Unsupported record quality mode: " + newRecordQualityMode
            );
        }
    }

    @UpnpAction(out = {
            @UpnpOutputArgument(name = "NrTracks", stateVariable = "NumberOfTracks", getterName = "getNumberOfTracks"),
            @UpnpOutputArgument(name = "MediaDuration", stateVariable = "CurrentMediaDuration", getterName = "getMediaDuration"),
            @UpnpOutputArgument(name = "CurrentURI", stateVariable = "AVTransportURI", getterName = "getCurrentURI"),
            @UpnpOutputArgument(name = "CurrentURIMetaData", stateVariable = "AVTransportURIMetaData", getterName = "getCurrentURIMetaData"),
            @UpnpOutputArgument(name = "NextURI", stateVariable = "NextAVTransportURI", getterName = "getNextURI"),
            @UpnpOutputArgument(name = "NextURIMetaData", stateVariable = "NextAVTransportURIMetaData", getterName = "getNextURIMetaData"),
            @UpnpOutputArgument(name = "PlayMedium", stateVariable = "PlaybackStorageMedium", getterName = "getPlayMedium"),
            @UpnpOutputArgument(name = "RecordMedium", stateVariable = "RecordStorageMedium", getterName = "getRecordMedium"),
            @UpnpOutputArgument(name = "WriteStatus", stateVariable = "RecordMediumWriteStatus", getterName = "getWriteStatus")
    })
    public MediaInfo getMediaInfo(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId)
            throws AVTransportException {
        return findStateMachine(instanceId).getCurrentState().getTransport().getMediaInfo();
    }

    @UpnpAction(out = {
            @UpnpOutputArgument(name = "CurrentTransportState", stateVariable = "TransportState", getterName = "getCurrentTransportState"),
            @UpnpOutputArgument(name = "CurrentTransportStatus", stateVariable = "TransportStatus", getterName = "getCurrentTransportStatus"),
            @UpnpOutputArgument(name = "CurrentSpeed", stateVariable = "TransportPlaySpeed", getterName = "getCurrentSpeed")
    })
    public TransportInfo getTransportInfo(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId)
            throws AVTransportException {
        return findStateMachine(instanceId).getCurrentState().getTransport().getTransportInfo();
    }

    @UpnpAction(out = {
            @UpnpOutputArgument(name = "Track", stateVariable = "CurrentTrack", getterName = "getTrack"),
            @UpnpOutputArgument(name = "TrackDuration", stateVariable = "CurrentTrackDuration", getterName = "getTrackDuration"),
            @UpnpOutputArgument(name = "TrackMetaData", stateVariable = "CurrentTrackMetaData", getterName = "getTrackMetaData"),
            @UpnpOutputArgument(name = "TrackURI", stateVariable = "CurrentTrackURI", getterName = "getTrackURI"),
            @UpnpOutputArgument(name = "RelTime", stateVariable = "RelativeTimePosition", getterName = "getRelTime"),
            @UpnpOutputArgument(name = "AbsTime", stateVariable = "AbsoluteTimePosition", getterName = "getAbsTime"),
            @UpnpOutputArgument(name = "RelCount", stateVariable = "RelativeCounterPosition", getterName = "getRelCount"),
            @UpnpOutputArgument(name = "AbsCount", stateVariable = "AbsoluteCounterPosition", getterName = "getAbsCount")
    })
    public PositionInfo getPositionInfo(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId)
            throws AVTransportException {
        Log.d(getClass().getName(),"Transport: " + findStateMachine(instanceId).getCurrentState().getTransport() + " PositionInfo: " + findStateMachine(instanceId).getCurrentState().getTransport().getPositionInfo());
        return findStateMachine(instanceId).getCurrentState().getTransport().getPositionInfo();
    }

    @UpnpAction(out = {
            @UpnpOutputArgument(name = "PlayMedia", stateVariable = "PossiblePlaybackStorageMedia", getterName = "getPlayMediaString"),
            @UpnpOutputArgument(name = "RecMedia", stateVariable = "PossibleRecordStorageMedia", getterName = "getRecMediaString"),
            @UpnpOutputArgument(name = "RecQualityModes", stateVariable = "PossibleRecordQualityModes", getterName = "getRecQualityModesString")
    })
    public DeviceCapabilities getDeviceCapabilities(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId)
            throws AVTransportException {
        return findStateMachine(instanceId).getCurrentState().getTransport().getDeviceCapabilities();
    }

    @UpnpAction(out = {
            @UpnpOutputArgument(name = "PlayMode", stateVariable = "CurrentPlayMode", getterName = "getPlayMode"),
            @UpnpOutputArgument(name = "RecQualityMode", stateVariable = "CurrentRecordQualityMode", getterName = "getRecQualityMode")
    })
    public TransportSettings getTransportSettings(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId)
            throws AVTransportException {
        return findStateMachine(instanceId).getCurrentState().getTransport().getTransportSettings();
    }

    @UpnpAction
    public void stop(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId)
            throws AVTransportException {
        try {
            findStateMachine(instanceId).stop();
        } catch (TransitionException ex) {
            throw new AVTransportException(AVTransportErrorCode.TRANSITION_NOT_AVAILABLE, ex.getMessage());
        }
    }

    @UpnpAction
    public void play(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId,
                     @UpnpInputArgument(name = "Speed", stateVariable = "TransportPlaySpeed") String speed)
            throws AVTransportException {
        try {
            findStateMachine(instanceId).play(speed);
        } catch (TransitionException ex) {
            throw new AVTransportException(AVTransportErrorCode.TRANSITION_NOT_AVAILABLE, ex.getMessage());
        }
    }

    @UpnpAction
    public void pause(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId)
            throws AVTransportException {
        try {
            findStateMachine(instanceId).pause();
        } catch (TransitionException ex) {
            throw new AVTransportException(AVTransportErrorCode.TRANSITION_NOT_AVAILABLE, ex.getMessage());
        }
    }

    @UpnpAction
    public void record(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId)
            throws AVTransportException {
        try {
            findStateMachine(instanceId).record();
        } catch (TransitionException ex) {
            throw new AVTransportException(AVTransportErrorCode.TRANSITION_NOT_AVAILABLE, ex.getMessage());
        }
    }

    @UpnpAction
    public void seek(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId,
                     @UpnpInputArgument(name = "Unit", stateVariable = "A_ARG_TYPE_SeekMode") String unit,
                     @UpnpInputArgument(name = "Target", stateVariable = "A_ARG_TYPE_SeekTarget") String target)
            throws AVTransportException {
        SeekMode seekMode;
        try {
            seekMode = SeekMode.valueOrExceptionOf(unit);
        } catch (IllegalArgumentException ex) {
            throw new AVTransportException(
                    AVTransportErrorCode.SEEKMODE_NOT_SUPPORTED, "Unsupported seek mode: " + unit
            );
        }

        try {
            findStateMachine(instanceId).seek(seekMode, target);
        } catch (TransitionException ex) {
            throw new AVTransportException(AVTransportErrorCode.TRANSITION_NOT_AVAILABLE, ex.getMessage());
        }
    }

    @UpnpAction
    public void next(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId)
            throws AVTransportException {
        try {
            findStateMachine(instanceId).next();
        } catch (TransitionException ex) {
            throw new AVTransportException(AVTransportErrorCode.TRANSITION_NOT_AVAILABLE, ex.getMessage());
        }
    }

    @UpnpAction
    public void previous(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId)
            throws AVTransportException {
        try {
            findStateMachine(instanceId).previous();
        } catch (TransitionException ex) {
            throw new AVTransportException(AVTransportErrorCode.TRANSITION_NOT_AVAILABLE, ex.getMessage());
        }
    }


    protected org.fourthline.cling.support.model.TransportAction[] getCurrentTransportActions(UnsignedIntegerFourBytes instanceId) throws Exception {
        AVTransportStateMachine stateMachine = findStateMachine(instanceId);
        try {
            return stateMachine.getCurrentState().getCurrentTransportActions();
        } catch (TransitionException ex) {
            return new org.fourthline.cling.support.model.TransportAction[0];
        }
    }

    @Override
    public UnsignedIntegerFourBytes[] getCurrentInstanceIds() {
        synchronized (stateMachines) {
            UnsignedIntegerFourBytes[] ids = new UnsignedIntegerFourBytes[stateMachines.size()];
            int i = 0;
            for (Long id : stateMachines.keySet()) {
                ids[i] = new UnsignedIntegerFourBytes(id);
                i++;
            }
            return ids;
        }
    }

    protected AVTransportStateMachine findStateMachine(UnsignedIntegerFourBytes instanceId) throws AVTransportException {
        return findStateMachine(instanceId, true);
    }

    protected AVTransportStateMachine findStateMachine(UnsignedIntegerFourBytes instanceId, boolean createDefaultTransport) throws AVTransportException {
        synchronized (stateMachines) {
            long id = instanceId.getValue();
            AVTransportStateMachine stateMachine = stateMachines.get(id);
            if (stateMachine == null && createDefaultTransport) {
                Log.d(getClass().getName(),"Creating stateMachine instance with ID '"+id+"'");
                stateMachine = createStateMachine(instanceId);
                stateMachines.put(id, stateMachine);
            } else if (stateMachine == null) {
                throw new AVTransportException(AVTransportErrorCode.INVALID_INSTANCE_ID);
            }
            Log.d(getClass().getName(),"Found transport control with ID '" + id + "'");
            return stateMachine;
        }
    }

}
