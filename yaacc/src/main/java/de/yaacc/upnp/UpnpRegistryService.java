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
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import org.fourthline.cling.UpnpService;
import org.fourthline.cling.UpnpServiceConfiguration;
import org.fourthline.cling.UpnpServiceImpl;
import org.fourthline.cling.android.AndroidRouter;
import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.controlpoint.ControlPoint;
import org.fourthline.cling.protocol.ProtocolFactory;
import org.fourthline.cling.registry.Registry;
import org.fourthline.cling.transport.Router;

/**
 * This is an android service to provide access to an upnp registry.
 *
 * @author Tobias Schöne (openbit)
 */
public class UpnpRegistryService extends Service {

    protected UpnpService upnpService;
    protected Binder binder = new Binder();


    /**
     * Starts the UPnP service.
     */
    @Override
    public void onCreate() {
        long start = System.currentTimeMillis();
        super.onCreate();

        upnpService = new UpnpServiceImpl(createConfiguration()) {

            @Override
            protected Router createRouter(ProtocolFactory protocolFactory, Registry registry) {
                return UpnpRegistryService.this.createRouter(
                        getConfiguration(),
                        protocolFactory,
                        UpnpRegistryService.this
                );
            }

            @Override
            public synchronized void shutdown() {
                // First have to remove the receiver, so Android won't complain about it leaking
                // when the main UI thread exits.
                ((AndroidRouter) getRouter()).unregisterBroadcastReceiver();

                // Now we can concurrently run the Cling shutdown code, without occupying the
                // Android main UI thread. This will complete probably after the main UI thread
                // is done.
                super.shutdown(true);
            }
        };

        Log.d(this.getClass().getName(), "on start took: " + (System.currentTimeMillis() - start));
    }

    protected YaaccUpnpServiceConfiguration createConfiguration() {

        return new YaaccUpnpServiceConfiguration();
    }

    public UpnpService getUpnpService() {
        return upnpService;
    }

    protected AndroidRouter createRouter(UpnpServiceConfiguration configuration,
                                         ProtocolFactory protocolFactory,
                                         Context context) {
        return new AndroidRouter(configuration, protocolFactory, context);
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

    protected class Binder extends android.os.Binder implements AndroidUpnpService {

        public UpnpService get() {
            return upnpService;
        }

        public UpnpServiceConfiguration getConfiguration() {
            return upnpService.getConfiguration();
        }

        public Registry getRegistry() {
            return upnpService.getRegistry();
        }

        public ControlPoint getControlPoint() {
            return upnpService.getControlPoint();
        }
    }


}
