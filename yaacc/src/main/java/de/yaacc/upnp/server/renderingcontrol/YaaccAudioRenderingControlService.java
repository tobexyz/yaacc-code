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
import android.media.AudioManager;
import de.yaacc.util.YaaccLogger;

import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;
import org.fourthline.cling.model.types.UnsignedIntegerTwoBytes;
import org.fourthline.cling.support.model.Channel;
import org.fourthline.cling.support.renderingcontrol.AbstractAudioRenderingControl;
import org.fourthline.cling.support.renderingcontrol.RenderingControlException;


/**
 * @author Tobias Schoene (openbit)
 */
public class YaaccAudioRenderingControlService extends
        AbstractAudioRenderingControl {


    private final Context context;

    public YaaccAudioRenderingControlService(Context context) {
        this.context = context;
    }

    @Override
    public boolean getMute(UnsignedIntegerFourBytes instanceId, String channelName)
            throws RenderingControlException {
        YaaccLogger.d(getClass().getName(), "getMute() ");
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            return audioManager.isStreamMute(AudioManager.STREAM_MUSIC);
        }
        return false;
    }

    @Override
    public UnsignedIntegerTwoBytes getVolume(UnsignedIntegerFourBytes instanceId,
                                             String channelName) throws RenderingControlException {
        YaaccLogger.d(getClass().getName(), "getVolume() ");
        int volume = 0;
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            volume = currentVolume * 100 / maxVolume;
        }
        return new UnsignedIntegerTwoBytes(volume);
    }

    @Override
    public void setMute(UnsignedIntegerFourBytes instanceId, String channelName, boolean desiredMute)
            throws RenderingControlException {
        YaaccLogger.d(getClass().getName(), "setMute()");
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, desiredMute ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0);
        }

    }

    @Override
    public void setVolume(UnsignedIntegerFourBytes instanceId, String channelName,
                          UnsignedIntegerTwoBytes desiredVolume) throws RenderingControlException {
        YaaccLogger.d(getClass().getName(), "setVolume() ");
        int desired = desiredVolume.getValue() != null ? desiredVolume.getValue().intValue() : 0;
        if (desired < 0) {
            desired = 0;
        }
        if (desired > 100) {
            desired = 100;
        }
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int volume = desired * maxVolume / 100;
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, AudioManager.FLAG_SHOW_UI);
        }
    }

    @Override
    public UnsignedIntegerFourBytes[] getCurrentInstanceIds() {
        YaaccLogger.d(getClass().getName(), " getCurrentInstanceIds() - not yet implemented");
        return null;
    }

    @Override
    protected Channel[] getCurrentChannels() {
        YaaccLogger.d(getClass().getName(), " getCurrentChannels() - not yet implemented");
        return null;
    }

}
