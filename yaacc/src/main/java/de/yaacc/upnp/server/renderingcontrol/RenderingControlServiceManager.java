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
package de.yaacc.upnp.server.renderingcontrol;

import org.fourthline.cling.model.meta.LocalService;
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;
import org.fourthline.cling.support.lastchange.LastChange;
import org.fourthline.cling.support.renderingcontrol.AbstractAudioRenderingControl;
import org.fourthline.cling.support.renderingcontrol.lastchange.RenderingControlLastChangeParser;
import org.fourthline.cling.support.renderingcontrol.lastchange.RenderingControlVariable;
import org.fourthline.cling.support.model.Channel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

import de.yaacc.upnp.server.events.AsyncLastChangeServiceManager;

/**
 * Service manager of the media renderer's RenderingControl service: the full state (volume and mute of the
 * {@code Master} channel, ...) as the initial event and the {@code LastChange} sent to the subscribers after
 * every change, with the machinery the AVTransport service uses ({@link AsyncLastChangeServiceManager}).
 */
public class RenderingControlServiceManager extends AsyncLastChangeServiceManager<AbstractAudioRenderingControl> {

    private final Supplier<? extends AbstractAudioRenderingControl> instanceFactory;

    /**
     * @param instanceFactory creates the one service instance, when it is first needed
     */
    public RenderingControlServiceManager(LocalService<AbstractAudioRenderingControl> service, int lockTimeoutMillis,
                                          Supplier<? extends AbstractAudioRenderingControl> instanceFactory) {
        this(service, lockTimeoutMillis, instanceFactory, createExecutor("rendering-control-events"));
    }

    RenderingControlServiceManager(LocalService<AbstractAudioRenderingControl> service, int lockTimeoutMillis,
                                   Supplier<? extends AbstractAudioRenderingControl> instanceFactory,
                                   ScheduledExecutorService executor) {
        super(service, new RenderingControlLastChangeParser(), lockTimeoutMillis, executor, "RCS");
        this.instanceFactory = instanceFactory;
    }

    @Override
    protected AbstractAudioRenderingControl createServiceInstance() {
        AbstractAudioRenderingControl service = instanceFactory.get();
        attachEvents(service.getLastChange(), service.getPropertyChangeSupport(), service);
        return service;
    }

    @Override
    protected List<String> describeChanges(LastChange lastChange) {
        List<String> descriptions = new ArrayList<>();
        for (UnsignedIntegerFourBytes instanceId : lastChange.getInstanceIDs()) {
            RenderingControlVariable.Mute mute = lastChange.getEventedValue(instanceId, RenderingControlVariable.Mute.class);
            RenderingControlVariable.Volume volume = lastChange.getEventedValue(instanceId, RenderingControlVariable.Volume.class);
            StringBuilder text = new StringBuilder();
            if (mute != null) {
                text.append("Mute=").append(mute.getValue().getMute()).append(' ');
            }
            if (volume != null) {
                text.append("Volume=").append(volume.getValue().getVolume()).append(' ');
            }
            if (text.length() > 0) {
                descriptions.add(text + "(instance " + instanceId.getValue() + ")");
            }
        }
        return descriptions;
    }

    @Override
    protected String describeCurrentState() throws Exception {
        AbstractAudioRenderingControl service = getImplementation();
        UnsignedIntegerFourBytes instance = AbstractAudioRenderingControl.getDefaultInstanceID();
        String master = Channel.Master.name();
        return "Mute=" + service.getMute(instance, master) + " Volume=" + service.getVolume(instance, master).getValue();
    }
}
