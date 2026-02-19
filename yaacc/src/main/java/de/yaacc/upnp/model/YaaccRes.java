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
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package de.yaacc.upnp.model;

import org.fourthline.cling.support.model.ProtocolInfo;
import org.fourthline.cling.support.model.Res;

/**
 * Lightweight Resource wrapper that avoids Cling overhead.
 * Converts to Cling Res only when needed for serialization.
 */
public class YaaccRes {
    
    private final ProtocolInfo protocolInfo;
    private final Long size;
    private final String duration;
    private final Long bitrate;
    private final Long sampleFrequency;
    private final Long nrAudioChannels;
    private final Long bitsPerSample;
    private final String resolution;
    private final String value;
    
    public YaaccRes(ProtocolInfo protocolInfo, Long size, String duration, Long bitrate, String value) {
        this.protocolInfo = protocolInfo;
        this.size = size;
        this.duration = duration;
        this.bitrate = bitrate;
        this.sampleFrequency = null;
        this.nrAudioChannels = null;
        this.bitsPerSample = null;
        this.resolution = null;
        this.value = value;
    }
    
    public YaaccRes(ProtocolInfo protocolInfo, Long size, String duration, Long bitrate, 
                    Long sampleFrequency, Long nrAudioChannels, Long bitsPerSample, String value) {
        this.protocolInfo = protocolInfo;
        this.size = size;
        this.duration = duration;
        this.bitrate = bitrate;
        this.sampleFrequency = sampleFrequency;
        this.nrAudioChannels = nrAudioChannels;
        this.bitsPerSample = bitsPerSample;
        this.resolution = null;
        this.value = value;
    }
    
    public YaaccRes(ProtocolInfo protocolInfo, Long size, String resolution, String value) {
        this.protocolInfo = protocolInfo;
        this.size = size;
        this.duration = null;
        this.bitrate = null;
        this.sampleFrequency = null;
        this.nrAudioChannels = null;
        this.bitsPerSample = null;
        this.resolution = resolution;
        this.value = value;
    }
    
    public ProtocolInfo getProtocolInfo() {
        return protocolInfo;
    }
    
    public Long getSize() {
        return size;
    }
    
    public String getDuration() {
        return duration;
    }
    
    public Long getBitrate() {
        return bitrate;
    }
    
    public Long getSampleFrequency() {
        return sampleFrequency;
    }
    
    public Long getNrAudioChannels() {
        return nrAudioChannels;
    }
    
    public Long getBitsPerSample() {
        return bitsPerSample;
    }
    
    public String getResolution() {
        return resolution;
    }
    
    public String getValue() {
        return value;
    }
    
    /**
     * Convert to Cling Res for UPnP serialization.
     */
    public Res toClingRes() {
        Res res = new Res(protocolInfo, size, duration, bitrate, value);
        if (sampleFrequency != null) res.setSampleFrequency(sampleFrequency);
        if (nrAudioChannels != null) res.setNrAudioChannels(nrAudioChannels);
        if (bitsPerSample != null) res.setBitsPerSample(bitsPerSample);
        if (resolution != null) res.setResolution(resolution);
        return res;
    }
    
    /**
     * Create from Cling Res.
     */
    public static YaaccRes fromClingRes(Res res) {
        YaaccRes yaaccRes = new YaaccRes(
            res.getProtocolInfo(),
            res.getSize(),
            res.getDuration(),
            res.getBitrate(),
            res.getValue()
        );
        // Note: sampleFrequency, nrAudioChannels, bitsPerSample, resolution not preserved
        return yaaccRes;
    }
}
