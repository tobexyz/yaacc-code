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
package de.yaacc.yxc;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A few devices in memory that implement the group calls the way the notes in docs/tech.md describe them: a server
 * lists clients, a client refuses nothing but a device that serves refuses the client call, a device can be told to be
 * unreachable, to fail a call, to answer late (its state changes only after some virtual milliseconds) or never.
 * Time is virtual: the sleeper handed to the service advances it, nothing really sleeps.
 */
final class SimulatedYxcNetwork {
    /** The word a device in no group reports (verified on three devices). */
    static final String NOT_LINKED_ROLE_WORD = "none";

    private long now;
    private final List<Scheduled> pending = new ArrayList<>();
    private final Map<String, Device> devices = new LinkedHashMap<>();
    /** Every call in order: {@code "<ip> <METHOD> <path>"}. */
    final List<String> calls = new ArrayList<>();
    final YxcSleeper sleeper = millis -> {
        now += millis;
        applyDue();
    };

    private static final class Scheduled {
        final long at;
        final Runnable change;

        Scheduled(long at, Runnable change) {
            this.at = at;
            this.change = change;
        }
    }

    long now() {
        return now;
    }

    Device add(String ip) {
        Device device = new Device(ip);
        devices.put(ip, device);
        return device;
    }

    Device device(String ip) {
        return devices.get(ip);
    }

    YxcGroupMember member(String ip) {
        return new YxcGroupMember(ip, "main", new YxcClient(devices.get(ip)));
    }

    int count(String ip, String callPrefix) {
        int count = 0;
        for (String call : calls) {
            if (call.startsWith(ip + " " + callPrefix)) {
                count++;
            }
        }
        return count;
    }

    /** Calls that change something: everything but the reads. */
    int changingCalls() {
        int count = 0;
        for (String call : calls) {
            if (!call.contains("/get")) {
                count++;
            }
        }
        return count;
    }

    private void applyDue() {
        List<Scheduled> due = new ArrayList<>();
        for (Scheduled scheduled : pending) {
            if (scheduled.at <= now) {
                due.add(scheduled);
            }
        }
        pending.removeAll(due);
        for (Scheduled scheduled : due) {
            scheduled.change.run();
        }
    }

    final class Device implements YxcTransport {
        final String ip;
        String power = "on";
        String input = "optical";
        String groupId = YxcDistributionInfo.NULL_GROUP_ID;
        String groupName = "";
        /** What a client of the API sees; the accepted flags below follow at once, these may lag or never change. */
        boolean server;
        boolean client;
        private boolean acceptedServer;
        private boolean acceptedClient;
        String serverZone = "";
        String serverIp = "";
        final List<String> clients = new ArrayList<>();
        final List<Integer> distributionStarts = new ArrayList<>();
        boolean distributing;
        /** Unreachable: every call fails like a connect timeout. */
        boolean unreachable;
        /** Accepts the calls but never changes state. */
        boolean ignoreChanges;
        /** State changes show only after this many virtual milliseconds. */
        long lateMillis;
        /** Answers the call (path without query) with this response_code instead of running it. */
        final Map<String, Integer> forcedCodes = new HashMap<>();
        /** Fails the call (path without query) like a lost connection. */
        final Set<String> brokenCalls = new HashSet<>();
        /** {@code distribution.client_max} of the features; {@code null} leaves the whole block out. */
        Integer clientMax = 9;
        /** If set, the answer of {@code system/getFeatures} as is. */
        String featuresJson;

        Device(String ip) {
            this.ip = ip;
        }

        /** Makes the device the server of a group (state only, no calls). */
        Device servingGroup(String id, String... clientIps) {
            server = true;
            acceptedServer = true;
            groupId = id;
            serverZone = "main";
            clients.clear();
            for (String clientIp : clientIps) {
                clients.add(clientIp);
            }
            return this;
        }

        /** Makes the device a client of a group (state only, no calls). */
        Device clientOf(String id, String serverAddress) {
            client = true;
            acceptedClient = true;
            groupId = id;
            serverIp = serverAddress;
            input = YxcZoneFeatures.GROUP_INPUT_ID;
            return this;
        }

        String role() {
            return server ? "server" : client ? "client" : NOT_LINKED_ROLE_WORD;
        }

        @Override
        public String get(String path) throws IOException {
            return handle("GET", path, null);
        }

        @Override
        public String post(String path, String jsonBody) throws IOException {
            return handle("POST", path, jsonBody);
        }

        private String handle(String method, String path, String body) throws IOException {
            calls.add(ip + " " + method + " " + path);
            String call = path.indexOf('?') < 0 ? path : path.substring(0, path.indexOf('?'));
            if (unreachable || brokenCalls.contains(call)) {
                throw new SocketTimeoutException("simulated: " + ip + " does not answer");
            }
            applyDue();
            Integer forced = forcedCodes.get(call);
            if (forced != null) {
                return code(forced);
            }
            try {
                switch (call) {
                    case "dist/getDistributionInfo":
                        return distributionInfo();
                    case "system/getFeatures":
                        return features();
                    case "main/getStatus":
                        // a client plays the link input and can not hand it on, like the captured status of a client
                        return "{\"response_code\":0,\"power\":\"" + power + "\",\"input\":\"" + input + "\",\"volume\":10,\"mute\":false,"
                                + "\"distribution_enable\":" + !YxcZoneFeatures.GROUP_INPUT_ID.equals(input) + "}";
                    case "main/setPower":
                        return changeIfAccepted(() -> power = path.endsWith("=on") ? "on" : "standby");
                    case "main/setInput":
                        return setInput(path);
                    case "dist/setServerInfo":
                        return setServerInfo(new JSONObject(body));
                    case "dist/setClientInfo":
                        return setClientInfo(new JSONObject(body));
                    case "dist/startDistribution":
                        if (!acceptedServer) {
                            return code(5);
                        }
                        distributionStarts.add(Integer.parseInt(path.substring(path.indexOf('=') + 1)));
                        distributing = true;
                        return code(0);
                    case "dist/stopDistribution":
                        if (!acceptedServer) {
                            return code(5);
                        }
                        distributing = false;
                        return code(0);
                    case "dist/setGroupName":
                        if (!acceptedServer) {
                            return code(5);
                        }
                        String name = new JSONObject(body).getString("name");
                        return changeIfAccepted(() -> groupName = name);
                    default:
                        return code(3);
                }
            } catch (JSONException e) {
                return code(4);
            }
        }

        private String setInput(String path) {
            String selected = path.substring(path.indexOf("input=") + 6, path.indexOf('&'));
            if (YxcZoneFeatures.GROUP_INPUT_ID.equals(selected) && !acceptedClient) {
                return code(5);
            }
            return changeIfAccepted(() -> input = selected);
        }

        private String setServerInfo(JSONObject body) throws JSONException {
            String id = body.getString("group_id");
            if (id.isEmpty()) {
                acceptedServer = false;
                return changeIfAccepted(() -> {
                    server = false;
                    groupId = YxcDistributionInfo.NULL_GROUP_ID;
                    serverZone = "";
                    clients.clear();
                });
            }
            // the zone is a string here, the client list a list
            String zone = body.getString("zone");
            boolean add = "add".equals(body.getString("type"));
            JSONArray list = body.getJSONArray("client_list");
            if (acceptedClient) {
                return code(5);
            }
            acceptedServer = true;
            List<String> ips = new ArrayList<>();
            for (int i = 0; i < list.length(); i++) {
                ips.add(list.getString(i));
            }
            return changeIfAccepted(() -> {
                server = true;
                groupId = id;
                serverZone = zone;
                for (String clientIp : ips) {
                    if (add && !clients.contains(clientIp)) {
                        clients.add(clientIp);
                    } else if (!add) {
                        clients.remove(clientIp);
                    }
                }
            });
        }

        private String setClientInfo(JSONObject body) throws JSONException {
            String id = body.getString("group_id");
            if (id.isEmpty()) {
                acceptedClient = false;
                return changeIfAccepted(() -> {
                    client = false;
                    groupId = YxcDistributionInfo.NULL_GROUP_ID;
                    serverIp = "";
                });
            }
            // the zone is a list here, the server a string
            body.getJSONArray("zone").getString(0);
            String serverAddress = body.getString("server_ip_address");
            if (acceptedServer) {
                return code(5);
            }
            acceptedClient = true;
            return changeIfAccepted(() -> {
                client = true;
                groupId = id;
                serverIp = serverAddress;
            });
        }

        private String changeIfAccepted(Runnable change) {
            if (!ignoreChanges) {
                if (lateMillis == 0) {
                    change.run();
                } else {
                    pending.add(new Scheduled(now + lateMillis, change));
                }
            }
            return code(0);
        }

        private String features() {
            if (featuresJson != null) {
                return featuresJson;
            }
            String distribution = clientMax == null ? ""
                    : ",\"distribution\":{\"version\":2.0,\"compatible_client\":[2],\"client_max\":" + clientMax
                    + ",\"server_zone_list\":[\"main\"]}";
            return "{\"response_code\":0,\"zone\":[{\"id\":\"main\"}]" + distribution + "}";
        }

        private String distributionInfo() {
            StringBuilder list = new StringBuilder();
            for (String clientIp : clients) {
                list.append(list.length() == 0 ? "" : ",").append("{\"ip_address\":\"").append(clientIp).append("\"}");
            }
            return "{\"response_code\":0,\"group_id\":\"" + groupId + "\",\"group_name\":\"" + groupName
                    + "\",\"role\":\"" + role() + "\",\"server_zone\":\"" + serverZone + "\",\"client_list\":[" + list + "]}";
        }
    }

    private static String code(int code) {
        return "{\"response_code\":" + code + "}";
    }
}
