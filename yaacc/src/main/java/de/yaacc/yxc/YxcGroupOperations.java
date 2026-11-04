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

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.yaacc.util.YaaccLogger;

/**
 * Link, change, unlink and leave on behalf of the sheet. It sits between the picker and {@link YxcGroupService} and
 * adds what the service does not know about the app:
 * <ul>
 * <li>it works from the picker's selection: whoever is checked and not in the group is added, whoever is in the
 * group and not checked is removed (the group ends when nobody is left);</li>
 * <li>it never takes a device out of another group: a picked device that is a server, or a client of another group,
 * is refused (decision of the owner; the devices accept such calls without complaint, see docs/tech.md);</li>
 * <li>a client that leaves is put back on the input it had before: it keeps the client role for as long as it sits on
 * the link input. The input is the last one that was not the link input (remembered from the states, so also for
 * groups made with another app), else the first non-link input the device offers;</li>
 * <li>it tells the screen which devices are busy and asks for a fresh read of every involved device afterwards.</li>
 * </ul>
 * One operation at a time. All methods block; the caller runs them on a worker thread.
 */
public final class YxcGroupOperations {
    static final int WAIT_MILLIS = 4000;
    static final int POLL_MILLIS = 250;
    private static final int MAX_REMEMBERED = 64;
    private static final java.util.regex.Pattern INPUT_ID = java.util.regex.Pattern.compile("[A-Za-z0-9_]{1,32}");

    /** A device that can take part, found by our own discovery. */
    public static final class Member {
        private final String udn;
        private final YxcGroupMember member;

        public Member(String udn, YxcGroupMember member) {
            this.udn = udn;
            this.member = member;
        }
    }

    /** What the operations need from the app around them. */
    public interface Environment {
        /** @return the watched devices that speak the protocol, as group members */
        List<Member> members();

        /** @return the last input of the device that was not the link input, {@code null} if never seen */
        String lastPlainInput(String udn);

        /** The device was involved in an operation: read it completely again. */
        void refresh(String udn);

        /** The set of devices an operation is busy with changed (empty when it ended). */
        void busyChanged(Set<String> busyUdns);
    }

    private final Environment environment;
    private final YxcGroupService service;
    // inputs read just before a join; used when the monitor never saw one
    private final Map<String, String> inputsBeforeJoin = new LinkedHashMap<>();

    public YxcGroupOperations(Environment environment, YxcSleeper sleeper) {
        this(environment, new YxcGroupService(sleeper, new SecureRandom(), WAIT_MILLIS, POLL_MILLIS));
    }

    YxcGroupOperations(Environment environment, YxcGroupService service) {
        this.environment = environment;
        this.service = service;
    }

    // ---- operations

    /**
     * Makes the group of the server consist of exactly the selected clients.
     *
     * @param selectedClientUdns the devices that are to be the clients, empty to end the group
     */
    public synchronized YxcGroupResult applySelection(String serverUdn, Collection<String> selectedClientUdns) {
        Map<String, YxcGroupMember> byUdn = lookup();
        Set<String> involved = new LinkedHashSet<>();
        involved.add(serverUdn);
        involved.addAll(selectedClientUdns);
        return guarded(involved, () -> {
            YxcGroupMember server = byUdn.get(serverUdn);
            if (server == null) {
                return invalid(null, "the device is not known");
            }
            List<YxcGroupMember> selected = new ArrayList<>();
            for (String udn : selectedClientUdns) {
                YxcGroupMember member = byUdn.get(udn);
                if (member == null) {
                    return invalid(null, "a picked device is not known");
                }
                if (member == server) {
                    return invalid(server.getIp(), "the server can not be its own client");
                }
                selected.add(member);
            }
            YxcDistributionInfo serverInfo = read(server);
            if (serverInfo.getRole() == YxcDistributionInfo.Role.CLIENT) {
                if (!serverInfo.isNullGroup()) {
                    return invalid(server.getIp(), "the device is a client of a group; leave the group first");
                }
                YxcGroupResult fixed = restoreInput(server, serverUdn);
                if (!fixed.isSuccess()) {
                    return fixed;
                }
            }
            boolean serves = serverInfo.getRole() == YxcDistributionInfo.Role.SERVER;
            Set<String> currentAddresses = serves ? new LinkedHashSet<>(serverInfo.getClients()) : new LinkedHashSet<String>();

            List<YxcGroupMember> toAdd = new ArrayList<>();
            for (YxcGroupMember member : selected) {
                if (currentAddresses.contains(member.getIp())) {
                    continue;
                }
                YxcDistributionInfo info = read(member);
                boolean inOtherGroup = info.getRole() == YxcDistributionInfo.Role.SERVER
                        || (info.getRole() == YxcDistributionInfo.Role.CLIENT && !info.isNullGroup());
                if (inOtherGroup) {
                    return invalid(member.getIp(), "the device is in another group");
                }
                toAdd.add(member);
            }
            Set<String> selectedAddresses = new HashSet<>();
            for (YxcGroupMember member : selected) {
                selectedAddresses.add(member.getIp());
            }
            List<YxcGroupMember> toRemove = new ArrayList<>();
            Map<YxcGroupMember, String> leavers = new LinkedHashMap<>();
            for (String address : currentAddresses) {
                if (selectedAddresses.contains(address)) {
                    continue;
                }
                for (Map.Entry<String, YxcGroupMember> entry : byUdn.entrySet()) {
                    if (entry.getValue().getIp().equals(address)) {
                        toRemove.add(entry.getValue());
                        leavers.put(entry.getValue(), entry.getKey());
                        involved.add(entry.getKey());
                    }
                }
            }
            if (toAdd.isEmpty() && toRemove.isEmpty()) {
                return YxcGroupResult.success(serves ? serverInfo.getGroupId() : null);
            }
            announceBusy(involved);
            YxcGroupResult restoreFailure = null;
            if (!toRemove.isEmpty()) {
                YxcGroupResult reduced = service.reduce(server, toRemove);
                if (!reduced.isSuccess()) {
                    return reduced;
                }
                // every leaver is put back; a device that can not be switched back is reported at the end,
                // it does not stop the others or the clients that are to be added
                for (Map.Entry<YxcGroupMember, String> leaver : leavers.entrySet()) {
                    YxcGroupResult back = restoreInput(leaver.getKey(), leaver.getValue());
                    if (!back.isSuccess() && restoreFailure == null) {
                        restoreFailure = back;
                    }
                }
            }
            if (toAdd.isEmpty()) {
                return restoreFailure != null ? restoreFailure : YxcGroupResult.success(null);
            }
            for (Map.Entry<String, YxcGroupMember> entry : byUdn.entrySet()) {
                if (toAdd.contains(entry.getValue())) {
                    rememberInputBeforeJoin(entry.getKey(), entry.getValue());
                }
            }
            boolean stillServes = serves && toRemove.size() < currentAddresses.size();
            YxcGroupResult added = stillServes ? service.extend(server, toAdd) : service.link(server, toAdd);
            return added.isSuccess() && restoreFailure != null ? restoreFailure : added;
        });
    }

    /** Ends the group the device serves; the clients are put back on their earlier inputs. */
    public synchronized YxcGroupResult unlinkGroup(String serverUdn) {
        Map<String, YxcGroupMember> byUdn = lookup();
        Set<String> involved = new LinkedHashSet<>();
        involved.add(serverUdn);
        return guarded(involved, () -> {
            YxcGroupMember server = byUdn.get(serverUdn);
            if (server == null) {
                return invalid(null, "the device is not known");
            }
            YxcDistributionInfo info = read(server);
            if (info.getRole() != YxcDistributionInfo.Role.SERVER) {
                return YxcGroupResult.success(null);
            }
            Map<YxcGroupMember, String> clients = new LinkedHashMap<>();
            for (String address : info.getClients()) {
                for (Map.Entry<String, YxcGroupMember> entry : byUdn.entrySet()) {
                    if (entry.getValue().getIp().equals(address)) {
                        clients.put(entry.getValue(), entry.getKey());
                        involved.add(entry.getKey());
                    }
                }
            }
            announceBusy(involved);
            YxcGroupResult result = service.unlink(server, new ArrayList<>(byUdn.values()));
            if (!result.isSuccess()) {
                return result;
            }
            YxcGroupResult restoreFailure = null;
            for (Map.Entry<YxcGroupMember, String> client : clients.entrySet()) {
                YxcGroupResult back = restoreInput(client.getKey(), client.getValue());
                if (!back.isSuccess() && restoreFailure == null) {
                    restoreFailure = back;
                }
            }
            return restoreFailure != null ? restoreFailure : result;
        });
    }

    /** A client leaves its group (also a client that left already but still sits on the link input). */
    public synchronized YxcGroupResult leave(String clientUdn) {
        Map<String, YxcGroupMember> byUdn = lookup();
        Set<String> involved = new LinkedHashSet<>();
        involved.add(clientUdn);
        return guarded(involved, () -> {
            YxcGroupMember client = byUdn.get(clientUdn);
            if (client == null) {
                return invalid(null, "the device is not known");
            }
            YxcDistributionInfo info = read(client);
            if (info.getRole() == YxcDistributionInfo.Role.SERVER) {
                return invalid(client.getIp(), "the device serves a group; unlink the group instead");
            }
            if (info.getRole() == YxcDistributionInfo.Role.CLIENT && !info.isNullGroup()) {
                YxcGroupResult left = service.unlink(client, new ArrayList<>(byUdn.values()));
                if (!left.isSuccess()) {
                    return left;
                }
            }
            return info.getRole() == YxcDistributionInfo.Role.CLIENT ? restoreInput(client, clientUdn) : YxcGroupResult.success(null);
        });
    }

    // ---- helpers

    private interface Body {
        YxcGroupResult run();
    }

    /** Marks the devices busy while the body runs and asks for a fresh read of all of them afterwards, however it ends. */
    private YxcGroupResult guarded(Set<String> involved, Body body) {
        environment.busyChanged(new LinkedHashSet<>(involved));
        try {
            return body.run();
        } catch (ReadFailed e) {
            return YxcGroupResult.failed(YxcGroupResult.Step.READ_STATE, e.ip, e.getMessage());
        } catch (RuntimeException e) {
            YaaccLogger.w(getClass().getName(), "Group operation failed", e);
            return YxcGroupResult.failed(YxcGroupResult.Step.VALIDATE, null, "unexpected failure: " + e.getClass().getSimpleName());
        } finally {
            // the fresh read is asked for before the rows are freed, so they do not flash the old state
            for (String udn : involved) {
                environment.refresh(udn);
            }
            environment.busyChanged(Collections.<String>emptySet());
        }
    }

    /** The devices an operation is busy with grew (clients found by the first reads): tell the screen again. */
    private void announceBusy(Set<String> involved) {
        environment.busyChanged(new LinkedHashSet<>(involved));
    }

    private Map<String, YxcGroupMember> lookup() {
        Map<String, YxcGroupMember> byUdn = new LinkedHashMap<>();
        for (Member member : environment.members()) {
            byUdn.put(member.udn, member.member);
        }
        return byUdn;
    }

    private static YxcGroupResult invalid(String ip, String detail) {
        return YxcGroupResult.failed(YxcGroupResult.Step.VALIDATE, ip, detail);
    }

    private YxcDistributionInfo read(YxcGroupMember member) {
        try {
            return member.getClient().getDistributionInfo();
        } catch (YxcException e) {
            throw new ReadFailed(member.getIp(), e);
        }
    }

    private void rememberInputBeforeJoin(String udn, YxcGroupMember member) {
        try {
            String input = member.getClient().getZoneStatus(member.getZone()).getInput();
            if (input != null && !YxcZoneFeatures.GROUP_INPUT_ID.equalsIgnoreCase(input)) {
                if (inputsBeforeJoin.size() >= MAX_REMEMBERED) {
                    inputsBeforeJoin.remove(inputsBeforeJoin.keySet().iterator().next());
                }
                inputsBeforeJoin.put(udn, input);
            }
        } catch (YxcException e) {
            // the service reads the device again and reports it
        }
    }

    /** Puts the device back on its earlier input; leaves it as it is when no input is known. */
    private YxcGroupResult restoreInput(YxcGroupMember member, String udn) {
        try {
            String current = member.getClient().getZoneStatus(member.getZone()).getInput();
            if (current != null && !YxcZoneFeatures.GROUP_INPUT_ID.equalsIgnoreCase(current)) {
                inputsBeforeJoin.remove(udn);
                return YxcGroupResult.success(null); // not on the link input: nothing to put back
            }
            // the input read just before our own join is the newest; what the monitor saw covers groups made elsewhere
            String target = inputsBeforeJoin.get(udn);
            if (target == null) {
                target = environment.lastPlainInput(udn);
            }
            if (target == null) {
                target = firstPlainInput(member);
            }
            if (target == null || !INPUT_ID.matcher(target).matches()) {
                return YxcGroupResult.success(null); // nothing, or nothing that looks like an input id of a device
            }
            member.getClient().setInput(member.getZone(), target, "");
            inputsBeforeJoin.remove(udn);
            return YxcGroupResult.success(null);
        } catch (YxcException e) {
            return YxcGroupResult.failed(YxcGroupResult.Step.RESTORE_INPUT, member.getIp(), e.getMessage());
        }
    }

    private static String firstPlainInput(YxcGroupMember member) throws YxcException {
        YxcZoneFeatures zone = member.getClient().getFeatures().getZone(member.getZone());
        if (zone == null) {
            return null;
        }
        for (String id : zone.getInputIds()) {
            if (!YxcZoneFeatures.GROUP_INPUT_ID.equalsIgnoreCase(id)) {
                return id;
            }
        }
        return null;
    }

    /** A read that failed; carried out of the lambdas as an unchecked exception and turned into a result. */
    private static final class ReadFailed extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String ip;

        ReadFailed(String ip, YxcException cause) {
            super(cause.getMessage(), cause);
            this.ip = ip;
        }
    }
}
