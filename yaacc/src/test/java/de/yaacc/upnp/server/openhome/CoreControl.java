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
package de.yaacc.upnp.server.openhome;

import org.fourthline.cling.model.action.ActionArgumentValue;
import org.fourthline.cling.model.action.ActionExecutor;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.meta.Action;
import org.fourthline.cling.model.meta.ActionArgument;
import org.fourthline.cling.model.meta.LocalService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.yaacc.openhome.OhControl;
import de.yaacc.openhome.OhControlException;

/**
 * An {@link OhControl} that talks to the services of an {@link OhRendererCore} in the same process: the actions run through the
 * real Cling services (argument conversion, handlers, error codes), only the network is left out. Test helper.
 */
public final class CoreControl implements OhControl {
    private final Map<String, LocalService<?>> services = new HashMap<>();
    public int calls;
    /** When set, every call fails like an unreachable device. */
    public boolean unreachable;

    public CoreControl(OhRendererCore core) throws Exception {
        List<LocalService<?>> built = core.buildServices(1000);
        for (LocalService<?> service : built) {
            services.put(service.getServiceId().getId(), service);
        }
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Map<String, String> invoke(String serviceName, String actionName, Map<String, String> arguments) throws OhControlException {
        calls++;
        if (unreachable) {
            throw new OhControlException("no answer", new java.io.IOException("unreachable"));
        }
        LocalService service = services.get(serviceName);
        if (service == null) {
            throw new OhControlException(401, "no such service");
        }
        Action action = service.getAction(actionName);
        if (action == null) {
            throw new OhControlException(401, "no such action");
        }
        ActionInvocation invocation = new ActionInvocation(action);
        for (Map.Entry<String, String> argument : arguments.entrySet()) {
            ActionArgument<?> definition = action.getInputArgument(argument.getKey());
            invocation.setInput(argument.getKey(), definition.getDatatype().valueOf(argument.getValue()));
        }
        ActionExecutor executor = service.getExecutor(action);
        executor.execute(invocation);
        if (invocation.getFailure() != null) {
            throw new OhControlException(invocation.getFailure().getErrorCode(), invocation.getFailure().getMessage());
        }
        Map<String, String> out = new HashMap<>();
        for (ActionArgumentValue<?> value : invocation.getOutput()) {
            out.put(value.getArgument().getName(), value.getDatatype().getString(value.getValue()));
        }
        return out;
    }
}
