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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package de.yaacc.browser;

import org.fourthline.cling.model.meta.Device;

/**
 * Holds the current status of a UPnP renderer.
 */
public class RendererStatus {
    public enum State { PLAYING, PAUSED, STOPPED, NO_MEDIA }
    
    private final Device device;
    private State state;
    private String trackTitle;
    private int volume;
    
    public RendererStatus(Device device, String upnpState, String trackTitle, int volume) {
        this.device = device;
        this.state = parseState(upnpState);
        this.trackTitle = trackTitle;
        this.volume = volume;
    }
    
    private State parseState(String upnpState) {
        if ("PLAYING".equals(upnpState)) return State.PLAYING;
        if ("PAUSED_PLAYBACK".equals(upnpState)) return State.PAUSED;
        if ("STOPPED".equals(upnpState)) return State.STOPPED;
        return State.NO_MEDIA;
    }
    
    public Device getDevice() {
        return device;
    }
    
    public State getState() {
        return state;
    }
    
    public String getTrackTitle() {
        return trackTitle;
    }
    
    public int getVolume() {
        return volume;
    }
    
    public boolean isPlaying() {
        return state == State.PLAYING;
    }
}
