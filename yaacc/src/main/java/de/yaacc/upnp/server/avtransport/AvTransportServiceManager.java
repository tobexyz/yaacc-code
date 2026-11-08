/*
 * Copyright (C) 2026 Tobias Schoene www.yaacc.de
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

import org.fourthline.cling.model.meta.LocalService;
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;
import org.fourthline.cling.support.avtransport.lastchange.AVTransportLastChangeParser;
import org.fourthline.cling.support.avtransport.lastchange.AVTransportVariable;
import org.fourthline.cling.support.lastchange.LastChange;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import de.yaacc.upnp.server.events.AsyncLastChangeServiceManager;

/**
 * Service manager of the media renderer's AVTransport service: the full state as the initial event and the
 * {@code LastChange} (transport state, URIs, {@code CurrentPlayMode}, ...) sent to the subscribers after every
 * change, without ever holding up a command. The machinery is {@link AsyncLastChangeServiceManager}'s, shared
 * with the RenderingControl service; this class only creates the service instance and says what the log shows
 * (the play mode, which is what Home Assistant's repeat and shuffle buttons follow).
 */
public class AvTransportServiceManager extends AsyncLastChangeServiceManager<YaaccAVTransportService> {

    public AvTransportServiceManager(LocalService<YaaccAVTransportService> service, int lockTimeoutMillis) {
        this(service, lockTimeoutMillis, createExecutor("avtransport-events"));
    }

    AvTransportServiceManager(LocalService<YaaccAVTransportService> service, int lockTimeoutMillis,
                              ScheduledExecutorService executor) {
        super(service, new AVTransportLastChangeParser(), lockTimeoutMillis, executor, "AVT");
    }

    @Override
    protected YaaccAVTransportService createServiceInstance() {
        YaaccAVTransportService service = new YaaccAVTransportService();
        attachEvents(service.getLastChange(), service.getPropertyChangeSupport(), service);
        return service;
    }

    /**
     * A change of the transport state (and of what can be done in it) is not paced: a control point acts on the last state
     * it saw, see {@link AsyncLastChangeServiceManager}.
     */
    @Override
    protected boolean isUrgent(LastChange lastChange) {
        for (UnsignedIntegerFourBytes instanceId : lastChange.getInstanceIDs()) {
            if (lastChange.getEventedValue(instanceId, AVTransportVariable.TransportState.class) != null
                    || lastChange.getEventedValue(instanceId, AVTransportVariable.CurrentTransportActions.class) != null) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected List<String> describeChanges(LastChange lastChange) {
        List<String> descriptions = new ArrayList<>();
        for (UnsignedIntegerFourBytes instanceId : lastChange.getInstanceIDs()) {
            AVTransportVariable.TransportState transportState =
                    lastChange.getEventedValue(instanceId, AVTransportVariable.TransportState.class);
            if (transportState != null) {
                descriptions.add("TransportState=" + transportState.getValue() + " (instance " + instanceId.getValue() + ")");
            }
            AVTransportVariable.CurrentPlayMode playMode =
                    lastChange.getEventedValue(instanceId, AVTransportVariable.CurrentPlayMode.class);
            if (playMode != null) {
                descriptions.add("CurrentPlayMode=" + playMode.getValue() + " (instance " + instanceId.getValue() + ")");
            }
        }
        return descriptions;
    }

    @Override
    protected String describeCurrentState() throws Exception {
        return "CurrentPlayMode="
                + getImplementation().getTransportSettings(YaaccAVTransportService.getDefaultInstanceID()).getPlayMode();
    }
}
