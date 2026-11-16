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
package de.yaacc.upnp.server.openhome;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import de.yaacc.multiroom.SnapcastRendererOutput;
import de.yaacc.multiroom.SnapcastRendererSources;

/**
 * The source list of the OpenHome renderer as one way into the renderer's output setting (task 12.5). The list is built at every
 * call from the setting and the groups known now, so it can not drift from the settings screen. Choosing a source writes the
 * <b>same two preferences</b> the settings screen writes ({@link SnapcastRendererOutput#KEY_OUTPUT}, and for a group
 * {@link SnapcastRendererOutput#KEY_GROUP} with the receiver id) in one step; the rest follows from the listeners of those
 * preferences (the player service stops a player of another output, the hub remembers the server of the group).
 *
 * <p>The source a controller chooses comes from a list that may be older than the groups: it is checked against the groups of now,
 * and a group that vanished is refused with nothing written (the old output stays, the controller sees the old source, the user is
 * told). Choosing the source that is the current one changes nothing. Pure Java: the preferences, the groups and the message are
 * handed in.
 */
public final class RendererOutputSources implements OhSourceProvider {

    /** Why a choice was refused. */
    public enum Refusal {
        /** The group is not known (any more), or the source is only a placeholder. */
        GROUP_GONE,
        /** The setting could not be stored. */
        NOT_SAVED
    }

    /** Stores the preferences all or none; may throw a runtime exception. */
    public interface PrefWriter {
        void write(Map<String, String> values);
    }

    private final SnapcastRendererOutput.StringSource preferences;
    private final PrefWriter writer;
    private final Supplier<List<SnapcastRendererSources.GroupRef>> groups;
    private final SnapcastRendererSources.Labels labels;
    private final Consumer<Refusal> refusals;
    private final Object writeLock = new Object();

    /**
     * @param preferences reads a preference as text (the default preferences of the app)
     * @param groups      the groups known now (the Snapcast hub's)
     * @param refusals    told when a choice is refused (the app shows a message; it must be quick)
     */
    public RendererOutputSources(SnapcastRendererOutput.StringSource preferences, PrefWriter writer,
                                 Supplier<List<SnapcastRendererSources.GroupRef>> groups, SnapcastRendererSources.Labels labels,
                                 Consumer<Refusal> refusals) {
        this.preferences = preferences;
        this.writer = writer;
        this.groups = groups;
        this.labels = labels;
        this.refusals = refusals;
    }

    @Override
    public SnapcastRendererSources.Snapshot snapshot() {
        SnapcastRendererOutput.Setting setting;
        List<SnapcastRendererSources.GroupRef> known;
        try {
            setting = SnapcastRendererOutput.read(preferences);
            known = groups.get();
        } catch (RuntimeException unreadable) {
            return SnapcastRendererSources.build(null, null, labels); // this device alone: what a renderer without the groups says
        }
        return SnapcastRendererSources.build(setting, known, labels);
    }

    @Override
    public boolean select(SnapcastRendererSources.Entry entry) {
        if (entry == null) {
            return false;
        }
        SnapcastRendererSources.Target target = entry.target();
        if (target == null) {
            return refuse(Refusal.GROUP_GONE); // the placeholder of a group that is not available
        }
        synchronized (writeLock) {
            SnapcastRendererOutput.Setting now;
            List<SnapcastRendererSources.GroupRef> known;
            try {
                now = SnapcastRendererOutput.read(preferences);
                known = groups.get();
            } catch (RuntimeException unreadable) {
                return refuse(Refusal.NOT_SAVED);
            }
            Map<String, String> values = new LinkedHashMap<>();
            if (target.mode() == SnapcastRendererOutput.Mode.THIS_DEVICE) {
                if (now.mode() == SnapcastRendererOutput.Mode.THIS_DEVICE && !now.isDamaged()) {
                    return true; // already this device
                }
                values.put(SnapcastRendererOutput.KEY_OUTPUT, SnapcastRendererOutput.VALUE_DEVICE);
            } else {
                if (!isKnown(known, target.receiverId())) {
                    return refuse(Refusal.GROUP_GONE);
                }
                if (now.mode() == SnapcastRendererOutput.Mode.GROUP && target.receiverId().equals(now.receiverId())) {
                    return true; // already this group
                }
                values.put(SnapcastRendererOutput.KEY_OUTPUT, SnapcastRendererOutput.VALUE_GROUP);
                values.put(SnapcastRendererOutput.KEY_GROUP, target.receiverId());
            }
            try {
                writer.write(values);
            } catch (RuntimeException failed) {
                return refuse(Refusal.NOT_SAVED);
            }
            return true;
        }
    }

    private static boolean isKnown(List<SnapcastRendererSources.GroupRef> known, String receiverId) {
        if (receiverId == null || known == null) {
            return false;
        }
        for (SnapcastRendererSources.GroupRef group : new ArrayList<>(known)) {
            if (group != null && receiverId.equals(group.receiverId())) {
                return true;
            }
        }
        return false;
    }

    private boolean refuse(Refusal refusal) {
        try {
            refusals.accept(refusal);
        } catch (RuntimeException ignored) {
            // the message is a courtesy: the refusal stands
        }
        return false;
    }
}
