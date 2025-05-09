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

package org.fourthline.cling.model.action;

import android.util.Log;

import org.fourthline.cling.model.Command;
import org.fourthline.cling.model.ServiceManager;
import org.fourthline.cling.model.meta.Action;
import org.fourthline.cling.model.meta.ActionArgument;
import org.fourthline.cling.model.meta.LocalService;
import org.fourthline.cling.model.state.StateVariableAccessor;
import org.fourthline.cling.model.types.ErrorCode;
import org.fourthline.cling.model.types.InvalidValueException;
import org.seamless.util.Exceptions;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared procedures for action executors based on an actual service implementation instance.
 *
 * @author Christian Bauer
 */
public abstract class AbstractActionExecutor implements ActionExecutor {

    protected Map<ActionArgument<LocalService>, StateVariableAccessor> outputArgumentAccessors =
            new HashMap<>();

    protected AbstractActionExecutor() {
    }

    protected AbstractActionExecutor(Map<ActionArgument<LocalService>, StateVariableAccessor> outputArgumentAccessors) {
        this.outputArgumentAccessors = outputArgumentAccessors;
    }

    public Map<ActionArgument<LocalService>, StateVariableAccessor> getOutputArgumentAccessors() {
        return outputArgumentAccessors;
    }

    /**
     * Obtains the service implementation instance from the {@link org.fourthline.cling.model.ServiceManager}, handles exceptions.
     */
    public void execute(final ActionInvocation<LocalService> actionInvocation) {

        Log.v(getClass().getName(), "Invoking on local service: " + actionInvocation);

        final LocalService service = actionInvocation.getAction().getService();

        try {

            if (service.getManager() == null) {
                throw new IllegalStateException("Service has no implementation factory, can't get service instance");
            }

            service.getManager().execute(new Command() {
                public void execute(ServiceManager serviceManager) throws Exception {
                    AbstractActionExecutor.this.execute(
                            actionInvocation,
                            serviceManager.getImplementation()
                    );
                }

                @Override
                public String toString() {
                    return "Action invocation: " + actionInvocation.getAction();
                }
            });

        } catch (ActionException ex) {

            Log.v(getClass().getName(), "ActionException thrown by service, wrapping in invocation and returning: " + ex);
            Log.v(getClass().getName(), "Exception root cause: ", Exceptions.unwrap(ex));

            actionInvocation.setFailure(ex);
        } catch (InterruptedException ex) {
            Log.v(getClass().getName(), "InterruptedException thrown by service, wrapping in invocation and returning: " + ex);
            Log.v(getClass().getName(), "Exception root cause: ", Exceptions.unwrap(ex));

            actionInvocation.setFailure(new ActionCancelledException(ex));
        } catch (Throwable t) {
            Throwable rootCause = Exceptions.unwrap(t);
            Log.v(getClass().getName(), "Execution has thrown, wrapping root cause in ActionException and returning: " + t);
            Log.v(getClass().getName(), "Exception root cause: ", rootCause);

            actionInvocation.setFailure(
                    new ActionException(
                            ErrorCode.ACTION_FAILED,
                            (rootCause.getMessage() != null ? rootCause.getMessage() : rootCause.toString()),
                            rootCause
                    )
            );
        }
    }

    protected abstract void execute(ActionInvocation<LocalService> actionInvocation, Object serviceImpl) throws Exception;

    /**
     * Reads the output arguments after an action execution using accessors.
     *
     * @param action   The action of which the output arguments are read.
     * @param instance The instance on which the accessors will be invoked.
     * @return <code>null</code> if the action has no output arguments, a single instance if it has one, an
     * <code>Object[]</code> otherwise.
     * @throws Exception
     */
    protected Object readOutputArgumentValues(Action<LocalService> action, Object instance) throws Exception {
        Object[] results = new Object[action.getOutputArguments().length];
        Log.v(getClass().getName(), "Attempting to retrieve output argument values using accessor: " + results.length);

        int i = 0;
        for (ActionArgument outputArgument : action.getOutputArguments()) {
            Log.v(getClass().getName(), "Calling acccessor method for: " + outputArgument);

            StateVariableAccessor accessor = getOutputArgumentAccessors().get(outputArgument);
            if (accessor != null) {
                Log.v(getClass().getName(), "Calling accessor to read output argument value: " + accessor);
                results[i++] = accessor.read(instance);
            } else {
                throw new IllegalStateException("No accessor bound for: " + outputArgument);
            }
        }

        if (results.length == 1) {
            return results[0];
        }
        return results.length > 0 ? results : null;
    }

    /**
     * Sets the output argument value on the {@link org.fourthline.cling.model.action.ActionInvocation}, considers string conversion.
     */
    protected void setOutputArgumentValue(ActionInvocation<LocalService> actionInvocation, ActionArgument<LocalService> argument, Object result)
            throws ActionException {

        LocalService service = actionInvocation.getAction().getService();

        if (result != null) {
            try {
                if (service.isStringConvertibleType(result)) {
                    Log.v(getClass().getName(), "Result of invocation matches convertible type, setting toString() single output argument value");
                    actionInvocation.setOutput(new ActionArgumentValue(argument, result.toString()));
                } else {
                    Log.v(getClass().getName(), "Result of invocation is Object, setting single output argument value");
                    actionInvocation.setOutput(new ActionArgumentValue(argument, result));
                }
            } catch (InvalidValueException ex) {
                throw new ActionException(
                        ErrorCode.ARGUMENT_VALUE_INVALID,
                        "Wrong type or invalid value for '" + argument.getName() + "': " + ex.getMessage(),
                        ex
                );
            }
        } else {

            Log.v(getClass().getName(), "Result of invocation is null, not setting any output argument value(s)");
        }

    }

}
