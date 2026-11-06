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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.fourthline.cling.model.meta.Action;
import org.fourthline.cling.model.meta.ActionArgument;
import org.fourthline.cling.model.meta.LocalService;
import org.fourthline.cling.model.meta.StateVariable;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * The services YAACC's renderer offers are the OpenHome services: every state variable and every action of the service
 * descriptions of the OpenHome project (copies in {@code src/test/resources/openhome/spec}) is there with the same name, type,
 * event flag, allowed values and arguments, in the same order, and nothing else. Plain JVM.
 */
public class OhServiceDefsTest {

    private static Document read(String fixture) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        return factory.newDocumentBuilder().parse(new File("src/test/resources/openhome/spec/" + fixture));
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element && ((Element) node).getTagName().equals(name)) {
                result.add((Element) node);
            }
        }
        return result;
    }

    private static String text(Element parent, String name) {
        List<Element> found = children(parent, name);
        return found.isEmpty() ? null : found.get(0).getTextContent().trim();
    }

    private static void assertSameAs(String fixture, OhServiceDef def) throws Exception {
        Element scpd = read(fixture).getDocumentElement();

        List<String> expectedVars = new ArrayList<>();
        for (Element v : children(children(scpd, "serviceStateTable").get(0), "stateVariable")) {
            String allowed = "";
            List<Element> list = children(v, "allowedValueList");
            if (!list.isEmpty()) {
                List<String> values = new ArrayList<>();
                for (Element value : children(list.get(0), "allowedValue")) {
                    values.add(value.getTextContent().trim());
                }
                allowed = "[" + String.join("|", values) + "]";
            }
            expectedVars.add(text(v, "name") + ":" + text(v, "dataType") + ("yes".equals(v.getAttribute("sendEvents")) ? "*" : "") + allowed);
        }
        List<String> actualVars = new ArrayList<>();
        for (OhServiceDef.Var v : def.vars()) {
            actualVars.add(v.name + ":" + v.type + (v.evented ? "*" : "") + (v.allowed.isEmpty() ? "" : "[" + String.join("|", v.allowed) + "]"));
        }
        assertEquals(fixture + " state variables", expectedVars, actualVars);

        List<String> expectedActions = new ArrayList<>();
        for (Element a : children(children(scpd, "actionList").get(0), "action")) {
            StringBuilder line = new StringBuilder(text(a, "name"));
            List<Element> argumentLists = children(a, "argumentList");
            if (!argumentLists.isEmpty()) {
                for (Element arg : children(argumentLists.get(0), "argument")) {
                    line.append(' ').append("in".equals(text(arg, "direction")) ? '>' : '<').append(text(arg, "name")).append('=')
                            .append(text(arg, "relatedStateVariable"));
                }
            }
            expectedActions.add(line.toString());
        }
        List<String> actualActions = new ArrayList<>();
        for (OhServiceDef.Act a : def.actions()) {
            StringBuilder line = new StringBuilder(a.name);
            for (OhServiceDef.Arg arg : a.args) {
                line.append(' ').append(arg.in ? '>' : '<').append(arg.name).append('=').append(arg.related);
            }
            actualActions.add(line.toString());
        }
        assertEquals(fixture + " actions", expectedActions, actualActions);
    }

    @Test
    public void thePlaylistServiceIsThatOfTheOpenHomeDescription() throws Exception {
        assertSameAs("Playlist1.xml", OhServiceDefs.playlist());
    }

    @Test
    public void theInfoServiceIsThatOfTheOpenHomeDescription() throws Exception {
        assertSameAs("Info1.xml", OhServiceDefs.info());
    }

    @Test
    public void theTimeServiceIsThatOfTheOpenHomeDescription() throws Exception {
        assertSameAs("Time1.xml", OhServiceDefs.time());
    }

    @Test
    public void theVolumeServiceIsThatOfTheOpenHomeDescription() throws Exception {
        assertSameAs("Volume2.xml", OhServiceDefs.volume());
    }

    @Test
    public void theProductServiceIsThatOfTheOpenHomeDescription() throws Exception {
        assertSameAs("Product2.xml", OhServiceDefs.product());
    }

    @Test
    public void theTypesAndIdsFollowTheOpenHomeNaming() {
        assertEquals("urn:av-openhome-org:service:Playlist:1", OhServiceDefs.playlist().serviceType());
        assertEquals("urn:av-openhome-org:serviceId:Playlist", OhServiceDefs.playlist().serviceId());
        assertEquals("urn:av-openhome-org:service:Product:2", OhServiceDefs.product().serviceType());
        assertEquals("urn:av-openhome-org:service:Volume:2", OhServiceDefs.volume().serviceType());
    }

    @Test
    public void clingAcceptsTheServicesBuiltFromTheDefinitions() throws Exception {
        for (OhServiceDef def : Arrays.asList(OhServiceDefs.playlist(), OhServiceDefs.info(), OhServiceDefs.time(),
                OhServiceDefs.volume(), OhServiceDefs.product())) {
            LocalService<OhServiceState> service = OhLocalService.build(def, new OhServiceState(),
                    new java.util.HashMap<String, OhActionHandler>(), 1000);

            assertEquals(def.vars().size(), service.getStateVariables().length);
            assertEquals(def.actions().size(), service.getActions().length);
            for (OhServiceDef.Act a : def.actions()) {
                Action action = service.getAction(a.name);
                assertNotNull(a.name, action);
                assertEquals(a.args.size(), action.getArguments().length);
                for (ActionArgument argument : action.getArguments()) {
                    assertNotNull(argument.getRelatedStateVariableName(), service.getStateVariable(argument.getRelatedStateVariableName()));
                }
            }
            for (OhServiceDef.Var v : def.vars()) {
                StateVariable variable = service.getStateVariable(v.name);
                assertEquals(v.evented, variable.getEventDetails().isSendEvents());
            }
            assertTrue(service.getServiceType().toString().startsWith("urn:av-openhome-org:service:"));
        }
    }
}
