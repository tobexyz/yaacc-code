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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Links devices into a group ("link": one server plays, its clients play in sync) and takes them out again.
 * <p>
 * The call sequences and the expected-state checks follow the behavior of the open-source libraries (see
 * docs/tech.md): clients first, then the server call that lists them, then the start; after each change the state is
 * read again (a device needs a moment) for up to one second, and a step that did not reach its state is repeated once.
 * The first slice has no event listener, so waiting means polling {@code getDistributionInfo}.
 * <p>
 * Expected failures (a device is unreachable, refuses a call, does not reach the state) are returned as
 * {@link YxcGroupResult}, not thrown. An operation that fails part-way takes back what it did to the clients; it
 * cannot restore a group that a client was taken out of. Operations of one service run one after another.
 */
public final class YxcGroupService {
    static final int DEFAULT_WAIT_MILLIS = 1000;
    static final int DEFAULT_POLL_MILLIS = 100;
    /** The limit used only when the server's features have no {@code distribution.client_max} (the smallest value seen on a device). */
    static final int FALLBACK_CLIENT_MAX = 9;
    private static final int ATTEMPTS = 2;
    private static final int GROUP_ID_BYTES = 16;
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    private static final int MAX_NAME_BYTES = 128;

    private final YxcSleeper sleeper;
    private final Random random;
    private final int waitMillis;
    private final int pollMillis;

    /** @param random where new group ids come from (a {@code SecureRandom} in the app) */
    public YxcGroupService(YxcSleeper sleeper, Random random) {
        this(sleeper, random, DEFAULT_WAIT_MILLIS, DEFAULT_POLL_MILLIS);
    }

    YxcGroupService(YxcSleeper sleeper, Random random, int waitMillis, int pollMillis) {
        if (sleeper == null || random == null || waitMillis < 0 || pollMillis <= 0) {
            throw new IllegalArgumentException("sleeper, random and positive timing are required");
        }
        this.sleeper = sleeper;
        this.random = random;
        this.waitMillis = waitMillis;
        this.pollMillis = pollMillis;
    }

    // ---- public operations

    /**
     * Puts the clients into a group of the server. A server that already serves a group gets them added to it,
     * otherwise a new group id is chosen. A client that is in another group leaves it first; a client that serves a
     * group of its own ends that group first (the device refuses the client call while it serves).
     */
    public synchronized YxcGroupResult link(YxcGroupMember server, List<YxcGroupMember> clients) {
        return run(() -> {
            checkRequest(server, clients);
            YxcDistributionInfo serverInfo = readDistribution(server);
            checkCapacity(server, serverInfo, clients);
            List<YxcDistributionInfo> clientInfos = readDistributions(clients);
            if (serves(serverInfo)) {
                return extendGroup(server, serverInfo, clients, clientInfos);
            }
            if (!serverInfo.isNullGroup()) {
                leaveGroup(server);
            }
            ensurePowerOn(server);
            return joinAndServe(server, newGroupId(), 0, clients, clientInfos);
        });
    }

    /** Adds clients to the group the server already serves; fails if it serves none. */
    public synchronized YxcGroupResult extend(YxcGroupMember server, List<YxcGroupMember> clients) {
        return run(() -> {
            checkRequest(server, clients);
            YxcDistributionInfo serverInfo = readDistribution(server);
            if (!serves(serverInfo)) {
                return YxcGroupResult.failed(YxcGroupResult.Step.VALIDATE, server.getIp(), "the device serves no group; link first");
            }
            checkCapacity(server, serverInfo, clients);
            return extendGroup(server, serverInfo, clients, readDistributions(clients));
        });
    }

    /**
     * Takes clients out of the group of the server. Clients that are not in it are ignored; when nobody would be left
     * the group is ended. A client that cannot be reached is still removed on the server side.
     */
    public synchronized YxcGroupResult reduce(YxcGroupMember server, List<YxcGroupMember> clients) {
        return run(() -> {
            checkRequest(server, clients);
            YxcDistributionInfo serverInfo = readDistribution(server);
            if (!serves(serverInfo)) {
                return YxcGroupResult.success(null);
            }
            return reduceGroup(server, serverInfo, clients);
        });
    }

    /**
     * Takes a device out of its group. A server ends the group (the known members that are its clients leave first);
     * a client leaves, and is removed from its server if that is among the known members. A device in no group is
     * left alone and the result is success.
     *
     * @param knownMembers other devices the caller knows; the clients of a group are only addressed when listed here
     */
    public synchronized YxcGroupResult unlink(YxcGroupMember device, Collection<YxcGroupMember> knownMembers) {
        return run(() -> {
            require(device != null, "device is required");
            List<YxcGroupMember> known = knownMembers == null ? new ArrayList<YxcGroupMember>() : new ArrayList<>(knownMembers);
            YxcDistributionInfo info = readDistribution(device);
            if (info.isNullGroup() && info.getClients().isEmpty()) {
                return YxcGroupResult.success(null);
            }
            if (serves(info)) {
                return closeGroup(device, info, known);
            }
            ServerState server = findServer(device, info, known);
            if (server != null) {
                return reduceGroup(server.member, server.info, Collections.singletonList(device));
            }
            leaveGroup(device);
            return YxcGroupResult.success(null);
        });
    }

    /** Sets the name of the group the server serves; empty selects the default name. */
    public synchronized YxcGroupResult rename(YxcGroupMember server, String name) {
        return run(() -> {
            require(server != null, "server is required");
            require(name != null && name.getBytes(StandardCharsets.UTF_8).length <= MAX_NAME_BYTES,
                    "a group name is at most " + MAX_NAME_BYTES + " bytes");
            YxcDistributionInfo info = readDistribution(server);
            if (!serves(info)) {
                return YxcGroupResult.failed(YxcGroupResult.Step.VALIDATE, server.getIp(), "the device serves no group");
            }
            // the default name is not known, so an empty name cannot be checked
            runWithRetry(YxcGroupResult.Step.RENAME, server.getIp(),
                    () -> server.getClient().setGroupName(name),
                    () -> name.isEmpty() || name.equals(server.getClient().getDistributionInfo().getGroupName()));
            return YxcGroupResult.success(info.getGroupId());
        });
    }

    // ---- the sequences

    private YxcGroupResult extendGroup(YxcGroupMember server, YxcDistributionInfo info, List<YxcGroupMember> clients,
                                       List<YxcDistributionInfo> clientInfos) {
        String reportedZone = info.getServerZone();
        if (reportedZone != null && !reportedZone.isEmpty() && !reportedZone.equalsIgnoreCase(server.getZone())) {
            return YxcGroupResult.failed(YxcGroupResult.Step.VALIDATE, server.getIp(),
                    "the device serves its group with zone " + reportedZone + ", not " + server.getZone());
        }
        return joinAndServe(server, info.getGroupId(), info.getClients().size(), clients, clientInfos);
    }

    /** Clients first, then the server call that lists them. Takes the clients back if something fails. */
    private YxcGroupResult joinAndServe(YxcGroupMember server, String groupId, int clientsBefore,
                                        List<YxcGroupMember> clients, List<YxcDistributionInfo> clientInfos) {
        List<YxcGroupMember> joined = new ArrayList<>();
        try {
            for (int i = 0; i < clients.size(); i++) {
                if (!isMember(groupId, clientInfos.get(i))) {
                    // from here on it may be half-joined, so a failure takes it back too
                    joined.add(clients.get(i));
                }
                joinClient(server, groupId, clients.get(i), clientInfos.get(i));
            }
            List<String> ips = ips(clients);
            runWithRetry(YxcGroupResult.Step.EXTEND_SERVER, server.getIp(),
                    () -> {
                        server.getClient().setServerInfo(groupId, server.getZone(), YxcClientListAction.ADD, ips);
                        server.getClient().startDistribution(clientsBefore);
                    },
                    () -> {
                        YxcDistributionInfo now = server.getClient().getDistributionInfo();
                        return now.getRole() == YxcDistributionInfo.Role.SERVER
                                && sameId(groupId, now.getGroupId())
                                && server.getZone().equalsIgnoreCase(now.getServerZone())
                                && now.getClients().containsAll(ips);
                    });
        } catch (Abort abort) {
            for (YxcGroupMember client : joined) {
                tryLeave(client);
            }
            throw abort;
        }
        return YxcGroupResult.success(groupId);
    }

    private void joinClient(YxcGroupMember server, String groupId, YxcGroupMember client, YxcDistributionInfo info) {
        if (isMember(groupId, info)) {
            // the group info says it is a member (it is the authority); the input says whether it plays the group
            YxcZoneStatus status = call(YxcGroupResult.Step.JOIN_CLIENT, client.getIp(),
                    () -> client.getClient().getZoneStatus(client.getZone()));
            if (!status.isLinkInput()) {
                selectLinkInput(client);
            }
            return;
        }
        if (serves(info)) {
            closeGroup(client, info, Collections.<YxcGroupMember>emptyList());
        } else if (!info.isNullGroup()) {
            leaveGroup(client);
        }
        ensurePowerOn(client);
        runWithRetry(YxcGroupResult.Step.JOIN_CLIENT, client.getIp(),
                () -> {
                    client.getClient().setClientInfo(groupId, client.getZone(), server.getIp());
                    client.getClient().setInput(client.getZone(), YxcZoneFeatures.GROUP_INPUT_ID, "");
                },
                () -> sameId(groupId, client.getClient().getDistributionInfo().getGroupId())
                        && client.getClient().getZoneStatus(client.getZone()).isLinkInput());
    }

    private void selectLinkInput(YxcGroupMember client) {
        runWithRetry(YxcGroupResult.Step.JOIN_CLIENT, client.getIp(),
                () -> client.getClient().setInput(client.getZone(), YxcZoneFeatures.GROUP_INPUT_ID, ""),
                () -> client.getClient().getZoneStatus(client.getZone()).isLinkInput());
    }

    private static boolean isMember(String groupId, YxcDistributionInfo info) {
        return info.getRole() == YxcDistributionInfo.Role.CLIENT && sameId(groupId, info.getGroupId());
    }

    private YxcGroupResult reduceGroup(YxcGroupMember server, YxcDistributionInfo info, List<YxcGroupMember> toRemove) {
        List<YxcGroupMember> present = new ArrayList<>();
        for (YxcGroupMember member : toRemove) {
            if (info.getClients().contains(member.getIp())) {
                present.add(member);
            }
        }
        if (present.isEmpty()) {
            return YxcGroupResult.success(info.getGroupId());
        }
        if (present.size() >= info.getClients().size()) {
            return closeGroup(server, info, present);
        }
        for (YxcGroupMember client : present) {
            leaveIfMember(client, info.getGroupId());
        }
        List<String> ips = ips(present);
        String zone = zoneOf(server, info);
        runWithRetry(YxcGroupResult.Step.REDUCE_SERVER, server.getIp(),
                () -> server.getClient().setServerInfo(info.getGroupId(), zone, YxcClientListAction.REMOVE, ips),
                () -> {
                    List<String> now = server.getClient().getDistributionInfo().getClients();
                    return Collections.disjoint(now, ips);
                });
        // clients remain: restart the distribution for them
        perform(YxcGroupResult.Step.REDUCE_SERVER, server.getIp(), () -> server.getClient().startDistribution(info.getClients().size()));
        return YxcGroupResult.success(info.getGroupId());
    }

    /** The known clients leave, then the server stops and clears its role; waits for the group id of zeros. */
    private YxcGroupResult closeGroup(YxcGroupMember server, YxcDistributionInfo info, List<YxcGroupMember> knownMembers) {
        for (YxcGroupMember member : knownMembers) {
            if (info.getClients().contains(member.getIp())) {
                leaveIfMember(member, info.getGroupId());
            }
        }
        runWithRetry(YxcGroupResult.Step.CLOSE_GROUP, server.getIp(),
                () -> {
                    try {
                        server.getClient().stopDistribution();
                    } catch (YxcException e) {
                        // a device that answers "no" has nothing to stop (or stopped already); clearing the role is what counts
                        if (e.getResponseCode() == YxcException.NONE) {
                            throw e;
                        }
                    }
                    server.getClient().clearServerInfo();
                },
                () -> server.getClient().getDistributionInfo().isNullGroup());
        return YxcGroupResult.success(null);
    }

    private void leaveGroup(YxcGroupMember member) {
        runWithRetry(YxcGroupResult.Step.LEAVE_GROUP, member.getIp(),
                () -> member.getClient().clearClientInfo(),
                () -> member.getClient().getDistributionInfo().isNullGroup());
    }

    /**
     * A server lists its clients, but a list is only a claim: the client leaves only if it reports this group's id
     * itself (a hostile or confused server must not take a device out of another group). A client that can not be read
     * is left to the server side.
     */
    private void leaveIfMember(YxcGroupMember member, String groupId) {
        try {
            YxcDistributionInfo own = member.getClient().getDistributionInfo();
            if (!sameId(groupId, own.getGroupId())) {
                return;
            }
        } catch (YxcException unreachable) {
            // not readable: the leave below is tried anyway, the server side removes it in any case
        }
        leaveQuietly(member);
    }

    /** For a client that may be gone: the server side decides whether the membership ends. */
    private void leaveQuietly(YxcGroupMember member) {
        try {
            leaveGroup(member);
        } catch (Abort ignored) {
            // an unreachable client cannot leave; the server removes it
        }
    }

    /** Best effort to take back a join; nobody can act on a failure here. */
    private void tryLeave(YxcGroupMember member) {
        try {
            member.getClient().clearClientInfo();
        } catch (YxcException ignored) {
            // the original failure is what the caller hears about
        }
    }

    private void ensurePowerOn(YxcGroupMember member) {
        YxcZoneStatus status = call(YxcGroupResult.Step.POWER_ON, member.getIp(), () -> member.getClient().getZoneStatus(member.getZone()));
        if (!status.isPowerOn()) {
            // not awaited: a device in standby needs longer than a group state does, and it takes the group calls anyway
            perform(YxcGroupResult.Step.POWER_ON, member.getIp(), () -> member.getClient().setPower(member.getZone(), true));
        }
    }

    private ServerState findServer(YxcGroupMember device, YxcDistributionInfo info, List<YxcGroupMember> known) {
        for (YxcGroupMember candidate : known) {
            if (candidate.getIp().equals(device.getIp())) {
                continue;
            }
            try {
                YxcDistributionInfo candidateInfo = candidate.getClient().getDistributionInfo();
                if (serves(candidateInfo) && sameId(info.getGroupId(), candidateInfo.getGroupId())
                        && candidateInfo.getClients().contains(device.getIp())) {
                    return new ServerState(candidate, candidateInfo);
                }
            } catch (YxcException unreachable) {
                // not the server we can talk to; the next candidate may be
            }
        }
        return null;
    }

    // ---- reading, waiting, retrying

    private YxcDistributionInfo readDistribution(YxcGroupMember member) {
        return call(YxcGroupResult.Step.READ_STATE, member.getIp(), () -> member.getClient().getDistributionInfo());
    }

    /** All devices are read before anything is changed, so an unreachable client changes nothing. */
    private List<YxcDistributionInfo> readDistributions(List<YxcGroupMember> members) {
        List<YxcDistributionInfo> infos = new ArrayList<>();
        for (YxcGroupMember member : members) {
            infos.add(readDistribution(member));
        }
        return infos;
    }

    /** Runs the action, waits for the state, and once more from the action if the state did not come. */
    private void runWithRetry(YxcGroupResult.Step step, String ip, Action action, Check expected) {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            perform(step, ip, action);
            if (await(step, ip, expected)) {
                return;
            }
        }
        throw new Abort(YxcGroupResult.timeout(step, ip, "the expected state was not reached"));
    }

    private boolean await(YxcGroupResult.Step step, String ip, Check expected) {
        int waited = 0;
        while (true) {
            if (call(step, ip, expected::holds)) {
                return true;
            }
            if (waited >= waitMillis) {
                return false;
            }
            try {
                sleeper.sleep(pollMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Abort(YxcGroupResult.failed(step, ip, "interrupted"));
            }
            waited += pollMillis;
        }
    }

    private static <T> T call(YxcGroupResult.Step step, String ip, Call<T> call) {
        try {
            return call.run();
        } catch (YxcException e) {
            throw new Abort(YxcGroupResult.failed(step, ip, e.getMessage()));
        }
    }

    private static void perform(YxcGroupResult.Step step, String ip, Action action) {
        try {
            action.run();
        } catch (YxcException e) {
            throw new Abort(YxcGroupResult.failed(step, ip, e.getMessage()));
        }
    }

    private YxcGroupResult run(Operation operation) {
        try {
            return operation.execute();
        } catch (Abort abort) {
            return abort.result;
        } catch (IllegalArgumentException e) {
            return YxcGroupResult.failed(YxcGroupResult.Step.VALIDATE, null, e.getMessage());
        }
    }

    // ---- helpers

    /**
     * The server takes the clients it has plus the new ones, up to the {@code client_max} of its own features. A device
     * reports 9 or 19 (verified on three devices), so no fixed number is used unless the features have none.
     */
    private void checkCapacity(YxcGroupMember server, YxcDistributionInfo serverInfo, List<YxcGroupMember> clients) {
        int max = clientMax(server);
        Set<String> all = new HashSet<>(serves(serverInfo) ? serverInfo.getClients() : Collections.<String>emptyList());
        all.addAll(ips(clients));
        require(all.size() <= max, "the device takes at most " + max + " clients, " + all.size() + " requested");
    }

    private int clientMax(YxcGroupMember server) {
        YxcFeatures features = call(YxcGroupResult.Step.READ_STATE, server.getIp(), () -> server.getClient().getFeatures());
        YxcDistributionFeatures distribution = features.getDistribution();
        Integer max = distribution == null ? null : distribution.getClientMax();
        return max == null || max < 1 ? FALLBACK_CLIENT_MAX : max;
    }

    private String newGroupId() {
        byte[] bytes = new byte[GROUP_ID_BYTES];
        random.nextBytes(bytes);
        StringBuilder id = new StringBuilder(GROUP_ID_BYTES * 2);
        for (byte b : bytes) {
            id.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
        }
        return id.toString();
    }

    private static void checkRequest(YxcGroupMember server, List<YxcGroupMember> clients) {
        require(server != null, "server is required");
        require(clients != null && !clients.isEmpty(), "at least one client is required");
        require(clients.size() <= YxcClient.MAX_CLIENT_ADDRESSES,
                "at most " + YxcClient.MAX_CLIENT_ADDRESSES + " clients per call");
        Set<String> seen = new HashSet<>();
        for (YxcGroupMember client : clients) {
            require(client != null, "a client is null");
            require(!client.getIp().equals(server.getIp()), "a device cannot be its own client");
            require(seen.add(client.getIp()), "a client is listed twice");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new Abort(YxcGroupResult.failed(YxcGroupResult.Step.VALIDATE, null, message));
        }
    }

    /** A device serves a group when it says so or lists clients (a server says {@code server}, a device in no group {@code none}; the word of a client is not captured yet). */
    private static boolean serves(YxcDistributionInfo info) {
        return info.getRole() == YxcDistributionInfo.Role.SERVER || !info.getClients().isEmpty();
    }

    private static boolean sameId(String expected, String actual) {
        return actual != null && expected.toUpperCase(Locale.ROOT).equals(actual.toUpperCase(Locale.ROOT));
    }

    private static String zoneOf(YxcGroupMember server, YxcDistributionInfo info) {
        String reported = info.getServerZone();
        return reported == null || reported.isEmpty() ? server.getZone() : reported;
    }

    private static List<String> ips(List<YxcGroupMember> members) {
        List<String> ips = new ArrayList<>(members.size());
        for (YxcGroupMember member : members) {
            ips.add(member.getIp());
        }
        return ips;
    }

    private interface Call<T> {
        T run() throws YxcException;
    }

    private interface Action {
        void run() throws YxcException;
    }

    private interface Check {
        boolean holds() throws YxcException;
    }

    private interface Operation {
        YxcGroupResult execute();
    }

    private static final class ServerState {
        final YxcGroupMember member;
        final YxcDistributionInfo info;

        ServerState(YxcGroupMember member, YxcDistributionInfo info) {
            this.member = member;
            this.info = info;
        }
    }

    /** Carries the result of an expected failure out of the nested steps; never leaves the service. */
    private static final class Abort extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final transient YxcGroupResult result;

        Abort(YxcGroupResult result) {
            super(result.toString(), null, false, false);
            this.result = result;
        }
    }
}
