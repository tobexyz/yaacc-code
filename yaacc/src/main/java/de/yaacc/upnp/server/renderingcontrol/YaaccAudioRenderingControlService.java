/*
 *
 * Copyright (C) 2013 Tobias Schoene www.yaacc.de
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

import android.content.Context;

import org.fourthline.cling.model.types.ErrorCode;
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;
import org.fourthline.cling.model.types.UnsignedIntegerTwoBytes;
import org.fourthline.cling.support.lastchange.EventedValue;
import org.fourthline.cling.support.model.Channel;
import org.fourthline.cling.support.renderingcontrol.AbstractAudioRenderingControl;
import org.fourthline.cling.support.renderingcontrol.RenderingControlException;
import org.fourthline.cling.support.renderingcontrol.lastchange.ChannelMute;
import org.fourthline.cling.support.renderingcontrol.lastchange.ChannelVolume;
import org.fourthline.cling.support.renderingcontrol.lastchange.RenderingControlLastChangeParser;
import org.fourthline.cling.support.renderingcontrol.lastchange.RenderingControlVariable;

import java.beans.PropertyChangeSupport;
import java.util.ArrayList;
import java.util.List;

import de.yaacc.upnp.server.events.ObservableLastChange;
import de.yaacc.upnp.server.events.SubscriberAwarePropertyChangeSupport;
import de.yaacc.util.YaaccLogger;


/**
 * The renderer's RenderingControl service: volume and mute of the music stream of the device, one instance
 * (0) with one channel ({@code Master}). The value is not stored here, it is the system's: {@link SystemVolume}.
 * <p>
 * <b>Events.</b> Volume and mute are reported in the service's {@code LastChange}. A change is put into it by
 * {@code SetVolume}/{@code SetMute} (and any change through {@link SystemVolume}, e.g. the app's volume
 * controls) and by {@link #onSystemVolumeChanged()}, which the system's volume broadcast calls (hardware keys,
 * the system's panel). The sending is the service manager's job ({@link RenderingControlServiceManager}).
 * The broadcast is only watched while a control point is subscribed.
 */
public class YaaccAudioRenderingControlService extends
        AbstractAudioRenderingControl {

    private static final String MASTER = Channel.Master.name();

    private final SystemVolume volume;
    private final VolumeWatcher watcher;
    private final SubscriberAwarePropertyChangeSupport subscribers = new SubscriberAwarePropertyChangeSupport(this);
    private final Runnable changeCallback = this::onSystemVolumeChanged;
    private final Object watchLock = new Object();
    private boolean watching; // guarded by watchLock
    // what the subscribers know (volume and mute), to put only a change into the LastChange; guarded by stateLock
    private final Object stateLock = new Object();
    private Boolean knownMute;
    private Integer knownVolume;

    public YaaccAudioRenderingControlService(Context context) {
        this(new SystemVolume(context), new SystemVolumeBroadcastWatcher(context));
    }

    YaaccAudioRenderingControlService(SystemVolume volume, VolumeWatcher watcher) {
        super(new ObservableLastChange(new RenderingControlLastChangeParser()));
        this.volume = volume;
        this.watcher = watcher;
        subscribers.setSubscriberCountListener(this::onSubscribersChanged);
    }

    /** The subscriptions listen here; it tells this service when the first one comes and the last one goes. */
    @Override
    public PropertyChangeSupport getPropertyChangeSupport() {
        return subscribers;
    }

    @Override
    public boolean getMute(UnsignedIntegerFourBytes instanceId, String channelName)
            throws RenderingControlException {
        YaaccLogger.v(getClass().getName(), "getMute() ");
        try {
            return volume.isMuted();
        } catch (RuntimeException e) {
            throw failed("getMute", e);
        }
    }

    @Override
    public UnsignedIntegerTwoBytes getVolume(UnsignedIntegerFourBytes instanceId,
                                             String channelName) throws RenderingControlException {
        YaaccLogger.v(getClass().getName(), "getVolume() ");
        try {
            return new UnsignedIntegerTwoBytes(volume.getPercent());
        } catch (RuntimeException e) {
            throw failed("getVolume", e);
        }
    }

    @Override
    public void setMute(UnsignedIntegerFourBytes instanceId, String channelName, boolean desiredMute)
            throws RenderingControlException {
        YaaccLogger.d(getClass().getName(), "setMute(" + desiredMute + ")");
        try {
            volume.setMuted(desiredMute); // tells publishVolumeState() through SystemVolume's listeners, if anybody is subscribed
        } catch (RuntimeException e) {
            throw failed("setMute", e);
        }
    }

    @Override
    public void setVolume(UnsignedIntegerFourBytes instanceId, String channelName,
                          UnsignedIntegerTwoBytes desiredVolume) throws RenderingControlException {
        int desired = desiredVolume == null || desiredVolume.getValue() == null ? 0 : desiredVolume.getValue().intValue();
        YaaccLogger.d(getClass().getName(), "setVolume(" + desired + ")");
        try {
            volume.setPercent(desired);
        } catch (RuntimeException e) {
            throw failed("setVolume", e);
        }
    }

    @Override
    public UnsignedIntegerFourBytes[] getCurrentInstanceIds() {
        return new UnsignedIntegerFourBytes[]{getDefaultInstanceID()};
    }

    @Override
    protected Channel[] getCurrentChannels() {
        return new Channel[]{Channel.Master};
    }

    /**
     * The system's volume or mute state may have changed (the system's volume broadcast, a change through
     * {@link SystemVolume}): puts what differs from what the subscribers know into the LastChange, which
     * asks the service manager for an event. Nothing happens when nothing changed. Package private: the test's
     * stand-in for the {@code AudioManager} callback.
     */
    void onSystemVolumeChanged() {
        synchronized (stateLock) { // read, compare and put in one step: the last reading is the last one put
            boolean mute;
            int percent;
            try {
                mute = volume.isMuted();
                percent = volume.getPercent();
            } catch (RuntimeException e) {
                YaaccLogger.w(getClass().getName(), "Volume not readable, no event", e);
                return;
            }
            List<EventedValue> changes = new ArrayList<>();
            if (knownMute == null || knownMute != mute) {
                changes.add(new RenderingControlVariable.Mute(new ChannelMute(Channel.Master, mute)));
            }
            if (knownVolume == null || knownVolume != percent) {
                changes.add(new RenderingControlVariable.Volume(new ChannelVolume(Channel.Master, percent)));
            }
            if (changes.isEmpty()) {
                return;
            }
            knownMute = mute;
            knownVolume = percent;
            getLastChange().setEventedValue(getDefaultInstanceID(), changes.toArray(new EventedValue[0]));
        }
    }

    private void onSubscribersChanged(int count) {
        synchronized (watchLock) {
            if (count > 0 && !watching) {
                watching = true;
                startWatching();
            } else if (count == 0 && watching) {
                watching = false;
                stopWatching();
            }
        }
    }

    private void startWatching() {
        SystemVolume.addChangeListener(changeCallback);
        try {
            watcher.start(changeCallback);
        } catch (RuntimeException e) {
            // the in-process changes (control point, app) are still seen
            YaaccLogger.w(getClass().getName(), "System volume broadcast not watched", e);
        }
        // the state a new subscriber gets is read after it is registered, so this is what it knows; later changes differ from it
        synchronized (stateLock) {
            try {
                knownMute = volume.isMuted();
                knownVolume = volume.getPercent();
            } catch (RuntimeException e) {
                knownMute = null;
                knownVolume = null;
            }
        }
        YaaccLogger.d(getClass().getName(), "RCS watching the system volume: first subscriber");
    }

    private void stopWatching() {
        SystemVolume.removeChangeListener(changeCallback);
        try {
            watcher.stop();
        } catch (RuntimeException e) {
            YaaccLogger.w(getClass().getName(), "System volume broadcast not stopped", e);
        }
        synchronized (stateLock) {
            knownMute = null;
            knownVolume = null;
        }
        YaaccLogger.d(getClass().getName(), "RCS not watching the system volume: no subscriber left");
    }

    private RenderingControlException failed(String action, RuntimeException e) {
        YaaccLogger.w(getClass().getName(), action + " failed", e);
        return new RenderingControlException(ErrorCode.ACTION_FAILED, action + " failed: " + e.getMessage());
    }
}
