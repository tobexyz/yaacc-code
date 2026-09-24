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

import android.os.Handler;
import android.os.Looper;

import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.UDAServiceId;
import org.fourthline.cling.support.model.DIDLContent;
import org.fourthline.cling.support.model.PositionInfo;
import org.fourthline.cling.support.model.TransportInfo;
import org.fourthline.cling.support.model.item.Item;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.yaacc.upnp.callback.avtransport.GetPositionInfo;
import de.yaacc.upnp.callback.avtransport.GetTransportInfo;
import de.yaacc.upnp.callback.renderingcontrol.GetVolume;
import de.yaacc.upnp.server.http.HttpRequestSender;
import de.yaacc.util.YaaccLogger;

/**
 * Monitors UPnP renderer status by polling AVTransport and RenderingControl services.
 */
public class RendererStatusMonitor {
    private static final int POLL_INTERVAL_MS = 10000; // Reduced frequency to 10 seconds
    
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<Device, RendererStatus> statusMap = new HashMap<>();
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final HttpRequestSender httpRequestSender = new HttpRequestSender();
    private StatusListener listener;
    
    public interface StatusListener {
        void onStatusChanged(RendererStatus status);
    }
    
    public void setListener(StatusListener listener) {
        this.listener = listener;
    }
    
    public void startMonitoring(List<Device> devices) {
        for (Device device : devices) {
            pollDevice(device);
        }
    }
    
    public void stopMonitoring() {
        handler.removeCallbacksAndMessages(null);
    }
    
    public RendererStatus getStatus(Device device) {
        return statusMap.get(device);
    }
    
    private void pollDevice(Device device) {
        Service avTransport = device.findService(new UDAServiceId("AVTransport"));
        if (avTransport == null) {
            handler.postDelayed(() -> pollDevice(device), POLL_INTERVAL_MS);
            return;
        }
        
        executorService.execute(new GetTransportInfo(avTransport, httpRequestSender) {
            @Override
            public void received(ActionInvocation invocation, TransportInfo transportInfo) {
                String state = transportInfo.getCurrentTransportState().getValue();
                getTrackTitleAndVolume(device, state);
            }
            
            @Override
            public void failure(ActionInvocation invocation, UpnpResponse response, String msg) {
                YaaccLogger.e(getClass().getName(), "GetTransportInfo failed: " + msg);
                handler.postDelayed(() -> pollDevice(device), POLL_INTERVAL_MS);
            }
        });
    }
    
    private void getTrackTitleAndVolume(Device device, String state) {
        if ("PLAYING".equals(state) || "PAUSED_PLAYBACK".equals(state)) {
            Service avTransport = device.findService(new UDAServiceId("AVTransport"));
            executorService.execute(new GetPositionInfo(avTransport, httpRequestSender) {
                @Override
                public void received(ActionInvocation invocation, PositionInfo positionInfo) {
                    String title = parseTrackTitle(positionInfo.getTrackMetaData());
                    getVolume(device, state, title);
                }
                
                @Override
                public void failure(ActionInvocation invocation, UpnpResponse response, String msg) {
                    getVolume(device, state, null);
                }
            });
        } else {
            getVolume(device, state, null);
        }
    }
    
    private void getVolume(Device device, String state, String trackTitle) {
        Service renderingControl = device.findService(new UDAServiceId("RenderingControl"));
        if (renderingControl == null) {
            updateStatus(device, state, trackTitle, 50);
            return;
        }
        
        // Check if GetVolume action is supported
        if (renderingControl.getAction("GetVolume") == null) {
            YaaccLogger.d(getClass().getName(), "GetVolume action not supported by device: " + device.getDisplayString());
            updateStatus(device, state, trackTitle, 50);
            return;
        }
        
        executorService.execute(new GetVolume(renderingControl, httpRequestSender) {
            @Override
            public void received(ActionInvocation invocation, int currentVolume) {
                updateStatus(device, state, trackTitle, currentVolume);
            }
            
            @Override
            public void failure(ActionInvocation invocation, UpnpResponse response, String msg) {
                updateStatus(device, state, trackTitle, 50);
            }
        });
    }
    
    private void updateStatus(Device device, String state, String trackTitle, int volume) {
        RendererStatus status = new RendererStatus(device, state, trackTitle, volume);
        statusMap.put(device, status);
        
        if (listener != null) {
            handler.post(() -> listener.onStatusChanged(status));
        }
        
        handler.postDelayed(() -> pollDevice(device), POLL_INTERVAL_MS);
    }
    
    private String parseTrackTitle(String trackMetaData) {
        if (trackMetaData == null || trackMetaData.isEmpty()) {
            return null;
        }
        
        try {
            DIDLContent metadata = new org.fourthline.cling.support.contentdirectory.DIDLParser().parse(trackMetaData);
            List<Item> items = metadata.getItems();
            if (!items.isEmpty()) {
                return items.get(0).getTitle();
            }
        } catch (Exception e) {
            YaaccLogger.d(getClass().getName(), "Failed to parse track metadata");
        }
        
        return null;
    }
}
