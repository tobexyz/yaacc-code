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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The description of one OpenHome service as data: its state variables and its actions with their arguments. The tables are
 * written in a compact text form (the one of the OpenHome service descriptions, shortened) and a test compares them with the
 * service descriptions of the OpenHome project, so a mistake in a name or a type shows at once.
 * <ul>
 *   <li>variable: {@code Name:type}, then {@code *} if it is evented, then {@code [a|b|c]} for the allowed values</li>
 *   <li>action: {@code Name}, then arguments {@code >Arg=RelatedVariable} (in) or {@code <Arg=RelatedVariable} (out), in order</li>
 * </ul>
 */
public final class OhServiceDef {
    public static final String NAMESPACE = "av-openhome-org";

    /** A state variable. */
    public static final class Var {
        public final String name;
        public final String type;
        public final boolean evented;
        public final List<String> allowed;

        Var(String name, String type, boolean evented, List<String> allowed) {
            this.name = name;
            this.type = type;
            this.evented = evented;
            this.allowed = Collections.unmodifiableList(allowed);
        }
    }

    /** An argument of an action. */
    public static final class Arg {
        public final String name;
        public final String related;
        public final boolean in;

        Arg(String name, String related, boolean in) {
            this.name = name;
            this.related = related;
            this.in = in;
        }
    }

    /** An action. */
    public static final class Act {
        public final String name;
        public final List<Arg> args;

        Act(String name, List<Arg> args) {
            this.name = name;
            this.args = Collections.unmodifiableList(args);
        }

        public List<Arg> inputs() {
            List<Arg> result = new ArrayList<>();
            for (Arg arg : args) {
                if (arg.in) {
                    result.add(arg);
                }
            }
            return result;
        }

        public List<Arg> outputs() {
            List<Arg> result = new ArrayList<>();
            for (Arg arg : args) {
                if (!arg.in) {
                    result.add(arg);
                }
            }
            return result;
        }
    }

    private final String name;
    private final int version;
    private final List<Var> vars = new ArrayList<>();
    private final List<Act> actions = new ArrayList<>();

    public OhServiceDef(String name, int version) {
        this.name = name;
        this.version = version;
    }

    public String name() {
        return name;
    }

    public int version() {
        return version;
    }

    /** {@code urn:av-openhome-org:service:Playlist:1} */
    public String serviceType() {
        return "urn:" + NAMESPACE + ":service:" + name + ":" + version;
    }

    /** {@code urn:av-openhome-org:serviceId:Playlist} */
    public String serviceId() {
        return "urn:" + NAMESPACE + ":serviceId:" + name;
    }

    public List<Var> vars() {
        return Collections.unmodifiableList(vars);
    }

    public List<Act> actions() {
        return Collections.unmodifiableList(actions);
    }

    public Var var(String variable) {
        for (Var v : vars) {
            if (v.name.equals(variable)) {
                return v;
            }
        }
        return null;
    }

    public Act action(String action) {
        for (Act a : actions) {
            if (a.name.equals(action)) {
                return a;
            }
        }
        return null;
    }

    public OhServiceDef vars(String... definitions) {
        for (String text : definitions) {
            String rest = text;
            List<String> allowed = new ArrayList<>();
            int bracket = rest.indexOf('[');
            if (bracket >= 0) {
                String list = rest.substring(bracket + 1, rest.indexOf(']'));
                allowed.addAll(java.util.Arrays.asList(list.split("\\|")));
                rest = rest.substring(0, bracket);
            }
            boolean evented = rest.endsWith("*");
            if (evented) {
                rest = rest.substring(0, rest.length() - 1);
            }
            String[] parts = rest.split(":");
            vars.add(new Var(parts[0], parts[1], evented, allowed));
        }
        return this;
    }

    public OhServiceDef actions(String... definitions) {
        for (String text : definitions) {
            String[] parts = text.trim().split(" +");
            List<Arg> args = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) {
                String[] pair = parts[i].substring(1).split("=");
                args.add(new Arg(pair[0], pair[1], parts[i].charAt(0) == '>'));
            }
            actions.add(new Act(parts[0], args));
        }
        return this;
    }
}
