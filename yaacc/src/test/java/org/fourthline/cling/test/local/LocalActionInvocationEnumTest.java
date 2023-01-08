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

package org.fourthline.cling.test.local;

import static org.junit.Assert.assertEquals;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;
import com.tngtech.java.junit.dataprovider.UseDataProvider;

import org.fourthline.cling.binding.LocalServiceBinder;
import org.fourthline.cling.binding.annotations.AnnotationLocalServiceBinder;
import org.fourthline.cling.binding.annotations.UpnpAction;
import org.fourthline.cling.binding.annotations.UpnpInputArgument;
import org.fourthline.cling.binding.annotations.UpnpOutputArgument;
import org.fourthline.cling.binding.annotations.UpnpService;
import org.fourthline.cling.binding.annotations.UpnpServiceId;
import org.fourthline.cling.binding.annotations.UpnpServiceType;
import org.fourthline.cling.binding.annotations.UpnpStateVariable;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.meta.DeviceDetails;
import org.fourthline.cling.model.meta.LocalDevice;
import org.fourthline.cling.model.meta.LocalService;
import org.fourthline.cling.model.types.UDADeviceType;
import org.fourthline.cling.test.data.SampleData;
import org.junit.Test;
import org.junit.runner.RunWith;


@RunWith(DataProviderRunner.class)
public class LocalActionInvocationEnumTest {

    public static LocalDevice createTestDevice(LocalService service) throws Exception {
        return new LocalDevice(
                SampleData.createLocalDeviceIdentity(),
                new UDADeviceType("BinaryLight", 1),
                new DeviceDetails("Example Binary Light"),
                service
        );
    }

    @DataProvider
    public static Object[][] devices() throws Exception {
        LocalServiceBinder binder = new AnnotationLocalServiceBinder();
        return new LocalDevice[][]{
                {createTestDevice(SampleData.readService(binder, TestServiceOne.class))},
                {createTestDevice(SampleData.readService(binder, TestServiceTwo.class))},
                {createTestDevice(SampleData.readService(binder, TestServiceThree.class))},
        };
    }

    @Test
    @UseDataProvider("devices")
    public void invokeActions(LocalDevice device) throws Exception {

        LocalService svc = SampleData.getFirstService(device);

        ActionInvocation checkTargetInvocation = new ActionInvocation(svc.getAction("GetTarget"));
        svc.getExecutor(checkTargetInvocation.getAction()).execute(checkTargetInvocation);
        assertEquals(checkTargetInvocation.getFailure(), null);
        assertEquals(checkTargetInvocation.getOutput().length, 1);
        assertEquals(checkTargetInvocation.getOutput()[0].toString(), "UNKNOWN");

        ActionInvocation setTargetInvocation = new ActionInvocation(svc.getAction("SetTarget"));
        setTargetInvocation.setInput("NewTargetValue", "ON");
        svc.getExecutor(setTargetInvocation.getAction()).execute(setTargetInvocation);
        assertEquals(setTargetInvocation.getFailure(), null);
        assertEquals(setTargetInvocation.getOutput().length, 0);

        ActionInvocation getTargetInvocation = new ActionInvocation(svc.getAction("GetTarget"));
        svc.getExecutor(getTargetInvocation.getAction()).execute(getTargetInvocation);
        assertEquals(getTargetInvocation.getFailure(), null);
        assertEquals(getTargetInvocation.getOutput().length, 1);
        assertEquals(getTargetInvocation.getOutput()[0].toString(), "ON");

        ActionInvocation getStatusInvocation = new ActionInvocation(svc.getAction("GetStatus"));
        svc.getExecutor(getStatusInvocation.getAction()).execute(getStatusInvocation);
        assertEquals(getStatusInvocation.getFailure(), null);
        assertEquals(getStatusInvocation.getOutput().length, 1);
        assertEquals(getStatusInvocation.getOutput()[0].toString(), "1");

    }

    /* ####################################################################################################### */

    @UpnpService(
            serviceId = @UpnpServiceId("SwitchPower"),
            serviceType = @UpnpServiceType(value = "SwitchPower", version = 1)
    )
    public static class TestServiceOne {

        @UpnpStateVariable(sendEvents = false)
        private Target target = Target.UNKNOWN;
        @UpnpStateVariable
        private boolean status = false;

        @UpnpAction(out = @UpnpOutputArgument(name = "RetTargetValue"))
        public Target getTarget() {
            return target;
        }

        @UpnpAction
        public void setTarget(@UpnpInputArgument(name = "NewTargetValue") String newTargetValue) {
            target = Target.valueOf(newTargetValue);

            status = target == Target.ON;
        }

        @UpnpAction(out = @UpnpOutputArgument(name = "ResultStatus"))
        public boolean getStatus() {
            return status;
        }

        public enum Target {
            ON,
            OFF,
            UNKNOWN
        }
    }


    /* ####################################################################################################### */

    @UpnpService(
            serviceId = @UpnpServiceId("SwitchPower"),
            serviceType = @UpnpServiceType(value = "SwitchPower", version = 1)
    )
    public static class TestServiceTwo {

        @UpnpStateVariable(sendEvents = false)
        private Target target = Target.UNKNOWN;
        @UpnpStateVariable
        private boolean status = false;

        @UpnpAction
        public void setTarget(@UpnpInputArgument(name = "NewTargetValue") String newTargetValue) {
            target = Target.valueOf(newTargetValue);

            status = target == Target.ON;
        }

        @UpnpAction(out = @UpnpOutputArgument(name = "RetTargetValue", stateVariable = "Target", getterName = "getRealTarget"))
        public void getTarget() {
        }

        public Target getRealTarget() {
            return target;
        }

        @UpnpAction(out = @UpnpOutputArgument(name = "ResultStatus"))
        public boolean getStatus() {
            return status;
        }

        public enum Target {
            ON,
            OFF,
            UNKNOWN
        }
    }

    /* ####################################################################################################### */

    @UpnpService(
            serviceId = @UpnpServiceId("SwitchPower"),
            serviceType = @UpnpServiceType(value = "SwitchPower", version = 1)
    )
    public static class TestServiceThree {

        @UpnpStateVariable(sendEvents = false)
        private Target target = Target.UNKNOWN;
        @UpnpStateVariable
        private boolean status = false;

        @UpnpAction
        public void setTarget(@UpnpInputArgument(name = "NewTargetValue") String newTargetValue) {
            target = Target.valueOf(newTargetValue);

            status = target == Target.ON;
        }

        @UpnpAction(name = "GetTarget", out = @UpnpOutputArgument(name = "RetTargetValue", getterName = "getTarget"))
        public TargetHolder getTargetHolder() {
            return new TargetHolder(target);
        }

        @UpnpAction(out = @UpnpOutputArgument(name = "ResultStatus"))
        public boolean getStatus() {
            return status;
        }

        public enum Target {
            ON,
            OFF,
            UNKNOWN
        }

        public class TargetHolder {
            private Target t;

            public TargetHolder(Target t) {
                this.t = t;
            }

            public Target getTarget() {
                return t;
            }
        }
    }

    /* ####################################################################################################### */

    @UpnpService(
            serviceId = @UpnpServiceId("SwitchPower"),
            serviceType = @UpnpServiceType(value = "SwitchPower", version = 1)
    )
    public static class TestServiceFour {

        @UpnpStateVariable(sendEvents = false)
        private Target target = Target.UNKNOWN;
        @UpnpStateVariable
        private boolean status = false;

        @UpnpAction
        public void setTarget(@UpnpInputArgument(name = "NewTargetValue") String newTargetValue) {
            target = Target.valueOf(newTargetValue);

            status = target == Target.ON;
        }

        @UpnpAction(name = "GetTarget", out = @UpnpOutputArgument(name = "RetTargetValue", stateVariable = "Target", getterName = "getT"))
        public TargetHolder getTargetHolder() {
            return new TargetHolder(target);
        }

        @UpnpAction(out = @UpnpOutputArgument(name = "ResultStatus"))
        public boolean getStatus() {
            return status;
        }

        public enum Target {
            ON,
            OFF,
            UNKNOWN
        }

        public class TargetHolder {
            private Target t;

            public TargetHolder(Target t) {
                this.t = t;
            }

            public Target getT() {
                return t;
            }
        }
    }

}