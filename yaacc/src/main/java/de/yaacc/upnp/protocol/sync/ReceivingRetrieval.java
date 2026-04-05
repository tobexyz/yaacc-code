/*
 *
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
/*
 * Copyright (C) 2013 4th Line GmbH, Switzerland
 *
 * The contents of this file are subject to the terms of either the GNU
 * Lesser General Public License Version 2 or later ("LGPL") or the
 * Common Development and Distribution License Version 1 or later
 * ("CDDL") (collectively, the "License"). You may not use this file
 * except in compliance with the License. See LICENSE.txt for more
 * information.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package de.yaacc.upnp.protocol.sync;

import org.fourthline.cling.binding.xml.DescriptorBindingException;
import org.fourthline.cling.binding.xml.DeviceDescriptorBinder;
import org.fourthline.cling.binding.xml.ServiceDescriptorBinder;
import org.fourthline.cling.binding.xml.UDA10DeviceDescriptorBinderImpl;
import org.fourthline.cling.binding.xml.UDA10ServiceDescriptorBinderSAXImpl;
import org.fourthline.cling.model.message.StreamRequestMessage;
import org.fourthline.cling.model.message.StreamResponseMessage;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.message.header.ContentTypeHeader;
import org.fourthline.cling.model.message.header.ServerHeader;
import org.fourthline.cling.model.message.header.UpnpHeader;
import org.fourthline.cling.model.meta.Icon;
import org.fourthline.cling.model.meta.LocalDevice;
import org.fourthline.cling.model.meta.LocalService;
import org.fourthline.cling.model.resource.DeviceDescriptorResource;
import org.fourthline.cling.model.resource.IconResource;
import org.fourthline.cling.model.resource.Resource;
import org.fourthline.cling.model.resource.ServiceDescriptorResource;

import java.io.IOException;
import java.net.URI;

import de.yaacc.upnp.protocol.ReceivingSync;
import de.yaacc.upnp.protocol.UpnpProtocolHandler;
import de.yaacc.upnp.registry.Registry;
import de.yaacc.util.Exceptions;
import de.yaacc.util.YaaccLogger;

/**
 * Handles reception of device/service descriptor and icon retrieval messages.
 *
 * <p>
 * Requested device and service XML descriptors are generated on-the-fly for every request.
 * </p>
 * <p>
 * Descriptor XML is dynamically generated depending on the control point - some control
 * points require different metadata than others for the same device and services.
 * </p>
 *
 * @author Christian Bauer
 */
public class ReceivingRetrieval extends ReceivingSync<StreamRequestMessage, StreamResponseMessage> {

    private Registry registry;

    public ReceivingRetrieval(Registry registry, StreamRequestMessage inputMessage) {
        super(inputMessage);
        this.registry = registry;
    }

    protected StreamResponseMessage executeSync() throws IOException {

        if (!getInputMessage().hasHostHeader()) {
            YaaccLogger.v(getClass().getName(), "Ignoring message, missing HOST header: " + getInputMessage());
            return new StreamResponseMessage(new UpnpResponse(UpnpResponse.Status.PRECONDITION_FAILED));
        }

        URI requestedURI = getInputMessage().getOperation().getURI();

        Resource foundResource = registry.getResource(requestedURI);
        if (foundResource == null) {
            foundResource = onResourceNotFound(requestedURI);
            if (foundResource == null) {
                YaaccLogger.v(getClass().getName(), "No local resource found: " + getInputMessage());
                return null;
            }
        }

        // HEAD requests don't need content, just return headers
        if (getInputMessage().getOperation().getMethod().equals(org.fourthline.cling.model.message.UpnpRequest.Method.HEAD)) {
            StreamResponseMessage response = new StreamResponseMessage(UpnpResponse.Status.OK);
            response.getHeaders().add(UpnpHeader.Type.SERVER, new ServerHeader());
            return response;
        }

        return createResponse(requestedURI, foundResource);
    }

    protected StreamResponseMessage createResponse(URI requestedURI, Resource resource) {

        StreamResponseMessage response;

        try {

            if (DeviceDescriptorResource.class.isAssignableFrom(resource.getClass())) {

                YaaccLogger.v(getClass().getName(), "Found local device matching relative request URI: " + requestedURI);
                LocalDevice device = (LocalDevice) resource.getModel();

                DeviceDescriptorBinder deviceDescriptorBinder = new UDA10DeviceDescriptorBinderImpl();

                String deviceDescriptor = deviceDescriptorBinder.generate(
                        device,
                        getRemoteClientInfo(),
                        UpnpProtocolHandler.NAMESPACE
                );
                response = new StreamResponseMessage(
                        deviceDescriptor,
                        new ContentTypeHeader(ContentTypeHeader.DEFAULT_CONTENT_TYPE)
                );
            } else if (ServiceDescriptorResource.class.isAssignableFrom(resource.getClass())) {


                YaaccLogger.v(getClass().getName(), "Found local service matching relative request URI: " + requestedURI);
                LocalService service = (LocalService) resource.getModel();

                ServiceDescriptorBinder serviceDescriptorBinder = new UDA10ServiceDescriptorBinderSAXImpl();
                String serviceDescriptor = serviceDescriptorBinder.generate(service);
                response = new StreamResponseMessage(
                        serviceDescriptor,
                        new ContentTypeHeader(ContentTypeHeader.DEFAULT_CONTENT_TYPE)
                );

            } else if (IconResource.class.isAssignableFrom(resource.getClass())) {

                YaaccLogger.v(getClass().getName(), "Found local icon matching relative request URI: " + requestedURI);
                Icon icon = (Icon) resource.getModel();
                response = new StreamResponseMessage(icon.getData(), icon.getMimeType());

            } else {

                YaaccLogger.v(getClass().getName(), "Ignoring GET for found local resource: " + resource);
                return null;
            }

        } catch (DescriptorBindingException ex) {
            YaaccLogger.w(getClass().getName(), "Error generating requested device/service descriptor: " + ex.toString());
            YaaccLogger.w(getClass().getName(), "Exception root cause: ", Exceptions.unwrap(ex));
            response = new StreamResponseMessage(UpnpResponse.Status.INTERNAL_SERVER_ERROR);
        }

        response.getHeaders().add(UpnpHeader.Type.SERVER, new ServerHeader());

        return response;
    }

    /**
     * Called if the {@link org.fourthline.cling.registry.Registry} had no result.
     *
     * @param requestedURIPath The requested URI path
     * @return <code>null</code> or your own {@link Resource}
     */
    protected Resource onResourceNotFound(URI requestedURIPath) {
        return null;
    }
}
