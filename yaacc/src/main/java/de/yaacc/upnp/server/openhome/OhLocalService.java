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

import org.fourthline.cling.model.DefaultServiceManager;
import org.fourthline.cling.model.ValidationException;
import org.fourthline.cling.model.action.ActionArgumentValue;
import org.fourthline.cling.model.action.ActionException;
import org.fourthline.cling.model.action.ActionExecutor;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.meta.Action;
import org.fourthline.cling.model.meta.ActionArgument;
import org.fourthline.cling.model.meta.LocalService;
import org.fourthline.cling.model.meta.StateVariable;
import org.fourthline.cling.model.meta.StateVariableEventDetails;
import org.fourthline.cling.model.meta.StateVariableTypeDetails;
import org.fourthline.cling.model.state.StateVariableAccessor;
import org.fourthline.cling.model.types.Datatype;
import org.fourthline.cling.model.types.ErrorCode;
import org.fourthline.cling.model.types.ServiceId;
import org.fourthline.cling.model.types.ServiceType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

import de.yaacc.openhome.OhError;
import de.yaacc.util.YaaccLogger;

/**
 * Builds the Cling {@link LocalService} of an OpenHome service from its {@link OhServiceDef}: the state variables read from an
 * {@link OhServiceState}, the actions run by {@link OhActionHandler}s. An action without a handler answers with the current
 * values of the variables of its output arguments (a getter) or, for one input argument, stores it in its variable (a setter).
 */
public final class OhLocalService {
    private OhLocalService() {
    }

    public static LocalService<OhServiceState> build(OhServiceDef def, final OhServiceState state,
                                                     Map<String, OhActionHandler> handlers, final int lockTimeoutMillis)
            throws ValidationException {
        Map<StateVariable, StateVariableAccessor> accessors = new LinkedHashMap<>();
        for (final OhServiceDef.Var v : def.vars()) {
            Datatype.Builtin builtin = Datatype.Builtin.getByDescriptorName(v.type);
            if (builtin == null) {
                throw new IllegalArgumentException("unknown data type " + v.type);
            }
            String[] allowed = v.allowed.isEmpty() ? null : v.allowed.toArray(new String[0]);
            StateVariable variable = new StateVariable(v.name,
                    new StateVariableTypeDetails(builtin.getDatatype(), null, allowed, null),
                    new StateVariableEventDetails(v.evented));
            accessors.put(variable, new StateVariableAccessor() {
                @Override
                public Class<?> getReturnType() {
                    return String.class;
                }

                @Override
                public Object read(Object serviceImpl) {
                    return ((OhServiceState) serviceImpl).get(v.name);
                }
            });
        }
        Map<Action, ActionExecutor> executors = new LinkedHashMap<>();
        for (OhServiceDef.Act a : def.actions()) {
            ActionArgument[] arguments = new ActionArgument[a.args.size()];
            for (int i = 0; i < arguments.length; i++) {
                OhServiceDef.Arg arg = a.args.get(i);
                arguments[i] = new ActionArgument(arg.name, arg.related,
                        arg.in ? ActionArgument.Direction.IN : ActionArgument.Direction.OUT);
            }
            executors.put(new Action(a.name, arguments), new Executor(def, a, state, handlers.get(a.name)));
        }
        LocalService<OhServiceState> service = new LocalService<>(ServiceType.valueOf(def.serviceType()),
                ServiceId.valueOf(def.serviceId()), executors, accessors, new HashSet<Class>(), false);
        service.setManager(new DefaultServiceManager<OhServiceState>(service, null) {
            @Override
            protected int getLockTimeoutMillis() {
                return lockTimeoutMillis;
            }

            @Override
            protected OhServiceState createServiceInstance() {
                return state;
            }
        });
        return service;
    }

    private static final class Executor implements ActionExecutor {
        private final OhServiceDef def;
        private final OhServiceDef.Act action;
        private final OhServiceState state;
        private final OhActionHandler handler;

        Executor(OhServiceDef def, OhServiceDef.Act action, OhServiceState state, OhActionHandler handler) {
            this.def = def;
            this.action = action;
            this.state = state;
            this.handler = handler;
        }

        @Override
        public void execute(ActionInvocation<LocalService> invocation) {
            try {
                Map<String, String> in = new HashMap<>();
                for (ActionArgumentValue<LocalService> value : invocation.getInput()) {
                    in.put(value.getArgument().getName(), value.getDatatype().getString(value.getValue()));
                }
                Map<String, String> out = handler != null ? handler.invoke(in) : standard(in);
                for (OhServiceDef.Arg arg : action.outputs()) {
                    String text = out == null ? null : out.get(arg.name);
                    invocation.setOutput(arg.name, text == null ? state.get(arg.related) : text);
                }
            } catch (OhError e) {
                invocation.setFailure(new ActionException(e.getCode(), e.getMessage()));
            } catch (RuntimeException e) {
                // the text of an exception stays in the log: the control point only learns that the action failed
                YaaccLogger.w(getClass().getName(), "OpenHome action " + def.name() + "." + action.name + " failed", e);
                invocation.setFailure(new ActionException(ErrorCode.ACTION_FAILED.getCode(), ErrorCode.ACTION_FAILED.getDescription()));
            } catch (Exception e) {
                YaaccLogger.w(getClass().getName(), "OpenHome action " + def.name() + "." + action.name + " failed", e);
                invocation.setFailure(new ActionException(ErrorCode.ACTION_FAILED.getCode(), ErrorCode.ACTION_FAILED.getDescription()));
            }
        }

        /** No handler: a getter answers from the state, a setter with one input stores it. */
        private Map<String, String> standard(Map<String, String> in) {
            if (action.outputs().isEmpty() && action.inputs().size() == 1) {
                OhServiceDef.Arg arg = action.inputs().get(0);
                state.set(arg.related, in.get(arg.name));
            }
            return null;
        }
    }
}
