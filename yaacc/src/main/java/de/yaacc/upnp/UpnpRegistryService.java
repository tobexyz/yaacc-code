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
package de.yaacc.upnp;

import org.fourthline.cling.android.AndroidUpnpServiceConfiguration;
import org.fourthline.cling.android.AndroidUpnpServiceImpl;
import org.fourthline.cling.model.types.ServiceType;
import org.fourthline.cling.model.types.UDADeviceType;
import org.fourthline.cling.model.types.UDAServiceType;

/**
 * This is an android service to provide access to an upnp registry.
 * 
 * @author Tobias Schöne (openbit)
 * 
 */
public class UpnpRegistryService extends AndroidUpnpServiceImpl {

	@Override
	protected AndroidUpnpServiceConfiguration createConfiguration() {

		return new AndroidUpnpServiceConfiguration() {
			@Override
			public int getRegistryMaintenanceIntervalMillis() {
				return 7000;
			}

			@Override
			public ServiceType[] getExclusiveServiceTypes() {

				return new ServiceType[] { new UDAServiceType("AVTransport"), new UDAServiceType("ContentDirectory"), new UDAServiceType("ConnectionManager"), new UDAServiceType("RenderingControl"), new UDAServiceType("X_MS_MediaReceiverRegistrar") };
			}

		};
	}

}
