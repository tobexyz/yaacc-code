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
package de.yaacc.upnp;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import org.fourthline.cling.UpnpService;
import org.fourthline.cling.UpnpServiceImpl;
import org.fourthline.cling.protocol.ProtocolFactory;
import org.fourthline.cling.registry.Registry;
import org.fourthline.cling.transport.Router;

import de.yaacc.upnp.server.YaaccRouter;

/**
 * This is an android service to provide access to an upnp registry.
 *
 * @author Tobias Schöne (openbit)
 */
public class UpnpRegistryService extends Service {

    protected UpnpService upnpService;
    protected IBinder binder = new UpnpRegistryServiceBinder();


    /**
     * Starts the UPnP service.
     */
    @Override
    public void onCreate() {
        long start = System.currentTimeMillis();
        super.onCreate();

        upnpService = new UpnpServiceImpl(new YaaccUpnpServiceConfiguration()) {

            @Override
            protected Router createRouter(ProtocolFactory protocolFactory, Registry registry) {
                return new YaaccRouter(getConfiguration(), protocolFactory, UpnpRegistryService.this);
            }

            @Override
            public synchronized void shutdown() {
                // Now we can concurrently run the Cling shutdown code, without occupying the
                // Android main UI thread. This will complete probably after the main UI thread
                // is done.
                super.shutdown(true);
            }
        };

        Log.d(this.getClass().getName(), "on start took: " + (System.currentTimeMillis() - start));
    }


    public UpnpService getUpnpService() {
        return upnpService;
    }


    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    /**
     * Stops the UPnP service, when the last Activity unbinds from this Service.
     */
    @Override
    public void onDestroy() {
        upnpService.shutdown();
        super.onDestroy();
    }

    protected class UpnpRegistryServiceBinder extends android.os.Binder {

        public UpnpRegistryService getService() {
            return UpnpRegistryService.this;
        }

    }


}
