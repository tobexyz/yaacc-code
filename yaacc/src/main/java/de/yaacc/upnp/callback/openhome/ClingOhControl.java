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
package de.yaacc.upnp.callback.openhome;

import org.fourthline.cling.model.action.ActionArgumentValue;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Action;
import org.fourthline.cling.model.meta.ActionArgument;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.Service;

import java.util.HashMap;
import java.util.Map;

import de.yaacc.openhome.OhControl;
import de.yaacc.openhome.OhControlException;
import de.yaacc.upnp.callback.ActionCallback;
import de.yaacc.upnp.server.http.HttpRequestSender;
import de.yaacc.util.YaaccLogger;

/**
 * Calls the actions of the OpenHome services of a device through Cling and waits for the answer. Blocks the calling thread, so it
 * is used from the player's own worker thread, never from the main thread.
 */
public final class ClingOhControl implements OhControl {
    public static final String NAMESPACE = "av-openhome-org";

    private final Device<?, ?, ?> device;
    private final HttpRequestSender sender;

    public ClingOhControl(Device<?, ?, ?> device, HttpRequestSender sender) {
        this.device = device;
        this.sender = sender;
    }

    /**
     * @return the service of that name (the highest version the device offers), null if it has none. A renderer inside another
     * device (a phone that serves files and plays) offers the OpenHome services on its root device: they are looked for there too.
     */
    public static Service<?, ?> findService(Device<?, ?, ?> device, String name) {
        if (device == null) {
            return null;
        }
        Service<?, ?> best = best(device.getServices(), name, null);
        // Cling answers null (not an empty array) for a device without services, like the local device or one that is still loading
        Device<?, ?, ?> root = device.getRoot();
        if (root != null && root != device) {
            best = best(root.getServices(), name, best);
        }
        return best;
    }

    private static Service<?, ?> best(Service<?, ?>[] services, String name, Service<?, ?> best) {
        if (services == null) {
            return best;
        }
        for (Service<?, ?> service : services) {
            if (service == null || service.getServiceType() == null) {
                continue;
            }
            if (NAMESPACE.equals(service.getServiceType().getNamespace()) && name.equals(service.getServiceType().getType())
                    && (best == null || service.getServiceType().getVersion() > best.getServiceType().getVersion())) {
                best = service;
            }
        }
        return best;
    }

    /** Whether the device offers what the controller needs: the playlist and the product (source and standby). */
    public static boolean offersPlaylist(Device<?, ?, ?> device) {
        return findService(device, "Playlist") != null && findService(device, "Product") != null;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Map<String, String> invoke(String serviceName, String actionName, Map<String, String> arguments) throws OhControlException {
        Service service = findService(device, serviceName);
        if (service == null) {
            throw new OhControlException(401, "the device has no " + serviceName + " service");
        }
        Action action = service.getAction(actionName);
        if (action == null) {
            throw new OhControlException(401, "the " + serviceName + " service has no action " + actionName);
        }
        ActionInvocation invocation = new ActionInvocation(action);
        for (Map.Entry<String, String> argument : arguments.entrySet()) {
            ActionArgument<?> definition = action.getInputArgument(argument.getKey());
            if (definition == null) {
                throw new OhControlException(402, "unknown argument " + argument.getKey());
            }
            try {
                invocation.setInput(argument.getKey(), definition.getDatatype().valueOf(argument.getValue()));
            } catch (RuntimeException e) {
                throw new OhControlException(402, "bad value for " + argument.getKey());
            }
        }
        final String[] failure = new String[1];
        final int[] code = {OhControlException.NO_ANSWER};
        new ActionCallback(invocation, sender) {
            @Override
            public void success(ActionInvocation invocation) {
                // read below
            }

            @Override
            public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
                failure[0] = defaultMsg == null ? "action failed" : defaultMsg;
                if (invocation.getFailure() != null) {
                    code[0] = invocation.getFailure().getErrorCode();
                    failure[0] = invocation.getFailure().getMessage();
                }
            }
        }.run();
        if (failure[0] != null || invocation.getFailure() != null) {
            YaaccLogger.d(getClass().getName(), "OpenHome action " + serviceName + "." + actionName + " failed: " + clean(failure[0]));
            throw new OhControlException(code[0], failure[0] == null ? "action failed" : clean(failure[0]));
        }
        Map<String, String> out = new HashMap<>();
        for (ActionArgumentValue<?> value : invocation.getOutput()) {
            out.put(value.getArgument().getName(), value.getDatatype().getString(value.getValue()));
        }
        return out;
    }

    /** The text of a fault comes from the device: shortened, without control characters, before it is logged. */
    private static String clean(String text) {
        if (text == null) {
            return "";
        }
        String shortened = text.length() > 200 ? text.substring(0, 200) : text;
        return shortened.replaceAll("\\p{Cntrl}", " ");
    }
}
