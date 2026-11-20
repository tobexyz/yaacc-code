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
package de.yaacc.fcast.discovery;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * The FCast receivers of the lists: the directory's entries as devices, and the memory of the selected ones. Pure (the Android
 * parts are in {@link FCastHub}).
 *
 * <p><b>Why remember.</b> Receivers found by mDNS exist only while a scan runs, but a selected receiver must survive a restart
 * (the app drops a selected id that no device matches). So the selected FCast receivers (their address and name) are stored when
 * the selection changes and put into the directory as remembered entries at the next start: they are listed as offline until the
 * network announces them, and a play to one simply tries to connect.
 */
public final class FCastReceivers implements FCastReceiverSource {
    private final FCastDirectory directory;
    private final Supplier<FCastReceiverDevice.Labels> labels;
    private final FCastStoredReceivers remembered;
    // the device of each receiver as it was last made: the lists compare devices by object, so an unchanged receiver is the same object
    private final java.util.Map<String, FCastReceiverDevice> devices = new java.util.HashMap<>();

    public FCastReceivers(FCastDirectory directory, Supplier<FCastReceiverDevice.Labels> labels, FCastStoredReceivers remembered) {
        this.directory = directory;
        this.labels = labels;
        this.remembered = remembered;
    }

    /** Puts the remembered receivers of the last run into the directory. Call once at the start. */
    public void restore() {
        directory.setRemembered(remembered.list());
    }

    @Override
    public synchronized List<FCastReceiverDevice> receivers() {
        List<FCastReceiverDevice> result = new ArrayList<>();
        java.util.Set<String> current = new java.util.HashSet<>();
        FCastReceiverDevice.Labels now = labels.get();
        for (FCastDirectory.Entry entry : directory.snapshot()) {
            FCastReceiverDevice device = deviceFor(entry, now);
            if (device != null) {
                result.add(device);
                current.add(entry.getId());
            }
        }
        devices.keySet().retainAll(current); // a receiver that is gone is forgotten
        return result;
    }

    @Override
    public synchronized FCastReceiverDevice findReceiver(String receiverId) {
        FCastDirectory.Entry entry = directory.findById(receiverId);
        return entry == null ? null : deviceFor(entry, labels.get());
    }

    private FCastReceiverDevice deviceFor(FCastDirectory.Entry entry, FCastReceiverDevice.Labels now) {
        FCastReceiverDevice cached = devices.get(entry.getId());
        if (cached != null && cached.showsSame(entry, now)) {
            return cached;
        }
        FCastReceiverDevice made = FCastReceiverDevice.of(entry, now);
        if (made != null) {
            devices.put(entry.getId(), made);
        }
        return made;
    }

    @Override
    public void selectionChanged(Collection<String> receiverIds) {
        List<FCastDirectory.Known> before = remembered.list();
        List<FCastDirectory.Known> after = new ArrayList<>();
        TreeSet<String> ordered = new TreeSet<>(); // a fixed order, so that the same selection is the same list
        if (receiverIds != null) {
            for (String id : receiverIds) {
                if (id != null) {
                    ordered.add(id);
                }
            }
        }
        for (String id : ordered) {
            FCastEndpoint endpoint = FCastReceiverId.parse(id);
            if (endpoint == null) {
                continue;
            }
            after.add(new FCastDirectory.Known(endpoint, nameFor(endpoint, before)));
        }
        if (sameAs(before, after)) {
            return; // the selection is stored often; nothing to write for the same list
        }
        remembered.replaceAll(after);
        directory.setRemembered(remembered.list());
    }

    /** The name to remember: what the directory shows (not the bare address), else what was remembered before. */
    private String nameFor(FCastEndpoint endpoint, List<FCastDirectory.Known> before) {
        FCastDirectory.Entry entry = directory.find(endpoint);
        if (entry != null && !entry.getDisplayName().equals(endpoint.toString())) {
            return entry.getDisplayName();
        }
        for (FCastDirectory.Known known : before) {
            if (known.getEndpoint().equals(endpoint)) {
                return known.getName();
            }
        }
        return "";
    }

    private static boolean sameAs(List<FCastDirectory.Known> a, List<FCastDirectory.Known> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).getEndpoint().equals(b.get(i).getEndpoint()) || !a.get(i).getName().equals(b.get(i).getName())) {
                return false;
            }
        }
        return true;
    }
}
